package provider

import (
	"bytes"
	"context"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const androidPublisherScope = "https://www.googleapis.com/auth/androidpublisher"

var (
	ErrPlayPurchaseNotFound = errors.New("google play purchase was not found")
	ErrPlayRejected         = errors.New("google play rejected the purchase")
)

// SubscriptionPurchase is the part of purchases.subscriptionsv2.get that
// billing uses to grant or revoke Premium.
type SubscriptionPurchase struct {
	State                string
	LatestOrderID        string
	Start                time.Time
	AcknowledgementState string
	ObfuscatedAccountID  string
	// PauseResumesAt is set when Google reports pausedStateContext.autoResumeTime.
	PauseResumesAt *time.Time
	LineItems      []PlayLineItem
}

type PlayLineItem struct {
	ProductID               string
	BasePlanID              string
	OfferID                 string
	OfferTags               []string
	Expiry                  time.Time
	AutoRenewEnabled        bool
	PriceCents              int64
	Currency                string
	HasPrice                bool
	InTrial                 bool
	LatestSuccessfulOrderID string
}

// PlayClient fetches a subscription purchase from the Google Play Developer API.
type PlayClient interface {
	PackageName() string
	GetSubscription(ctx context.Context, purchaseToken string) (*SubscriptionPurchase, error)
}

// VoidedPurchase is one refunded, voided, or charged-back Play order.
// PurchaseToken is a credential and must not be logged.
type VoidedPurchase struct {
	OrderID       string
	PurchaseToken string
}

// VoidedPurchaseLister lists voided Play orders. purchases.voidedpurchases.list
// returns only one-time products unless type=1, which also includes subscriptions.
type VoidedPurchaseLister interface {
	ListVoidedPurchases(ctx context.Context, start time.Time) ([]VoidedPurchase, error)
}

type serviceAccountKey struct {
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
}

// GooglePlayClient calls androidpublisher purchases.subscriptionsv2.get with a
// service-account access token. Tests can point APIBase and TokenURI at a
// local server.
type GooglePlayClient struct {
	packageName string
	email       string
	privateKey  *rsa.PrivateKey
	tokenURI    string
	APIBase     string
	HTTP        *http.Client
	now         func() time.Time
	trialDays   int

	mu       sync.Mutex
	token    string
	tokenExp time.Time
}

func NewGooglePlayClient(packageName, serviceAccountPath string, trialDays int) (*GooglePlayClient, error) {
	body, err := readServiceAccount(serviceAccountPath)
	if err != nil {
		return nil, err
	}
	return newGooglePlayClient(packageName, body, "https://androidpublisher.googleapis.com", "", trialDays)
}

func newGooglePlayClient(packageName string, keyJSON []byte, apiBase, tokenURI string, trialDays int) (*GooglePlayClient, error) {
	var key serviceAccountKey
	if err := json.Unmarshal(keyJSON, &key); err != nil {
		return nil, fmt.Errorf("read google play service account: %w", err)
	}
	if strings.TrimSpace(key.ClientEmail) == "" || strings.TrimSpace(key.PrivateKey) == "" {
		return nil, fmt.Errorf("google play service account is missing client_email or private_key")
	}
	block, _ := pem.Decode([]byte(key.PrivateKey))
	if block == nil {
		return nil, fmt.Errorf("google play service account private key is not PEM")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		parsed, err = x509.ParsePKCS1PrivateKey(block.Bytes)
		if err != nil {
			return nil, fmt.Errorf("parse google play private key: %w", err)
		}
	}
	rsaKey, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("google play private key is not RSA")
	}
	if strings.TrimSpace(packageName) == "" {
		return nil, fmt.Errorf("google play package name is required")
	}
	if tokenURI == "" {
		tokenURI = key.TokenURI
	}
	if tokenURI == "" {
		tokenURI = "https://oauth2.googleapis.com/token"
	}
	if apiBase == "" {
		apiBase = "https://androidpublisher.googleapis.com"
	}
	return &GooglePlayClient{
		packageName: packageName,
		email:       key.ClientEmail,
		privateKey:  rsaKey,
		tokenURI:    tokenURI,
		APIBase:     strings.TrimRight(apiBase, "/"),
		HTTP:        &http.Client{Timeout: 10 * time.Second},
		now:         time.Now,
		trialDays:   trialDays,
	}, nil
}

func readServiceAccount(path string) ([]byte, error) {
	if strings.TrimSpace(path) == "" {
		return nil, fmt.Errorf("google play service account path is empty")
	}
	body, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read google play service account: %w", err)
	}
	return body, nil
}

