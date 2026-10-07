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
	got, err := ParseSubscriptionPurchase(body)
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
	client, err := newGooglePlayClient("cloud.veritasvpn", raw, apiSrv.URL, tokenSrv.URL)
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
