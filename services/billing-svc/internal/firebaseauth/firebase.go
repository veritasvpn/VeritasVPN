package firebaseauth

import (
	"context"
	"crypto/rsa"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"crypto/x509"

	"github.com/golang-jwt/jwt/v5"
)

const googleCertsURL = "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"

// Verifier validates Firebase ID tokens for a project without a service account.
type Verifier struct {
	projectID string
	client    *http.Client

	mu        sync.RWMutex
	certs     map[string]*rsa.PublicKey
	expiresAt time.Time
}

func NewVerifier(projectID string) *Verifier {
	return &Verifier{
		projectID: projectID,
		client:    &http.Client{Timeout: 10 * time.Second},
		certs:     make(map[string]*rsa.PublicKey),
	}
}

type firebaseClaims struct {
	UserID string `json:"user_id"`
	Email  string `json:"email"`
	jwt.RegisteredClaims
}

// Verify returns the Firebase UID for a valid ID token.
func (v *Verifier) Verify(ctx context.Context, idToken string) (string, error) {
	if idToken == "" {
		return "", errors.New("missing token")
	}

	if err := v.ensureCerts(ctx); err != nil {
		return "", err
	}

	parser := jwt.NewParser(jwt.WithValidMethods([]string{"RS256"}))
	token, err := parser.ParseWithClaims(idToken, &firebaseClaims{}, func(t *jwt.Token) (interface{}, error) {
		kid, _ := t.Header["kid"].(string)
		v.mu.RLock()
		key, ok := v.certs[kid]
		v.mu.RUnlock()
		if !ok {
			// refresh once on unknown kid
			if refreshErr := v.refreshCerts(ctx); refreshErr != nil {
				return nil, refreshErr
			}
			v.mu.RLock()
			defer v.mu.RUnlock()
			key, ok = v.certs[kid]
			if !ok {
				return nil, fmt.Errorf("unknown kid %q", kid)
			}
			return key, nil
		}
		return key, nil
	})
	if err != nil {
		return "", fmt.Errorf("parse token: %w", err)
	}

	claims, ok := token.Claims.(*firebaseClaims)
	if !ok || !token.Valid {
		return "", errors.New("invalid token claims")
	}

	issuer := "https://securetoken.google.com/" + v.projectID
	if claims.Issuer != issuer {
		return "", fmt.Errorf("invalid issuer")
	}
	if len(claims.Audience) == 0 || claims.Audience[0] != v.projectID {
		return "", fmt.Errorf("invalid audience")
	}

	uid := claims.Subject
	if uid == "" {
		uid = claims.UserID
	}
	if uid == "" {
		return "", errors.New("missing uid")
	}
	return uid, nil
}

func (v *Verifier) ensureCerts(ctx context.Context) error {
	v.mu.RLock()
	fresh := time.Now().Before(v.expiresAt) && len(v.certs) > 0
	v.mu.RUnlock()
	if fresh {
		return nil
	}
	return v.refreshCerts(ctx)
}

func (v *Verifier) refreshCerts(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, googleCertsURL, nil)
	if err != nil {
		return err
	}
	resp, err := v.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("google certs: status %d", resp.StatusCode)
	}

	var raw map[string]string
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		return err
	}

	certs := make(map[string]*rsa.PublicKey, len(raw))
	for kid, pemStr := range raw {
		block, _ := pem.Decode([]byte(pemStr))
		if block == nil {
			continue
		}
		cert, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			continue
		}
		pub, ok := cert.PublicKey.(*rsa.PublicKey)
		if !ok {
			continue
		}
		certs[kid] = pub
	}
	if len(certs) == 0 {
		return errors.New("no firebase certs loaded")
	}

	ttl := 1 * time.Hour
	if cc := resp.Header.Get("Cache-Control"); cc != "" {
		for _, part := range strings.Split(cc, ",") {
			part = strings.TrimSpace(part)
			if strings.HasPrefix(part, "max-age=") {
				if sec, err := time.ParseDuration(strings.TrimPrefix(part, "max-age=") + "s"); err == nil {
					ttl = sec
				}
			}
		}
	}

	v.mu.Lock()
	v.certs = certs
	v.expiresAt = time.Now().Add(ttl)
	v.mu.Unlock()
	return nil
}
