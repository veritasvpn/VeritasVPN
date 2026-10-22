package service

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strings"
)

// playAccountDomain separates this hash from any other use of the account id.
// The Android app uses the same value. It is not a credential. Do not rotate
// it: purchases made with the hash would stop matching.
const playAccountDomain = "veritasvpn-play-account-v1"

// obfuscatedPlayAccountID is the value set as Play Billing obfuscatedAccountId.
// It is 64 hex characters, which is the field's maximum length.
func obfuscatedPlayAccountID(accountID string) string {
	mac := hmac.New(sha256.New, []byte(playAccountDomain))
	_, _ = mac.Write([]byte(strings.TrimSpace(accountID)))
	return hex.EncodeToString(mac.Sum(nil))
}

// playAccountMatches accepts the current HMAC and the raw account id that
// older app versions stored with the purchase. A raw match keeps existing
// subscribers on Premium.
func playAccountMatches(accountID, obfuscated string) bool {
	accountID = strings.TrimSpace(accountID)
	obfuscated = strings.TrimSpace(obfuscated)
	if accountID == "" || obfuscated == "" {
		return false
	}
	if hmac.Equal([]byte(accountID), []byte(obfuscated)) {
		return true
	}
	sum := obfuscatedPlayAccountID(accountID)
	return hmac.Equal([]byte(sum), []byte(strings.ToLower(obfuscated)))
}

// isPlayAccountHash reports a 64-hex HMAC. Those values are not account ids.
// A 32-hex Account ID, and the short ids used in tests, stay on the legacy path.
func isPlayAccountHash(value string) bool {
	if len(value) != 64 {
		return false
	}
	for _, c := range value {
		switch {
		case c >= '0' && c <= '9':
		case c >= 'a' && c <= 'f':
		case c >= 'A' && c <= 'F':
		default:
			return false
		}
	}
	return true
}
