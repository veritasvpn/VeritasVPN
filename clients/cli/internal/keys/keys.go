package keys

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"

	"golang.org/x/crypto/curve25519"
)

func Generate() (privateKey, publicKey string, err error) {
	var secret [32]byte
	if _, err := rand.Read(secret[:]); err != nil {
		return "", "", fmt.Errorf("generate key: %w", err)
	}

	secret[0] &= 248
	secret[31] &= 127
	secret[31] |= 64

	var pub [32]byte
	curve25519.ScalarBaseMult(&pub, &secret)

	privateKey = base64.StdEncoding.EncodeToString(secret[:])
	publicKey = base64.StdEncoding.EncodeToString(pub[:])
	return
}