func (c *GooglePlayClient) PackageName() string { return c.packageName }

func (c *GooglePlayClient) GetSubscription(ctx context.Context, purchaseToken string) (*SubscriptionPurchase, error) {
	access, err := c.accessToken(ctx)
	if err != nil {
		return nil, err
	}
	endpoint := c.APIBase + "/androidpublisher/v3/applications/" + url.PathEscape(c.packageName) +
		"/purchases/subscriptionsv2/tokens/" + url.PathEscape(purchaseToken)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+access)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("google play subscription lookup: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	switch resp.StatusCode {
	case http.StatusOK:
		return ParseSubscriptionPurchase(body, c.now(), c.trialDays)
	case http.StatusNotFound:
		return nil, ErrPlayPurchaseNotFound
	case http.StatusBadRequest, http.StatusForbidden:
		return nil, ErrPlayRejected
	default:
		return nil, fmt.Errorf("google play subscription lookup returned %d", resp.StatusCode)
	}
}

// VoidedPurchaseLookback stays inside the Voided Purchases API's 30-day window.
const VoidedPurchaseLookback = 29 * 24 * time.Hour

const voidedPurchasePageLimit = 20

// ListVoidedPurchases calls purchases.voidedpurchases.list. type=1 is required:
// the default (0) omits subscription refunds, including GPA.xxxx..N renewals.
// The response body can contain purchase tokens, so errors do not include it.
func (c *GooglePlayClient) ListVoidedPurchases(ctx context.Context, start time.Time) ([]VoidedPurchase, error) {
	oldest := c.now().UTC().Add(-VoidedPurchaseLookback)
	if start.IsZero() || start.Before(oldest) {
		start = oldest
	}
	var out []VoidedPurchase
	pageToken := ""
	for page := 0; page < voidedPurchasePageLimit; page++ {
		batch, next, err := c.listVoidedPurchasePage(ctx, start, pageToken)
		if err != nil {
			return nil, err
		}
		out = append(out, batch...)
		if next == "" || next == pageToken {
			break
		}
		pageToken = next
	}
	return out, nil
}

func (c *GooglePlayClient) listVoidedPurchasePage(ctx context.Context, start time.Time, pageToken string) ([]VoidedPurchase, string, error) {
	access, err := c.accessToken(ctx)
	if err != nil {
		// The transport error can include an address. Do not return it.
		return nil, "", fmt.Errorf("google play voided purchases lookup failed")
	}
	query := url.Values{}
	// type=1: voided in-app products AND voided subscription orders.
	query.Set("type", "1")
	query.Set("maxResults", "1000")
	if pageToken == "" {
		query.Set("startTime", strconv.FormatInt(start.UTC().UnixMilli(), 10))
	} else {
		query.Set("token", pageToken)
	}
	endpoint := c.APIBase + "/androidpublisher/v3/applications/" + url.PathEscape(c.packageName) +
		"/purchases/voidedpurchases?" + query.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, "", err
	}
	req.Header.Set("Authorization", "Bearer "+access)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, "", fmt.Errorf("google play voided purchases lookup failed")
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, "", fmt.Errorf("google play voided purchases lookup failed")
	}
	if resp.StatusCode != http.StatusOK {
		return nil, "", fmt.Errorf("google play voided purchases returned %d", resp.StatusCode)
	}
	var raw struct {
		VoidedPurchases []struct {
			PurchaseToken string `json:"purchaseToken"`
			OrderID       string `json:"orderId"`
		} `json:"voidedPurchases"`
		TokenPagination struct {
			NextPageToken string `json:"nextPageToken"`
		} `json:"tokenPagination"`
	}
	if err := json.Unmarshal(body, &raw); err != nil {
		return nil, "", fmt.Errorf("google play voided purchases response was invalid")
	}
	out := make([]VoidedPurchase, 0, len(raw.VoidedPurchases))
	for _, item := range raw.VoidedPurchases {
		orderID := strings.TrimSpace(item.OrderID)
		token := strings.TrimSpace(item.PurchaseToken)
		if orderID == "" || token == "" {
			continue
		}
		out = append(out, VoidedPurchase{OrderID: orderID, PurchaseToken: token})
	}
	return out, strings.TrimSpace(raw.TokenPagination.NextPageToken), nil
}

// PlayOrderStatus is the refund flag from orders.get. PurchaseToken comes from
// the order payload and is a credential. Do not log it.
type PlayOrderStatus struct {
	Refunded      bool
	PurchaseToken string
}

