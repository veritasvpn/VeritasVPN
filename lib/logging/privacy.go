package logging

import (
	"crypto/sha256"
	"encoding/hex"
)

// HashIdentifier returns a stable, non-reversible identifier suitable for logs.
// Raw account IDs, emails, invoice IDs, and peer IDs must not be logged.
func HashIdentifier(value string) string {
	if value == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:8])
}
