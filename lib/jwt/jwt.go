package jwt

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const (
	DefaultIssuer   = "https://api.veritasvpn.cloud"
	DefaultAudience = "veritasvpn-api"
	AccessTokenUse  = "access"
)

type Manager struct {
	hmacSecret     []byte
	privateKey     ed25519.PrivateKey
	publicKeys     map[string]ed25519.PublicKey
	activeKeyID    string
	issuer         string
	audience       string
	accessTokenTTL time.Duration
}

type Claims struct {
	jwt.RegisteredClaims
	AccountID string `json:"account_id"`
	Tier      string `json:"tier"`
	TokenUse  string `json:"token_use,omitempty"`
}

// NewManager retains the HMAC-only constructor for development and tests.
// Production should use NewManagerWithKeys so verifier services receive only
// public key material and cannot mint access tokens.
func NewManager(secret string, accessTTL, _ time.Duration) *Manager {
	return &Manager{
		hmacSecret:     []byte(secret),
		issuer:         DefaultIssuer,
		audience:       DefaultAudience,
		accessTokenTTL: accessTTL,
		publicKeys:     map[string]ed25519.PublicKey{},
	}
}

// NewManagerWithKeys configures Ed25519 signing/verification. publicKeysJSON
// is a JSON object whose values are PEM-encoded PKIX public keys. secret is an
// optional migration verifier for access tokens issued before the key rollout.
func NewManagerWithKeys(secret, privateKeyPEM, publicKeysJSON, activeKeyID, issuer, audience string, accessTTL time.Duration) (*Manager, error) {
	if strings.TrimSpace(issuer) == "" {
		issuer = DefaultIssuer
	}
	if strings.TrimSpace(audience) == "" {
		audience = DefaultAudience
	}
	m := &Manager{
		hmacSecret:     []byte(strings.TrimSpace(secret)),
		activeKeyID:    strings.TrimSpace(activeKeyID),
		issuer:         issuer,
		audience:       audience,
		accessTokenTTL: accessTTL,
		publicKeys:     make(map[string]ed25519.PublicKey),
	}

	if strings.TrimSpace(publicKeysJSON) != "" {
		var encoded map[string]string
		if err := json.Unmarshal([]byte(publicKeysJSON), &encoded); err != nil {
			return nil, fmt.Errorf("parse JWT_ED25519_PUBLIC_KEYS: %w", err)
		}
		for kid, value := range encoded {
			key, err := parsePublicKey(value)
			if err != nil {
				return nil, fmt.Errorf("parse public key %q: %w", kid, err)
			}
			m.publicKeys[kid] = key
		}
	}

	if strings.TrimSpace(privateKeyPEM) != "" {
		key, err := parsePrivateKey(privateKeyPEM)
		if err != nil {
			return nil, fmt.Errorf("parse JWT_ED25519_PRIVATE_KEY: %w", err)
		}
		m.privateKey = key
		if m.activeKeyID == "" {
			return nil, fmt.Errorf("JWT_ACTIVE_KEY_ID is required with an Ed25519 private key")
		}
		pub, ok := m.publicKeys[m.activeKeyID]
		if !ok || !pub.Equal(key.Public()) {
			return nil, fmt.Errorf("active Ed25519 public key does not match the private key")
		}
	}

	if len(m.privateKey) == 0 && len(m.hmacSecret) == 0 && len(m.publicKeys) == 0 {
		return nil, fmt.Errorf("no JWT signing or verification key configured")
	}
	return m, nil
}

