package main

import (
	"errors"
	"fmt"
	"os"
	"strings"

	"golang.zx2c4.com/wireguard/wgctrl/wgtypes"
)

// keyReader reports os.ErrNotExist when the key file is absent. Any other
// error means the file is there and cannot be used.
type serverKeyFS struct {
	read  func(string) ([]byte, error)
	write func(string, []byte) error
}

// loadServerKey returns the key on disk. It generates a key only when the
// file does not exist, and then only if that new key can be written.
// A file that exists but cannot be read or parsed is an error.
func loadServerKey(path string, fs serverKeyFS, generate func() (wgtypes.Key, error)) (wgtypes.Key, bool, error) {
	data, err := fs.read(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			key, genErr := generate()
			if genErr != nil {
				return wgtypes.Key{}, false, fmt.Errorf("generate wireguard private key: %w", genErr)
			}
			if writeErr := fs.write(path, []byte(key.String()+"\n")); writeErr != nil {
				return wgtypes.Key{}, false, fmt.Errorf("write new wireguard private key: %w", writeErr)
			}
			return key, true, nil
		}
		return wgtypes.Key{}, false, fmt.Errorf("read wireguard private key: %w", err)
	}
	key, err := wgtypes.ParseKey(strings.TrimSpace(string(data)))
	if err != nil {
		return wgtypes.Key{}, false, fmt.Errorf("parse wireguard private key: %w", err)
	}
	return key, false, nil
}
