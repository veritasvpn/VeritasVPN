// phishing-checker performs bounded, passive URL risk analysis. It never
// downloads a page, follows redirects, executes JavaScript, or accepts private
// network targets. This keeps a public checker from becoming an SSRF proxy.
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	maxBodyBytes = 4096
	maxURLBytes  = 2048
)

type finding struct {
	Code     string `json:"code"`
	Severity string `json:"severity"`
	Message  string `json:"message"`
}

type response struct {
	Verdict       string    `json:"verdict"`
	Score         int       `json:"score"`
	NormalizedURL string    `json:"normalizedUrl"`
	Hostname      string    `json:"hostname"`
	ResolvedIPs   []string  `json:"resolvedIps,omitempty"`
	TLS           *tlsInfo  `json:"tls,omitempty"`
	Findings      []finding `json:"findings"`
	Notice        string    `json:"notice"`
	CheckedAt     time.Time `json:"checkedAt"`
}

type tlsInfo struct {
	Verified  bool   `json:"verified"`
	Issuer    string `json:"issuer,omitempty"`
	ExpiresAt string `json:"expiresAt,omitempty"`
}

type request struct {
	URL            string `json:"url"`
	TurnstileToken string `json:"turnstile_token"`
}

type limiter struct {
	mu      sync.Mutex
	entries map[string][]time.Time
	limit   int
	window  time.Duration
}

func (l *limiter) allow(key string) bool {
	now := time.Now()
	cutoff := now.Add(-l.window)
	l.mu.Lock()
	defer l.mu.Unlock()
	times := l.entries[key]
	i := 0
	for _, at := range times {
		if at.After(cutoff) {
			times[i] = at
			i++
		}
	}
	times = times[:i]
	if len(times) >= l.limit {
		l.entries[key] = times
		return false
	}
	l.entries[key] = append(times, now)
	return true
}

