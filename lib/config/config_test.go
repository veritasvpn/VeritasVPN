package config

import "testing"

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
