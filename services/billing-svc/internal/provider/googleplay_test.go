package provider

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

func TestParseSubscriptionPurchase(t *testing.T) {
	body := []byte(`{
	  "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
	  "latestOrderId": "GPA.1234",
	  "startTime": "2026-10-01T00:00:00Z",
	  "acknowledgementState": "ACKNOWLEDGEMENT_STATE_PENDING",
	  "externalAccountIdentifiers": {"obfuscatedExternalAccountId": "abc123"},
	  "lineItems": [{
	    "productId": "premium_monthly",
	    "expiryTime": "2026-11-01T00:00:00Z",
	    "autoRenewingPlan": {
	      "autoRenewEnabled": true,
	      "recurringPrice": {"currencyCode": "USD", "units": "3", "nanos": 0}
	    },
	    "offerDetails": {"basePlanId": "monthly"}
	  }]
	}`)
	got, err := ParseSubscriptionPurchase(body, time.Date(2026, 10, 3, 0, 0, 0, 0, time.UTC), 7)
	if err != nil {
		t.Fatal(err)
	}
	if got.State != "SUBSCRIPTION_STATE_ACTIVE" || got.ObfuscatedAccountID != "abc123" {
		t.Fatalf("purchase = %+v", got)
	}
	if len(got.LineItems) != 1 || got.LineItems[0].ProductID != "premium_monthly" || got.LineItems[0].BasePlanID != "monthly" {
		t.Fatalf("line = %+v", got.LineItems)
	}
	if !got.LineItems[0].HasPrice || got.LineItems[0].PriceCents != 300 || got.LineItems[0].Currency != "usd" {
		t.Fatalf("price = %+v", got.LineItems[0])
	}
	expiry := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)
	if !got.LineItems[0].Expiry.Equal(expiry) {
		t.Fatalf("expiry = %s", got.LineItems[0].Expiry)
	}
}

func TestParseSubscriptionPurchase_TrialInsideWindow(t *testing.T) {
	// Trial started 3 days ago, within 7-day window → InTrial=true
	body := []byte(`{
	  "startTime":"2026-09-30T00:00:00Z",
	  "subscriptionState":"SUBSCRIPTION_STATE_ACTIVE",
	  "lineItems":[{
	    "productId":"premium_monthly",
	    "expiryTime":"2026-11-01T00:00:00Z",
	    "offerDetails":{"basePlanId":"monthly","offerId":"free-trial-7d","offerTags":["trial"]}
	  }]
	}`)
	now := time.Date(2026, 10, 3, 0, 0, 0, 0, time.UTC) // 3 days after start
	got, err := ParseSubscriptionPurchase(body, now, 7)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.LineItems) != 1 || !got.LineItems[0].InTrial {
		t.Fatalf("expected InTrial=true, got %+v", got.LineItems)
	}
}

func TestParseSubscriptionPurchase_OrderAndPause(t *testing.T) {
	body := []byte(`{
	  "subscriptionState": "SUBSCRIPTION_STATE_PAUSED",
	  "latestOrderId": "GPA.1234-5678-9012-34567..0",
	  "startTime": "2026-10-01T00:00:00Z",
	  "pausedStateContext": {"autoResumeTime": "2026-11-15T00:00:00Z"},
	  "lineItems": [{
	    "productId": "premium_monthly",
	    "expiryTime": "2026-11-01T00:00:00Z",
	    "latestSuccessfulOrderId": "GPA.1234-5678-9012-34567..0",
	    "autoRenewingPlan": {
	      "autoRenewEnabled": true,
	      "recurringPrice": {"currencyCode": "PYG", "units": "20000", "nanos": 0}
	    }
	  }]
	}`)
	got, err := ParseSubscriptionPurchase(body, time.Date(2026, 10, 26, 0, 0, 0, 0, time.UTC), 7)
	if err != nil {
		t.Fatal(err)
	}
	if got.State != "SUBSCRIPTION_STATE_PAUSED" || got.LatestOrderID != "GPA.1234-5678-9012-34567..0" {
		t.Fatalf("purchase = %+v", got)
	}
	if got.PauseResumesAt == nil || !got.PauseResumesAt.Equal(time.Date(2026, 11, 15, 0, 0, 0, 0, time.UTC)) {
		t.Fatalf("resume = %v", got.PauseResumesAt)
	}
	line := got.LineItems[0]
	if line.LatestSuccessfulOrderID != "GPA.1234-5678-9012-34567..0" || !line.HasPrice || line.PriceCents != 2000000 || line.Currency != "pyg" {
		t.Fatalf("line = %+v", line)
	}
}