// OrderRefunded reports whether Play has fully refunded this order.
// A refund that does not revoke access is omitted from voidedpurchases.list
// and does not send voidedPurchaseNotification. orders.get still shows it as
// REFUNDED, or with orderHistory.refundEvent. The response can contain a
// purchase token and a buyer address, so errors do not include the body.
func (c *GooglePlayClient) OrderRefunded(ctx context.Context, orderID string) (PlayOrderStatus, error) {
	orderID = strings.TrimSpace(orderID)
	if orderID == "" {
		return PlayOrderStatus{}, fmt.Errorf("google play order id is empty")
	}
	access, err := c.accessToken(ctx)
	if err != nil {
		return PlayOrderStatus{}, fmt.Errorf("google play order lookup failed")
	}
	endpoint := c.APIBase + "/androidpublisher/v3/applications/" + url.PathEscape(c.packageName) +
		"/orders/" + url.PathEscape(orderID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return PlayOrderStatus{}, err
	}
	req.Header.Set("Authorization", "Bearer "+access)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return PlayOrderStatus{}, fmt.Errorf("google play order lookup failed")
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return PlayOrderStatus{}, fmt.Errorf("google play order lookup failed")
	}
	if resp.StatusCode == http.StatusNotFound {
		return PlayOrderStatus{}, nil
	}
	if resp.StatusCode != http.StatusOK {
		return PlayOrderStatus{}, fmt.Errorf("google play order lookup returned %d", resp.StatusCode)
	}
	return parsePlayOrderStatus(body)
}

func parsePlayOrderStatus(body []byte) (PlayOrderStatus, error) {
	var raw struct {
		State         string `json:"state"`
		PurchaseToken string `json:"purchaseToken"`
		OrderHistory  struct {
			RefundEvent json.RawMessage `json:"refundEvent"`
		} `json:"orderHistory"`
	}
	if err := json.Unmarshal(body, &raw); err != nil {
		return PlayOrderStatus{}, fmt.Errorf("google play order response was invalid")
	}
	refundEvent := bytes.TrimSpace(raw.OrderHistory.RefundEvent)
	refunded := raw.State == "REFUNDED" || (len(refundEvent) > 0 && string(refundEvent) != "null")
	return PlayOrderStatus{
		Refunded:      refunded,
		PurchaseToken: strings.TrimSpace(raw.PurchaseToken),
	}, nil
}

func (c *GooglePlayClient) accessToken(ctx context.Context) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.token != "" && c.now().Before(c.tokenExp) {
		return c.token, nil
	}
	assertion, err := c.signedJWT()
	if err != nil {
		return "", err
	}
	form := url.Values{}
	form.Set("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
	form.Set("assertion", assertion)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.tokenURI, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", fmt.Errorf("google play token exchange: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("google play token exchange returned %d", resp.StatusCode)
	}
	var parsed struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return "", fmt.Errorf("google play token response: %w", err)
	}
	if parsed.AccessToken == "" {
		return "", fmt.Errorf("google play token response was empty")
	}
	ttl := time.Duration(parsed.ExpiresIn) * time.Second
	if ttl <= 0 || ttl > time.Hour {
		ttl = 50 * time.Minute
	}
	c.token = parsed.AccessToken
	c.tokenExp = c.now().Add(ttl - time.Minute)
	return c.token, nil
}