func parsePrivateKey(value string) (ed25519.PrivateKey, error) {
	block, _ := pem.Decode([]byte(value))
	if block == nil {
		return nil, fmt.Errorf("invalid PEM")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	key, ok := parsed.(ed25519.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("key is not Ed25519")
	}
	return key, nil
}

func parsePublicKey(value string) (ed25519.PublicKey, error) {
	block, _ := pem.Decode([]byte(value))
	if block == nil {
		return nil, fmt.Errorf("invalid PEM")
	}
	parsed, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	key, ok := parsed.(ed25519.PublicKey)
	if !ok {
		return nil, fmt.Errorf("key is not Ed25519")
	}
	return key, nil
}

func (m *Manager) GenerateAccessToken(accountID, tier string) (string, int64, error) {
	now := time.Now()
	expires := now.Add(m.accessTokenTTL)
	claims := &Claims{
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    m.issuer,
			Subject:   accountID,
			Audience:  jwt.ClaimStrings{m.audience},
			IssuedAt:  jwt.NewNumericDate(now),
			NotBefore: jwt.NewNumericDate(now.Add(-5 * time.Second)),
			ExpiresAt: jwt.NewNumericDate(expires),
			ID:        generateJTI(),
		},
		AccountID: accountID,
		Tier:      tier,
		TokenUse:  AccessTokenUse,
	}

	if len(m.privateKey) > 0 {
		token := jwt.NewWithClaims(jwt.SigningMethodEdDSA, claims)
		token.Header["kid"] = m.activeKeyID
		signed, err := token.SignedString(m.privateKey)
		if err != nil {
			return "", 0, fmt.Errorf("sign token: %w", err)
		}
		return signed, expires.Unix(), nil
	}
	if len(m.hmacSecret) == 0 {
		return "", 0, fmt.Errorf("access-token signer is not configured")
	}
	if strings.EqualFold(strings.TrimSpace(os.Getenv("ENVIRONMENT")), "production") {
		return "", 0, fmt.Errorf("HS256 mint is disabled in production; configure JWT_ED25519_PRIVATE_KEY")
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signed, err := token.SignedString(m.hmacSecret)
	if err != nil {
		return "", 0, fmt.Errorf("sign token: %w", err)
	}
	return signed, expires.Unix(), nil
}

func (m *Manager) validMethods() []string {
	methods := []string{jwt.SigningMethodEdDSA.Alg()}
	if len(m.hmacSecret) > 0 {
		methods = append(methods, jwt.SigningMethodHS256.Alg())
	}
	return methods
}

func (m *Manager) ValidateAccessToken(tokenStr string) (*Claims, error) {
	usedLegacyHMAC := false
	token, err := jwt.ParseWithClaims(tokenStr, &Claims{}, func(t *jwt.Token) (interface{}, error) {
		switch t.Method.Alg() {
		case jwt.SigningMethodEdDSA.Alg():
			kid, _ := t.Header["kid"].(string)
			key, ok := m.publicKeys[kid]
			if !ok || kid == "" {
				return nil, fmt.Errorf("unknown signing key")
			}
			return key, nil
		case jwt.SigningMethodHS256.Alg():
			if len(m.hmacSecret) == 0 {
				return nil, fmt.Errorf("legacy signing key is disabled")
			}
			usedLegacyHMAC = true
			return m.hmacSecret, nil
		default:
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
	}, jwt.WithValidMethods(m.validMethods()), jwt.WithExpirationRequired())
	if err != nil {
		return nil, fmt.Errorf("parse token: %w", err)
	}
	claims, ok := token.Claims.(*Claims)
	if !ok || !token.Valid || claims.AccountID == "" || claims.Subject != claims.AccountID {
		return nil, fmt.Errorf("invalid token claims")
	}
	if usedLegacyHMAC {
		// Transitional HS256 tokens predate audience and purpose claims.
		if claims.Issuer != "veritasvpn" && claims.Issuer != m.issuer {
			return nil, fmt.Errorf("invalid token issuer")
		}
		return claims, nil
	}
	audienceOK := false
	for _, audience := range claims.Audience {
		if audience == m.audience {
			audienceOK = true
			break
		}
	}
	if claims.Issuer != m.issuer || !audienceOK || claims.TokenUse != AccessTokenUse {
		return nil, fmt.Errorf("invalid token purpose, issuer, or audience")
	}
	return claims, nil
}

func generateJTI() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%x%x", time.Now().UnixNano(), os.Getpid())
	}
	return fmt.Sprintf("%x", b)
}
