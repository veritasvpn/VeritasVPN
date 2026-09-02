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

func generateAnonDeviceID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", fmt.Errorf("generate anon device id: %w", err)
	}
	// RFC 4122 version 4 style UUID without importing google/uuid.
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("anon-%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}
