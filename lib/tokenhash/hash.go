package tokenhash

import (
	"crypto/sha256"
	"encoding/hex"
)

// Hash returns the SHA-256 hex digest used for refresh/access token storage
// and Redis logout blacklist keys (auth-svc and billing-svc must match).
func Hash(input string) string {
	sum := sha256.Sum256([]byte(input))
	return hex.EncodeToString(sum[:])
}