func main() {
	limit := 10
	if v, err := strconv.Atoi(strings.TrimSpace(os.Getenv("RATE_LIMIT_PER_MINUTE"))); err == nil && v > 0 && v <= 120 {
		limit = v
	}
	l := &limiter{entries: make(map[string][]time.Time), limit: limit, window: time.Minute}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", health)
	mux.HandleFunc("POST /api/v1/phishing/check", check(l, env("TURNSTILE_SECRET_KEY", ""), verifyTurnstile))
	server := &http.Server{
		Addr:              env("LISTEN_ADDR", ":8080"),
		Handler:           securityHeaders(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      12 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	log.Printf("phishing checker listening on %s", server.Addr)
	log.Fatal(server.ListenAndServe())
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func check(l *limiter, secret string, verify func(context.Context, string, string, string) error) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !sameOrigin(r) {
			writeJSON(w, http.StatusForbidden, map[string]string{"error": "Cross-origin requests are not allowed."})
			return
		}
		client := clientIP(r)
		if !l.allow(client) {
			w.Header().Set("Retry-After", "60")
			writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "Too many checks. Try again in a minute."})
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
		defer r.Body.Close()
		var input request
		if err := json.NewDecoder(r.Body).Decode(&input); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Enter one valid URL."})
			return
		}
		// Prove the caller passed Turnstile before any DNS lookup or TLS handshake.
		if err := verify(r.Context(), secret, input.TurnstileToken, client); err != nil {
			status := http.StatusForbidden
			message := "Verification failed."
			if err.Error() == "verification required" {
				message = "Complete the verification challenge, then try again."
			}
			if err.Error() == "verification unavailable" {
				status = http.StatusServiceUnavailable
				message = "Verification is unavailable. Try again later."
			}
			writeJSON(w, status, map[string]string{"error": message})
			return
		}
		result, err := analyze(r.Context(), input.URL)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func analyze(parent context.Context, raw string) (response, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > maxURLBytes {
		return response{}, errors.New("Enter an HTTP or HTTPS URL under 2,048 characters.")
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return response{}, errors.New("Enter a complete URL beginning with http:// or https://.")
	}
	if parsed.User != nil {
		return response{}, errors.New("URLs containing a username or password are not accepted.")
	}
	if parsed.Port() != "" && parsed.Port() != "80" && parsed.Port() != "443" {
		return response{}, errors.New("Only the standard web ports (80 and 443) can be checked.")
	}
	host := strings.TrimSuffix(strings.ToLower(parsed.Hostname()), ".")
	if host == "" || len(host) > 253 {
		return response{}, errors.New("Enter a valid public hostname.")
	}
	ctx, cancel := context.WithTimeout(parent, 5*time.Second)
	defer cancel()
	ips, err := resolvePublic(ctx, host)
	if err != nil {
		return response{}, err
	}
	result := response{
		NormalizedURL: parsed.String(),
		Hostname:      host,
		ResolvedIPs:   ipsToStrings(ips),
		Findings:      []finding{},
		Notice:        "This is a risk assessment, not a guarantee. VeritasVPN does not download the page, follow redirects, execute scripts, or retain the submitted URL.",
		CheckedAt:     time.Now().UTC(),
	}
	score := 0
	add := func(code, severity, message string, points int) {
		result.Findings = append(result.Findings, finding{Code: code, Severity: severity, Message: message})
		score += points
	}
	if parsed.Scheme != "https" {
		add("no_https", "warning", "The URL does not use HTTPS, so information sent to it may not be protected in transit.", 2)
	}
	if net.ParseIP(host) != nil {
		add("ip_address_host", "warning", "The URL uses an IP address instead of a recognizable domain name.", 2)
	}
	if strings.Contains(host, "xn--") {
		add("punycode", "warning", "The hostname uses Punycode. It can be legitimate, but is also used for lookalike-domain attacks.", 2)
	}
	labels := strings.Split(host, ".")
	if len(labels) >= 5 {
		add("many_subdomains", "notice", "The hostname has many subdomains, which can make the real registered domain harder to spot.", 1)
	}
	if len(parsed.String()) > 120 {
		add("long_url", "notice", "The URL is unusually long.", 1)
	}
	if strings.Count(host, "-") >= 2 {
		add("many_hyphens", "notice", "The hostname contains several hyphens, a common lookalike-domain pattern.", 1)
	}
	if strings.Contains(strings.ToLower(parsed.EscapedPath()+"?"+parsed.RawQuery), "%") {
		add("encoded_url", "notice", "The path contains encoded characters, which can hide what a link does.", 1)
	}
	keywords := []string{"login", "signin", "verify", "verification", "account", "secure", "wallet", "invoice", "support", "update", "password"}
	needle := strings.ToLower(host + parsed.EscapedPath() + "?" + parsed.RawQuery)
	hits := 0
	for _, word := range keywords {
		if strings.Contains(needle, word) {
			hits++
		}
	}
	if hits >= 2 {
		add("credential_language", "notice", "The URL contains multiple words commonly used on sign-in or payment pages.", 1)
	}
	if brand := impersonatedBrand(host); brand != "" {
		add("brand_lookalike", "danger", "The hostname mentions "+brand+" but is not an official "+brand+" domain.", 4)
	}
	if parsed.Scheme == "https" {
		result.TLS = inspectTLS(ctx, host, parsed.Port(), ips)
		if !result.TLS.Verified {
			add("tls_unverified", "warning", "The server’s HTTPS certificate could not be verified.", 2)
		} else if expiry, err := time.Parse(time.RFC3339, result.TLS.ExpiresAt); err == nil && time.Until(expiry) < 30*24*time.Hour {
			add("tls_expiring", "notice", "The HTTPS certificate expires within 30 days.", 1)
		}
	}
	result.Score = score
	switch {
	case score >= 5:
		result.Verdict = "high_risk"
	case score >= 2:
		result.Verdict = "suspicious"
	default:
		result.Verdict = "no_obvious_signals"
	}
	return result, nil
}

func resolvePublic(ctx context.Context, host string) ([]net.IP, error) {
	if ip := net.ParseIP(host); ip != nil {
		if !isPublicIP(ip) {
			return nil, errors.New("Private, local, and reserved network addresses cannot be checked.")
		}
		return []net.IP{ip}, nil
	}
	ips, err := net.DefaultResolver.LookupIP(ctx, "ip", host)
	if err != nil || len(ips) == 0 {
		return nil, errors.New("The hostname could not be resolved.")
	}
	for _, ip := range ips {
		if !isPublicIP(ip) {
			return nil, errors.New("The hostname resolves to a private, local, or reserved address and cannot be checked.")
		}
	}
	return ips, nil
}

func isPublicIP(ip net.IP) bool {
	addr, ok := netip.AddrFromSlice(ip)
	addr = addr.Unmap()
	if !ok || !addr.IsValid() || addr.IsLoopback() || addr.IsPrivate() || addr.IsLinkLocalUnicast() || addr.IsLinkLocalMulticast() || addr.IsMulticast() || addr.IsUnspecified() {
		return false
	}
	// Block CGNAT, documentation, benchmarking, and unique-local ranges too.
	for _, prefix := range []netip.Prefix{
		netip.MustParsePrefix("100.64.0.0/10"), netip.MustParsePrefix("192.0.0.0/24"),
		netip.MustParsePrefix("192.0.2.0/24"), netip.MustParsePrefix("198.18.0.0/15"),
		netip.MustParsePrefix("198.51.100.0/24"), netip.MustParsePrefix("203.0.113.0/24"),
		netip.MustParsePrefix("2001:db8::/32"), netip.MustParsePrefix("fc00::/7"),
	} {
		if prefix.Contains(addr) {
			return false
		}
	}
	return true
}

func inspectTLS(ctx context.Context, host, port string, ips []net.IP) *tlsInfo {
	if port == "" {
		port = "443"
	}
	result := &tlsInfo{}
	for _, ip := range ips {
		dialer := &net.Dialer{Timeout: 3 * time.Second}
		attemptCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
		rawConnection, err := dialer.DialContext(attemptCtx, "tcp", net.JoinHostPort(ip.String(), port))
		if err != nil {
			cancel()
			continue
		}
		connection := tls.Client(rawConnection, &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12})
		_ = connection.SetDeadline(time.Now().Add(4 * time.Second))
		err = connection.HandshakeContext(attemptCtx)
		cancel()
		state := connection.ConnectionState()
		_ = connection.Close()
		if len(state.PeerCertificates) == 0 {
			continue
		}
		cert := state.PeerCertificates[0]
		result.Verified = true
		result.Issuer = cert.Issuer.CommonName
		result.ExpiresAt = cert.NotAfter.UTC().Format(time.RFC3339)
		return result
	}
	return result
}