func (c *GooglePlayClient) signedJWT() (string, error) {
	now := c.now()
	claims := jwt.MapClaims{
		"iss":   c.email,
		"scope": androidPublisherScope,
		"aud":   c.tokenURI,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
	signed, err := token.SignedString(c.privateKey)
	if err != nil {
		return "", fmt.Errorf("sign google play assertion: %w", err)
	}
	return signed, nil
}

func ParseSubscriptionPurchase(body []byte, now time.Time, trialDays int) (*SubscriptionPurchase, error) {
	var raw struct {
		SubscriptionState          string `json:"subscriptionState"`
		LatestOrderID              string `json:"latestOrderId"`
		StartTime                  string `json:"startTime"`
		AcknowledgementState       string `json:"acknowledgementState"`
		ExternalAccountIdentifiers struct {
			ObfuscatedExternalAccountID string `json:"obfuscatedExternalAccountId"`
		} `json:"externalAccountIdentifiers"`
		PausedStateContext *struct {
			AutoResumeTime string `json:"autoResumeTime"`
		} `json:"pausedStateContext"`
		LineItems []struct {
			ProductID               string `json:"productId"`
			ExpiryTime              string `json:"expiryTime"`
			LatestSuccessfulOrderID string `json:"latestSuccessfulOrderId"`
			AutoRenewingPlan        *struct {
				AutoRenewEnabled bool `json:"autoRenewEnabled"`
				RecurringPrice   *struct {
					CurrencyCode string `json:"currencyCode"`
					Units        string `json:"units"`
					Nanos        int64  `json:"nanos"`
				} `json:"recurringPrice"`
			} `json:"autoRenewingPlan"`
			OfferDetails *struct {
				BasePlanID string   `json:"basePlanId"`
				OfferID    string   `json:"offerId"`
				OfferTags  []string `json:"offerTags"`
			} `json:"offerDetails"`
		} `json:"lineItems"`
	}
	if err := json.Unmarshal(body, &raw); err != nil {
		return nil, fmt.Errorf("google play subscription response: %w", err)
	}
	if raw.SubscriptionState == "" || len(raw.LineItems) == 0 {
		return nil, fmt.Errorf("google play subscription response was incomplete")
	}
	out := &SubscriptionPurchase{
		State:                raw.SubscriptionState,
		LatestOrderID:        raw.LatestOrderID,
		AcknowledgementState: raw.AcknowledgementState,
		ObfuscatedAccountID:  strings.TrimSpace(raw.ExternalAccountIdentifiers.ObfuscatedExternalAccountID),
	}
	if raw.StartTime != "" {
		start, err := time.Parse(time.RFC3339, raw.StartTime)
		if err != nil {
			return nil, fmt.Errorf("google play start time: %w", err)
		}
		out.Start = start.UTC()
	}
	if raw.PausedStateContext != nil && strings.TrimSpace(raw.PausedStateContext.AutoResumeTime) != "" {
		// A bad resume timestamp must not fail the lookup. Pause still removes
		// Premium; the app then says to resume in Google Play.
		if resumes, err := time.Parse(time.RFC3339, strings.TrimSpace(raw.PausedStateContext.AutoResumeTime)); err == nil {
			utc := resumes.UTC()
			out.PauseResumesAt = &utc
		}
	}
	for _, item := range raw.LineItems {
		expiry, err := time.Parse(time.RFC3339, item.ExpiryTime)
		if err != nil {
			return nil, fmt.Errorf("google play expiry time: %w", err)
		}
		line := PlayLineItem{
			ProductID:               item.ProductID,
			Expiry:                  expiry.UTC(),
			LatestSuccessfulOrderID: strings.TrimSpace(item.LatestSuccessfulOrderID),
		}
		if item.AutoRenewingPlan != nil {
			line.AutoRenewEnabled = item.AutoRenewingPlan.AutoRenewEnabled
			if price := item.AutoRenewingPlan.RecurringPrice; price != nil && price.CurrencyCode != "" {
				cents, err := moneyCents(price.Units, price.Nanos)
				if err != nil {
					return nil, err
				}
				line.HasPrice = true
				line.PriceCents = cents
				line.Currency = strings.ToLower(price.CurrencyCode)
			}
		}
		if item.OfferDetails != nil {
			line.BasePlanID = item.OfferDetails.BasePlanID
			line.OfferID = item.OfferDetails.OfferID
			line.OfferTags = item.OfferDetails.OfferTags
			// Detect trial: offerId contains "trial" or offerTags contains "trial"
			hasTrialTag := false
			for _, tag := range line.OfferTags {
				if strings.ToLower(tag) == "trial" {
					hasTrialTag = true
					break
				}
			}
			if !hasTrialTag && strings.Contains(strings.ToLower(line.OfferID), "trial") {
				hasTrialTag = true
			}
			// Only mark as in trial if we have the tag AND we're still in the trial period
			// Trial period is typically 7 days from start time
			if hasTrialTag {
				trialDuration := time.Duration(trialDays) * 24 * time.Hour
				trialEnd := out.Start.Add(trialDuration)
				line.InTrial = now.Before(trialEnd)
			}
		}
		out.LineItems = append(out.LineItems, line)
	}
	return out, nil
}

func moneyCents(units string, nanos int64) (int64, error) {
	units = strings.TrimSpace(units)
	if units == "" {
		units = "0"
	}
	var whole int64
	if _, err := fmt.Sscan(units, &whole); err != nil {
		return 0, fmt.Errorf("google play price units: %w", err)
	}
	if nanos < 0 {
		nanos = -nanos
	}
	return whole*100 + nanos/10000000, nil
}
