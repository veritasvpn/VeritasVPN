package provider

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const googleCertsURL = "https://www.googleapis.com/oauth2/v3/certs"

// PushVerifier checks the OIDC bearer token on a Pub/Sub push request.
type PushVerifier interface {
	Verify(ctx context.Context, authorizationHeader string) error
}

type pushClaims struct {
	jwt.RegisteredClaims
	Email         string `json:"email"`
	EmailVerified any    `json:"email_verified"`
}

// GooglePubSubAuth verifies a Real-time Developer Notifications push token.
// Audience is the push endpoint URL configured on the subscription. When
// ServiceAccountEmail is set, the token's email claim must match it.
type GooglePubSubAuth struct {
	Audience            string
	ServiceAccountEmail string
	CertsURL            string
	HTTP                *http.Client

	mu      sync.Mutex
	keys    map[string]*rsa.PublicKey
	fetched time.Time
}

func NewGooglePubSubAuth(audience, serviceAccountEmail string) *GooglePubSubAuth {
	return &GooglePubSubAuth{
		Audience:            strings.TrimSpace(audience),
		ServiceAccountEmail: strings.TrimSpace(serviceAccountEmail),
		CertsURL:            googleCertsURL,
		HTTP:                &http.Client{Timeout: 10 * time.Second},
	}
}

func (a *GooglePubSubAuth) Verify(ctx context.Context, authorizationHeader string) error {
	if a == nil || a.Audience == "" {
		return fmt.Errorf("google play push audience is not configured")
	}
	raw := strings.TrimSpace(strings.TrimPrefix(authorizationHeader, "Bearer "))
	if raw == "" || !strings.HasPrefix(authorizationHeader, "Bearer ") {
		return fmt.Errorf("missing push authorization")
	}
	claims := &pushClaims{}
	parser := jwt.NewParser(jwt.WithValidMethods([]string{jwt.SigningMethodRS256.Alg()}), jwt.WithExpirationRequired())
	token, err := parser.ParseWithClaims(raw, claims, func(token *jwt.Token) (interface{}, error) {
		kid, _ := token.Header["kid"].(string)
		if kid == "" {
			return nil, fmt.Errorf("push token is missing kid")
		}
		return a.publicKey(ctx, kid)
	})
	if err != nil || !token.Valid {
		return fmt.Errorf("push token rejected")
	}
	issuer := claims.Issuer
	if issuer != "accounts.google.com" && issuer != "https://accounts.google.com" {
		return fmt.Errorf("push token issuer rejected")
	}
	if !audienceMatches(claims.Audience, a.Audience) {
		return fmt.Errorf("push token audience rejected")
	}
	if !emailVerified(claims.EmailVerified) {
		return fmt.Errorf("push token email is not verified")
	}
	if a.ServiceAccountEmail != "" && !strings.EqualFold(claims.Email, a.ServiceAccountEmail) {
		return fmt.Errorf("push token service account rejected")
	}
	return nil
}

func audienceMatches(got jwt.ClaimStrings, want string) bool {
	for _, aud := range got {
		if aud == want {
			return true
		}
	}
	return false
}

func emailVerified(value any) bool {
	switch typed := value.(type) {
	case bool:
		return typed
	case string:
		return strings.EqualFold(typed, "true")
	default:
		return false
	}
}

func (a *GooglePubSubAuth) publicKey(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if key := a.keys[kid]; key != nil && time.Since(a.fetched) < time.Hour {
		return key, nil
	}
	if err := a.refresh(ctx); err != nil {
		return nil, err
	}
	key := a.keys[kid]
	if key == nil {
		return nil, fmt.Errorf("push signing key not found")
	}
	return key, nil
}

func (a *GooglePubSubAuth) refresh(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, a.CertsURL, nil)
	if err != nil {
		return err
	}
	resp, err := a.HTTP.Do(req)
	if err != nil {
		return fmt.Errorf("fetch google push certificates: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("google push certificates returned %d", resp.StatusCode)
	}
	var parsed struct {
		Keys []struct {
			Kid string `json:"kid"`
			Kty string `json:"kty"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return fmt.Errorf("google push certificates: %w", err)
	}
	keys := make(map[string]*rsa.PublicKey, len(parsed.Keys))
	for _, key := range parsed.Keys {
		if key.Kty != "" && key.Kty != "RSA" {
			continue
		}
		pub, err := rsaPublicKey(key.N, key.E)
		if err != nil {
			return err
		}
		keys[key.Kid] = pub
	}
	a.keys = keys
	a.fetched = time.Now()
	return nil
}

func rsaPublicKey(n, e string) (*rsa.PublicKey, error) {
	nBytes, err := base64.RawURLEncoding.DecodeString(n)
	if err != nil {
		return nil, fmt.Errorf("push key modulus: %w", err)
	}
	eBytes, err := base64.RawURLEncoding.DecodeString(e)
	if err != nil {
		return nil, fmt.Errorf("push key exponent: %w", err)
	}
	exponent := 0
	for _, b := range eBytes {
		exponent = exponent<<8 | int(b)
	}
	if exponent == 0 {
		return nil, fmt.Errorf("push key exponent is empty")
	}
	return &rsa.PublicKey{N: new(big.Int).SetBytes(nBytes), E: exponent}, nil
}
