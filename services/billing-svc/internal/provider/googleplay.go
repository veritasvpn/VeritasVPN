package provider

import (
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
	LineItems            []PlayLineItem
}

type PlayLineItem struct {
	ProductID        string
	BasePlanID       string
	Expiry           time.Time
	AutoRenewEnabled bool
	PriceCents       int64
	Currency         string
	HasPrice         bool
}

// PlayClient fetches a subscription purchase from the Google Play Developer API.
type PlayClient interface {
	PackageName() string
	GetSubscription(ctx context.Context, purchaseToken string) (*SubscriptionPurchase, error)
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

	mu       sync.Mutex
	token    string
	tokenExp time.Time
}

func NewGooglePlayClient(packageName, serviceAccountPath string) (*GooglePlayClient, error) {
	body, err := readServiceAccount(serviceAccountPath)
	if err != nil {
		return nil, err
	}
	return newGooglePlayClient(packageName, body, "https://androidpublisher.googleapis.com", "")
}

func newGooglePlayClient(packageName string, keyJSON []byte, apiBase, tokenURI string) (*GooglePlayClient, error) {
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
		return ParseSubscriptionPurchase(body)
	case http.StatusNotFound:
		return nil, ErrPlayPurchaseNotFound
	case http.StatusBadRequest, http.StatusForbidden:
		return nil, ErrPlayRejected
	default:
		return nil, fmt.Errorf("google play subscription lookup returned %d", resp.StatusCode)
	}
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

func ParseSubscriptionPurchase(body []byte) (*SubscriptionPurchase, error) {
	var raw struct {
		SubscriptionState          string `json:"subscriptionState"`
		LatestOrderID              string `json:"latestOrderId"`
		StartTime                  string `json:"startTime"`
		AcknowledgementState       string `json:"acknowledgementState"`
		ExternalAccountIdentifiers struct {
			ObfuscatedExternalAccountID string `json:"obfuscatedExternalAccountId"`
		} `json:"externalAccountIdentifiers"`
		LineItems []struct {
			ProductID        string `json:"productId"`
			ExpiryTime       string `json:"expiryTime"`
			AutoRenewingPlan *struct {
				AutoRenewEnabled bool `json:"autoRenewEnabled"`
				RecurringPrice   *struct {
					CurrencyCode string `json:"currencyCode"`
					Units        string `json:"units"`
					Nanos        int64  `json:"nanos"`
				} `json:"recurringPrice"`
			} `json:"autoRenewingPlan"`
			OfferDetails *struct {
				BasePlanID string `json:"basePlanId"`
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
	for _, item := range raw.LineItems {
		expiry, err := time.Parse(time.RFC3339, item.ExpiryTime)
		if err != nil {
			return nil, fmt.Errorf("google play expiry time: %w", err)
		}
		line := PlayLineItem{
			ProductID: item.ProductID,
			Expiry:    expiry.UTC(),
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
