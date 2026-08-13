package service

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
)

// generatePSK returns a WireGuard-compatible 32-byte key as standard base64.
func generatePSK() (string, error) {
	var key [32]byte
	if _, err := rand.Read(key[:]); err != nil {
		return "", fmt.Errorf("generate psk: %w", err)
	}
	return base64.StdEncoding.EncodeToString(key[:]), nil
}
