package config

import (
	"os"
	"os/exec"
	"testing"
)

func TestUseMockBTCPay(t *testing.T) {
	t.Setenv("ALLOW_MOCK_BTCPAY", "")

	prod := &Config{Environment: "production", BTCPayAPIKey: "", BTCPayServerURL: ""}
	if prod.UseMockBTCPay() {
		t.Fatal("production must never use mock BTCPay")
	}

	devNoOptIn := &Config{Environment: "development", BTCPayAPIKey: "", BTCPayServerURL: ""}
	if devNoOptIn.UseMockBTCPay() {
		t.Fatal("dev without ALLOW_MOCK_BTCPAY must not mock")
	}

	t.Setenv("ALLOW_MOCK_BTCPAY", "true")
	devOptIn := &Config{Environment: "development", BTCPayAPIKey: "", BTCPayServerURL: ""}
	if !devOptIn.UseMockBTCPay() {
		t.Fatal("ALLOW_MOCK_BTCPAY=true should enable mock when BTCPay unset")
	}

	configured := &Config{
		Environment:     "development",
		BTCPayAPIKey:    "key",
		BTCPayServerURL: "https://btcpay.example",
	}
	if configured.UseMockBTCPay() {
		t.Fatal("configured BTCPay should not mock")
	}
}

func TestRequireBTCPayProduction(t *testing.T) {
	dev := &Config{Environment: "development"}
	if err := dev.RequireBTCPayProduction(); err != nil {
		t.Fatalf("dev should skip: %v", err)
	}

	prod := &Config{Environment: "production"}
	if err := prod.RequireBTCPayProduction(); err == nil {
		t.Fatal("expected missing secrets error")
	}

	ok := &Config{
		Environment:         "production",
		BTCPayServerURL:     "https://btcpay.example",
		BTCPayAPIKey:        "key",
		BTCPayStoreID:       "store",
		BTCPayWebhookSecret: "secret",
	}
	if err := ok.RequireBTCPayProduction(); err != nil {
		t.Fatal(err)
	}
}

// TestLoadAllowsVerifierWithoutPrivateKey ensures wg-manager/billing-style
// environments (public keys only) can call Load without os.Exit.
func TestLoadAllowsVerifierWithoutPrivateKey(t *testing.T) {
	t.Setenv("JWT_ED25519_PRIVATE_KEY", "")
	t.Setenv("JWT_ACTIVE_KEY_ID", "")
	t.Setenv("JWT_ED25519_PUBLIC_KEYS", `{"kid":"-----BEGIN PUBLIC KEY-----\nMFkw\n-----END PUBLIC KEY-----\n"}`)
	t.Setenv("AGENT_AUTH_TOKEN", "")
	t.Setenv("BROWSER_PROXY_HOST", "")
	t.Setenv("BROWSER_EXPECTED_EGRESS_IP", "")

	cfg := Load()
	if cfg.JWTPrivateKey != "" {
		t.Fatalf("expected empty private key, got %q", cfg.JWTPrivateKey)
	}
	if cfg.JWTPublicKeys == "" {
		t.Fatal("expected public keys to be loaded")
	}
	if cfg.JWTActiveKeyID != "" {
		t.Fatalf("expected empty active kid for verifier, got %q", cfg.JWTActiveKeyID)
	}
}

func TestEnvRequiredExitsWhenEmpty(t *testing.T) {
	if os.Getenv("TEST_ENV_REQUIRED_CHILD") == "1" {
		_ = envRequired("THIS_ENV_MUST_BE_UNSET_FOR_TEST")
		os.Exit(0)
	}
	cmd := exec.Command(os.Args[0], "-test.run=TestEnvRequiredExitsWhenEmpty")
	cmd.Env = append(os.Environ(), "TEST_ENV_REQUIRED_CHILD=1")
	err := cmd.Run()
	if err == nil {
		t.Fatal("expected child process to exit non-zero when envRequired key is empty")
	}
	if ee, ok := err.(*exec.ExitError); ok {
		if ee.ExitCode() == 0 {
			t.Fatal("expected non-zero exit")
		}
		return
	}
	t.Fatalf("unexpected error: %v", err)
}