func TestParseSubscriptionPurchase_TrialOutsideWindow(t *testing.T) {
	// Trial started 8 days ago, outside 7-day window → InTrial=false
	body := []byte(`{
	  "startTime":"2026-09-25T00:00:00Z",
	  "subscriptionState":"SUBSCRIPTION_STATE_ACTIVE",
	  "lineItems":[{
	    "productId":"premium_monthly",
	    "expiryTime":"2026-11-01T00:00:00Z",
	    "offerDetails":{"basePlanId":"monthly","offerId":"free-trial-7d","offerTags":["trial"]}
	  }]
	}`)
	now := time.Date(2026, 10, 3, 0, 0, 0, 0, time.UTC) // 8 days after start
	got, err := ParseSubscriptionPurchase(body, now, 7)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.LineItems) != 1 || got.LineItems[0].InTrial {
		t.Fatalf("expected InTrial=false, got %+v", got.LineItems)
	}
}

func TestGooglePlayClientUsesMockedGoogle(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	var sawAuth bool
	tokenSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"access_token":"ya29.test","expires_in":3600}`))
	}))
	defer tokenSrv.Close()
	apiSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer ya29.test" {
			t.Errorf("authorization = %q", r.Header.Get("Authorization"))
		}
		if r.URL.Path != "/androidpublisher/v3/applications/cloud.veritasvpn/purchases/subscriptionsv2/tokens/token-abc" {
			t.Errorf("path = %s", r.URL.Path)
		}
		sawAuth = true
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
		  "subscriptionState":"SUBSCRIPTION_STATE_CANCELED",
		  "externalAccountIdentifiers":{"obfuscatedExternalAccountId":"acc-1"},
		  "lineItems":[{"productId":"premium_annual","expiryTime":"2027-10-01T00:00:00Z","autoRenewingPlan":{"autoRenewEnabled":false},"offerDetails":{"basePlanId":"yearly"}}]
		}`))
	}))
	defer apiSrv.Close()

	pemKey := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	raw, err := json.Marshal(map[string]string{
		"client_email": "play@example.iam.gserviceaccount.com",
		"private_key":  string(pemKey),
		"token_uri":    tokenSrv.URL,
	})
	if err != nil {
		t.Fatal(err)
	}
	client, err := newGooglePlayClient("cloud.veritasvpn", raw, apiSrv.URL, tokenSrv.URL, 7)
	if err != nil {
		t.Fatal(err)
	}
	purchase, err := client.GetSubscription(context.Background(), "token-abc")
	if err != nil {
		t.Fatal(err)
	}
	if !sawAuth || purchase.LineItems[0].ProductID != "premium_annual" || purchase.LineItems[0].BasePlanID != "yearly" {
		t.Fatalf("purchase = %+v saw=%v", purchase, sawAuth)
	}
	missing := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"access_token":"ya29.test","expires_in":3600}`))
			return
		}
		http.NotFound(w, r)
	}))
	defer missing.Close()
	client.APIBase = missing.URL
	client.tokenURI = missing.URL + "/token"
	client.token = ""
	_, err = client.GetSubscription(context.Background(), "gone")
	if err != ErrPlayPurchaseNotFound {
		t.Fatalf("missing purchase err = %v", err)
	}
}

func TestListVoidedPurchasesIncludesSubscriptionOrders(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 10, 28, 0, 0, 0, 0, time.UTC)
	wantStart := strconv.FormatInt(now.Add(-VoidedPurchaseLookback).UnixMilli(), 10)
	var pages int
	apiSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		pages++
		if r.URL.Path != "/androidpublisher/v3/applications/cloud.veritasvpn/purchases/voidedpurchases" {
			t.Errorf("path = %s", r.URL.Path)
		}
		if strings.Contains(r.URL.RawQuery, "purchaseToken") || strings.Contains(r.URL.Path, "tok-") {
			t.Errorf("request exposed a purchase token: %s", r.URL.RequestURI())
		}
		q := r.URL.Query()
		if q.Get("type") != "1" {
			t.Errorf("type = %q, want 1 so subscription voids are included", q.Get("type"))
		}
		if r.Header.Get("Authorization") != "Bearer ya29.test" {
			t.Errorf("authorization = %q", r.Header.Get("Authorization"))
		}
		w.Header().Set("Content-Type", "application/json")
		if pages == 1 {
			if q.Get("startTime") != wantStart {
				t.Errorf("startTime = %q, want %s", q.Get("startTime"), wantStart)
			}
			if q.Get("token") != "" {
				t.Errorf("first page token = %q", q.Get("token"))
			}
			_, _ = w.Write([]byte(`{
			  "voidedPurchases": [
			    {"orderId":"GPA.3305-8757-7634-59491","purchaseToken":"tok-initial","voidedReason":1},
			    {"orderId":"GPA.3305-8757-7634-59491..2","purchaseToken":"tok-renewal","voidedReason":7}
			  ],
			  "tokenPagination": {"nextPageToken":"next-page"}
			}`))
			return
		}
		if q.Get("token") != "next-page" {
			t.Errorf("page token = %q", q.Get("token"))
		}
		_, _ = w.Write([]byte(`{"voidedPurchases":[{"orderId":"GPA.3305-8757-7634-59491..3","purchaseToken":"tok-renewal"}]}`))
	}))
	defer apiSrv.Close()

	pemKey := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	raw, err := json.Marshal(map[string]string{
		"client_email": "play@example.iam.gserviceaccount.com",
		"private_key":  string(pemKey),
		"token_uri":    "https://oauth2.googleapis.com/token",
	})
	if err != nil {
		t.Fatal(err)
	}
	client, err := newGooglePlayClient("cloud.veritasvpn", raw, apiSrv.URL, "https://oauth2.googleapis.com/token", 7)
	if err != nil {
		t.Fatal(err)
	}
	client.now = func() time.Time { return now }
	client.token = "ya29.test"
	client.tokenExp = now.Add(time.Hour)

	got, err := client.ListVoidedPurchases(context.Background(), now.Add(-40*24*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if pages != 2 || len(got) != 3 {
		t.Fatalf("pages=%d purchases=%d", pages, len(got))
	}
	if got[0].OrderID != "GPA.3305-8757-7634-59491" || got[1].OrderID != "GPA.3305-8757-7634-59491..2" || got[2].OrderID != "GPA.3305-8757-7634-59491..3" {
		t.Fatalf("orders = %+v", got)
	}
	if got[1].PurchaseToken != "tok-renewal" {
		t.Fatalf("renewal token was not kept for the subscription lookup")
	}

	failSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, `{"purchaseToken":"secret-purchase-token"}`, http.StatusInternalServerError)
	}))
	defer failSrv.Close()
	client.APIBase = failSrv.URL
	_, err = client.ListVoidedPurchases(context.Background(), now)
	if err == nil || strings.Contains(err.Error(), "secret-purchase-token") {
		t.Fatalf("error = %v", err)
	}
	if err.Error() != "google play voided purchases returned 500" {
		t.Fatalf("error = %v", err)
	}
}

func TestParsePlayOrderStatusRefundWithoutRevoke(t *testing.T) {
	refunded, err := parsePlayOrderStatus([]byte(`{
	  "orderId":"GPA.3305-8757-7634-59491..2",
	  "state":"REFUNDED",
	  "purchaseToken":"secret-purchase-token",
	  "orderHistory":{"refundEvent":{"eventTime":"2026-10-27T23:48:00Z","refundReason":"OTHER"}}
	}`))
	if err != nil || !refunded.Refunded || refunded.PurchaseToken != "secret-purchase-token" {
		t.Fatalf("status = %+v err=%v", refunded, err)
	}
	open, err := parsePlayOrderStatus([]byte(`{"state":"PROCESSED","orderHistory":{"refundEvent":null}}`))
	if err != nil || open.Refunded {
		t.Fatalf("open order = %+v err=%v", open, err)
	}
	fromEvent, err := parsePlayOrderStatus([]byte(`{"state":"PROCESSED","orderHistory":{"refundEvent":{"refundReason":"CHARGEBACK"}}}`))
	if err != nil || !fromEvent.Refunded {
		t.Fatalf("chargeback = %+v err=%v", fromEvent, err)
	}
}

func TestOrderRefundedOmitsTokenFromErrors(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 10, 28, 0, 0, 0, 0, time.UTC)
	const orderID = "GPA.3305-8757-7634-59491..2"
	apiSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/androidpublisher/v3/applications/cloud.veritasvpn/orders/"+orderID {
			t.Errorf("path = %s", r.URL.Path)
		}
		if strings.Contains(r.URL.Path, "secret-purchase-token") {
			t.Errorf("path contained a purchase token")
		}
		http.Error(w, `{"purchaseToken":"secret-purchase-token","buyerAddress":{"buyerState":"x"}}`, http.StatusInternalServerError)
	}))
	defer apiSrv.Close()
	pemKey := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
	raw, err := json.Marshal(map[string]string{
		"client_email": "play@example.iam.gserviceaccount.com",
		"private_key":  string(pemKey),
		"token_uri":    "https://oauth2.googleapis.com/token",
	})
	if err != nil {
		t.Fatal(err)
	}
	client, err := newGooglePlayClient("cloud.veritasvpn", raw, apiSrv.URL, "https://oauth2.googleapis.com/token", 7)
	if err != nil {
		t.Fatal(err)
	}
	client.now = func() time.Time { return now }
	client.token = "ya29.test"
	client.tokenExp = now.Add(time.Hour)
	_, err = client.OrderRefunded(context.Background(), orderID)
	if err == nil || strings.Contains(err.Error(), "secret-purchase-token") || strings.Contains(err.Error(), "buyerAddress") {
		t.Fatalf("error = %v", err)
	}
}

func TestPubSubPushAuthAcceptsGoogleShapedToken(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	n := base64.RawURLEncoding.EncodeToString(key.N.Bytes())
	eBytes := big.NewInt(int64(key.E)).Bytes()
	e := base64.RawURLEncoding.EncodeToString(eBytes)
	certs := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"keys":[{"kty":"RSA","kid":"test","n":"` + n + `","e":"` + e + `"}]}`))
	}))
	defer certs.Close()

	auth := NewGooglePubSubAuth("https://api.veritasvpn.cloud/api/v1/billing/webhook/google-play", "push@example.iam.gserviceaccount.com")
	auth.CertsURL = certs.URL
	claims := jwt.MapClaims{
		"iss":            "https://accounts.google.com",
		"aud":            auth.Audience,
		"email":          "push@example.iam.gserviceaccount.com",
		"email_verified": true,
		"iat":            time.Now().Add(-time.Minute).Unix(),
		"exp":            time.Now().Add(time.Hour).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
	token.Header["kid"] = "test"
	signed, err := token.SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	if err := auth.Verify(context.Background(), "Bearer "+signed); err != nil {
		t.Fatal(err)
	}
	if err := auth.Verify(context.Background(), "Bearer not-a-token"); err == nil {
		t.Fatal("expected rejected token")
	}
}