func impersonatedBrand(host string) string {
	brands := map[string][]string{
		"Google":    {"google.com"},
		"Microsoft": {"microsoft.com", "live.com", "office.com"},
		"Apple":     {"apple.com", "icloud.com"},
		"PayPal":    {"paypal.com"},
		"Amazon":    {"amazon.com", "amazon.co.uk", "amazon.de"},
		"Meta":      {"facebook.com", "instagram.com", "whatsapp.com"},
		"Binance":   {"binance.com"},
		"Coinbase":  {"coinbase.com"},
	}
	lower := strings.ToLower(host)
	for brand, domains := range brands {
		needle := strings.ToLower(brand)
		if !strings.Contains(lower, needle) {
			continue
		}
		for _, domain := range domains {
			if lower == domain || strings.HasSuffix(lower, "."+domain) {
				return ""
			}
		}
		return brand
	}
	return ""
}

func ipsToStrings(ips []net.IP) []string {
	values := make([]string, 0, len(ips))
	for _, ip := range ips {
		values = append(values, ip.String())
	}
	sort.Strings(values)
	return values
}

func verifyTurnstile(ctx context.Context, secret, token, remoteIP string) error {
	secret = strings.TrimSpace(secret)
	if secret == "" {
		return errors.New("verification unavailable")
	}
	token = strings.TrimSpace(token)
	if token == "" {
		return errors.New("verification required")
	}
	form := url.Values{}
	form.Set("secret", secret)
	form.Set("response", token)
	if net.ParseIP(remoteIP) != nil {
		form.Set("remoteip", remoteIP)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://challenges.cloudflare.com/turnstile/v0/siteverify", strings.NewReader(form.Encode()))
	if err != nil {
		return errors.New("verification unavailable")
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	client := &http.Client{Timeout: 4 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return errors.New("verification unavailable")
	}
	defer resp.Body.Close()
	var result struct {
		Success bool `json:"success"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&result); err != nil || !result.Success {
		return errors.New("verification failed")
	}
	return nil
}

func clientIP(r *http.Request) string {
	if value := strings.TrimSpace(r.Header.Get("X-Scanner-Client-IP")); net.ParseIP(value) != nil {
		return value
	}
	return "shared"
}

func sameOrigin(r *http.Request) bool {
	// A missing Origin is not the website. Browsers send Origin on this POST;
	// accepting the empty value let any client on the internet use the checker.
	origin := strings.TrimSpace(r.Header.Get("Origin"))
	return origin == "https://veritasvpn.cloud" || origin == "https://www.veritasvpn.cloud"
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(value); err != nil {
		log.Printf("write response: %v", err)
	}
}
