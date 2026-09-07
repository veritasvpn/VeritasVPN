package logging

import (
	"crypto/sha256"
	"encoding/hex"
	"net/url"
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

// RedactURL strips userinfo from a connection string so it can be logged.
// Service URLs for NATS, Redis and Postgres embed their password, and logs are
// far more widely readable than the Secret those passwords came from.
// Unparseable input is reported as "<redacted>" rather than echoed, since a
// value we cannot parse is the one most likely to leak something.
func RedactURL(raw string) string {
	if raw == "" {
		return ""
	}
	parsed, err := url.Parse(raw)
	if err != nil {
		return "<redacted>"
	}
	if parsed.User == nil {
		return raw
	}
	if name := parsed.User.Username(); name != "" {
		parsed.User = url.User(name)
	} else {
		parsed.User = nil
	}
	return parsed.String()
}
