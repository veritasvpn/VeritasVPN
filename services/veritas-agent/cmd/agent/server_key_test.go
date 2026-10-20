package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.zx2c4.com/wireguard/wgctrl/wgtypes"
)

func TestLoadServerKeyGeneratesOnlyWhenMissing(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	path := filepath.Join(dir, "private.key")
	generated := 0
	key, created, err := loadServerKey(path, serverKeyFS{
		read: os.ReadFile,
		write: func(name string, body []byte) error {
			if err := os.MkdirAll(filepath.Dir(name), 0o700); err != nil {
				return err
			}
			return os.WriteFile(name, body, 0o600)
		},
	}, func() (wgtypes.Key, error) {
		generated++
		return wgtypes.GeneratePrivateKey()
	})
	if err != nil {
		t.Fatal(err)
	}
	if !created || generated != 1 {
		t.Fatalf("created=%v generated=%d", created, generated)
	}
	again, created, err := loadServerKey(path, serverKeyFS{read: os.ReadFile, write: osWrite}, func() (wgtypes.Key, error) {
		t.Fatal("generate called for an existing key")
		return wgtypes.Key{}, errors.New("unused")
	})
	if err != nil {
		t.Fatal(err)
	}
	if created || again.String() != key.String() {
		t.Fatalf("existing key was not reused")
	}
}

func TestLoadServerKeyRefusesUnreadableOrUnparseableFile(t *testing.T) {
	t.Parallel()
	readErr := errors.New("permission denied")
	_, created, err := loadServerKey("/etc/wireguard/private.key", serverKeyFS{
		read:  func(string) ([]byte, error) { return nil, readErr },
		write: func(string, []byte) error { t.Fatal("wrote a replacement key"); return nil },
	}, func() (wgtypes.Key, error) {
		t.Fatal("generated a replacement key")
		return wgtypes.Key{}, errors.New("unused")
	})
	if err == nil || created {
		t.Fatalf("unreadable file err=%v created=%v", err, created)
	}
	if !strings.Contains(err.Error(), "read wireguard private key") {
		t.Fatalf("error = %v", err)
	}

	_, _, err = loadServerKey("/etc/wireguard/private.key", serverKeyFS{
		read:  func(string) ([]byte, error) { return []byte("not-a-key\n"), nil },
		write: func(string, []byte) error { t.Fatal("wrote a replacement key"); return nil },
	}, func() (wgtypes.Key, error) {
		t.Fatal("generated a replacement key")
		return wgtypes.Key{}, errors.New("unused")
	})
	if err == nil || !strings.Contains(err.Error(), "parse wireguard private key") {
		t.Fatalf("parse error = %v", err)
	}
}

func TestLoadServerKeyDoesNotKeepAKeyItCouldNotStore(t *testing.T) {
	t.Parallel()
	_, created, err := loadServerKey("/missing/private.key", serverKeyFS{
		read:  func(string) ([]byte, error) { return nil, os.ErrNotExist },
		write: func(string, []byte) error { return errors.New("read-only file system") },
	}, wgtypes.GeneratePrivateKey)
	if err == nil || created {
		t.Fatalf("err=%v created=%v", err, created)
	}
	if strings.Contains(err.Error(), "private key") && strings.Contains(err.Error(), "wgtypes") {
		t.Fatalf("error leaked key material: %v", err)
	}
}

func osWrite(name string, body []byte) error {
	return os.WriteFile(name, body, 0o600)
}
