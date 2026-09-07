package crypto

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"sync"

	"golang.org/x/crypto/bcrypt"
)

func GenerateToken(length int) (string, error) {
	b := make([]byte, length)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("random read: %w", err)
	}
	return hex.EncodeToString(b), nil
}

func SHA256Hash(input string) string {
	hash := sha256.Sum256([]byte(input))
	return hex.EncodeToString(hash[:])
}

func GenerateRefreshToken() (string, error) {
	b := make([]byte, 48)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("random read: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func GenerateAccountID() (string, error) {
	// Account IDs are bearer credentials for anonymous accounts. Use 128 bits
	// of CSPRNG entropy so online guessing remains infeasible at any scale.
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("random read: %w", err)
	}
	return hex.EncodeToString(b), nil
}

// MaxPasswordBytes is bcrypt's own input limit. Anything longer is silently
// ignored by the algorithm, so accepting it buys no security and only gives a
// caller a way to push large buffers through the hashing path.
const MaxPasswordBytes = 72

func HashPassword(password string) (string, error) {
	if len(password) > MaxPasswordBytes {
		return "", fmt.Errorf("password exceeds %d bytes", MaxPasswordBytes)
	}
	bytes, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", fmt.Errorf("hash password: %w", err)
	}
	return string(bytes), nil
}

func CheckPassword(password, hash string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
	return err == nil
}

// Derived once at first use rather than hardcoded so it tracks DefaultCost.
var decoyHash = sync.OnceValue(func() []byte {
	hash, err := bcrypt.GenerateFromPassword([]byte("veritas decoy"), bcrypt.DefaultCost)
	if err != nil {
		return nil
	}
	return hash
})

// BurnPasswordCheck spends the same time as CheckPassword without a stored
// hash to compare against. Sign-in paths call it when no account matched, so
// the response time does not reveal whether an address is registered.
func BurnPasswordCheck(password string) {
	if hash := decoyHash(); hash != nil {
		_ = bcrypt.CompareHashAndPassword(hash, []byte(password))
	}
}

func GenerateResetToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("random read: %w", err)
	}
	return hex.EncodeToString(b), nil
}
