package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	ServerPort  string
	DatabaseURL string
	RedisURL    string
	NatsURL     string
	JWTSecret   string
	LogLevel    string
	Environment string

	StripeSecretKey     string
	StripeWebhookSecret string
	BTCPayServerURL     string
	BTCPayAPIKey        string
	BTCPayStoreID       string
	BTCPayWebhookSecret string
	BTCPayPublicURL     string
	BitcoinReadinessURL string

	PremiumPriceUSDCents int64
	PremiumPeriodDays    int
	FirebaseProjectID    string
	CheckoutSuccessURL   string
	CheckoutCancelURL    string
	CORSOrigins          string
	BillingPublicURL     string

	AgentAuthToken string

	ResendAPIKey        string
	TurnstileSecretKey  string
	PublicBaseURL       string

	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration
}

func Load() *Config {
	return &Config{
		ServerPort:  envOrDefault("SERVER_PORT", "8080"),
		DatabaseURL: envOrDefault("DATABASE_URL", "postgres://veritas:change-me@localhost:5432/veritas?sslmode=disable"),
		RedisURL:    envOrDefault("REDIS_URL", "redis://localhost:6379/0"),
		NatsURL:     envOrDefault("NATS_URL", "nats://localhost:4222"),
		JWTSecret:   envRequired("JWT_SECRET"),
		LogLevel:    envOrDefault("LOG_LEVEL", "info"),
		Environment: envOrDefault("ENVIRONMENT", "development"),

		StripeSecretKey:     os.Getenv("STRIPE_SECRET_KEY"),
		StripeWebhookSecret: os.Getenv("STRIPE_WEBHOOK_SECRET"),
		BTCPayServerURL:     os.Getenv("BTCPAY_SERVER_URL"),
		BTCPayAPIKey:        os.Getenv("BTCPAY_API_KEY"),
		BTCPayStoreID:       envOrDefault("BTCPAY_STORE_ID", "default"),
		BTCPayWebhookSecret: os.Getenv("BTCPAY_WEBHOOK_SECRET"),
		BTCPayPublicURL:     os.Getenv("BTCPAY_PUBLIC_URL"),
		BitcoinReadinessURL: os.Getenv("BITCOIN_READINESS_URL"),

		PremiumPriceUSDCents: int64EnvOrDefault("PREMIUM_PRICE_USD_CENTS", 300),
		PremiumPeriodDays:    intEnvOrDefault("PREMIUM_PERIOD_DAYS", 30),
		FirebaseProjectID:    envOrDefault("FIREBASE_PROJECT_ID", "veritasvpn-37cf6"),
		CheckoutSuccessURL:   envOrDefault("CHECKOUT_SUCCESS_URL", "http://localhost:8000/billing/success.html"),
		CheckoutCancelURL:    envOrDefault("CHECKOUT_CANCEL_URL", "http://localhost:8000/billing/cancel.html"),
		CORSOrigins:          envOrDefault("CORS_ORIGINS", "http://localhost:8000,http://127.0.0.1:8000"),
		BillingPublicURL:     envOrDefault("BILLING_PUBLIC_URL", "http://localhost:8083"),

		AgentAuthToken: envRequired("AGENT_AUTH_TOKEN"),

		ResendAPIKey:       os.Getenv("RESEND_API_KEY"),
		TurnstileSecretKey: os.Getenv("TURNSTILE_SECRET_KEY"),
		PublicBaseURL:      envOrDefault("PUBLIC_BASE_URL", "https://veritasvpn.cloud"),

		AccessTokenTTL:  durationEnvOrDefault("ACCESS_TOKEN_TTL", 1*time.Hour),
		RefreshTokenTTL: durationEnvOrDefault("REFRESH_TOKEN_TTL", 30*24*time.Hour),
	}
}

func envOrDefault(key, defaultVal string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return defaultVal
}

func envRequired(key string) string {
	return os.Getenv(key)
}

func intEnvOrDefault(key string, defaultVal int) int {
	val := os.Getenv(key)
	if val == "" {
		return defaultVal
	}
	n, err := strconv.Atoi(val)
	if err != nil {
		return defaultVal
	}
	return n
}

func int64EnvOrDefault(key string, defaultVal int64) int64 {
	val := os.Getenv(key)
	if val == "" {
		return defaultVal
	}
	n, err := strconv.ParseInt(val, 10, 64)
	if err != nil {
		return defaultVal
	}
	return n
}

func durationEnvOrDefault(key string, defaultVal time.Duration) time.Duration {
	val := os.Getenv(key)
	if val == "" {
		return defaultVal
	}
	d, err := time.ParseDuration(val)
	if err != nil {
		return defaultVal
	}
	return d
}

func (c *Config) IsProduction() bool {
	return strings.ToLower(c.Environment) == "production"
}

func (c *Config) IsDevelopment() bool {
	return strings.ToLower(c.Environment) == "development"
}

func (c *Config) ServerAddr() string {
	if strings.HasPrefix(c.ServerPort, ":") {
		return c.ServerPort
	}
	return ":" + c.ServerPort
}

func (c *Config) HTTPServerAddr() string {
	if strings.HasPrefix(c.ServerPort, ":") {
		return c.ServerPort
	}
	return ":" + c.ServerPort
}

func (c *Config) GRPCServerAddr() string {
	port, err := strconv.Atoi(strings.TrimPrefix(c.ServerPort, ":"))
	if err != nil {
		port = 8080
	}
	return ":" + strconv.Itoa(port+1000)
}

func (c *Config) AllowedCORSOrigins() []string {
	parts := strings.Split(c.CORSOrigins, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

// UseMockBTCPay returns true only when explicitly allowed for local/dev
// (ALLOW_MOCK_BTCPAY=true) and real BTCPay credentials are missing.
// Production never uses mock. Public deployments must leave ALLOW_MOCK_BTCPAY unset.
func (c *Config) UseMockBTCPay() bool {
	if c.IsProduction() {
		return false
	}
	if strings.ToLower(os.Getenv("ALLOW_MOCK_BTCPAY")) != "true" {
		return false
	}
	return c.BTCPayAPIKey == "" || c.BTCPayServerURL == "" || strings.Contains(c.BTCPayServerURL, "btcpay:49392")
}

// RequireBTCPayProduction validates that production billing has real BTCPay credentials
// and a webhook secret. Call from billing-svc main before serving.
func (c *Config) RequireBTCPayProduction() error {
	if !c.IsProduction() {
		return nil
	}
	missing := make([]string, 0, 4)
	if c.BTCPayServerURL == "" || strings.Contains(c.BTCPayServerURL, "btcpay:49392") {
		missing = append(missing, "BTCPAY_SERVER_URL")
	}
	if c.BTCPayAPIKey == "" {
		missing = append(missing, "BTCPAY_API_KEY")
	}
	if c.BTCPayStoreID == "" {
		missing = append(missing, "BTCPAY_STORE_ID")
	}
	if c.BTCPayWebhookSecret == "" {
		missing = append(missing, "BTCPAY_WEBHOOK_SECRET")
	}
	if len(missing) > 0 {
		return fmt.Errorf("production billing requires: %s", strings.Join(missing, ", "))
	}
	return nil
}
