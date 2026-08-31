package main

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

type claims struct {
	Exp      int64           `json:"exp"`
	Sub      string          `json:"sub"`
	Tier     string          `json:"tier"`
	Iss      string          `json:"iss"`
	Aud      json.RawMessage `json:"aud"`
	TokenUse string          `json:"token_use"`
}
type proxy struct {
	secret     []byte
	publicKeys map[string]ed25519.PublicKey
	issuer     string
	audience   string
	transport  *http.Transport
}

func main() {
	secret := os.Getenv("JWT_SECRET") // optional legacy HS256; omit in production
	publicKeys, err := parsePublicKeys(os.Getenv("JWT_ED25519_PUBLIC_KEYS"))
	if err != nil {
		log.Fatalf("invalid JWT_ED25519_PUBLIC_KEYS: %v", err)
	}
	if len(publicKeys) == 0 {
		log.Fatal("JWT_ED25519_PUBLIC_KEYS must be configured")
	}
	issuer := strings.TrimSpace(os.Getenv("JWT_ISSUER"))
	if issuer == "" {
		issuer = "https://api.veritasvpn.cloud"
	}
	audience := strings.TrimSpace(os.Getenv("JWT_AUDIENCE"))
	if audience == "" {
		audience = "veritasvpn-api"
	}
	p := &proxy{secret: []byte(secret), publicKeys: publicKeys, issuer: issuer, audience: audience, transport: &http.Transport{
		Proxy:               nil,
		DialContext:         dialPublic,
		ForceAttemptHTTP2:   false,
		IdleConnTimeout:     60 * time.Second,
		TLSHandshakeTimeout: 10 * time.Second,
	}}
	server := &http.Server{Addr: ":1080", Handler: p, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 90 * time.Second}
	log.Printf("authenticated browser proxy listening on %s", server.Addr)
	log.Fatal(server.ListenAndServe())
}

func (p *proxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !p.authorized(r.Header.Get("Proxy-Authorization")) {
		w.Header().Set("Proxy-Authenticate", `Basic realm="VeritasVPN"`)
		http.Error(w, "proxy authentication required", http.StatusProxyAuthRequired)
		return
	}
	r.Header.Del("Proxy-Authorization")
	if r.Method == http.MethodConnect {
		p.connect(w, r)
		return
	}
	p.forward(w, r)
}

func (p *proxy) authorized(header string) bool {
	if !strings.HasPrefix(header, "Basic ") {
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(header, "Basic "))
	if err != nil {
		return false
	}
	parts := strings.SplitN(string(raw), ":", 2)
	return len(parts) == 2 && parts[0] == "veritas" && validateJWT(parts[1], p.secret, p.publicKeys, p.issuer, p.audience)
}

func parsePublicKeys(value string) (map[string]ed25519.PublicKey, error) {
	keys := make(map[string]ed25519.PublicKey)
	if strings.TrimSpace(value) == "" {
		return keys, nil
	}
	var encoded map[string]string
	if err := json.Unmarshal([]byte(value), &encoded); err != nil {
		return nil, err
	}
	for kid, publicPEM := range encoded {
		block, _ := pem.Decode([]byte(publicPEM))
		if block == nil {
			return nil, errors.New("invalid public key PEM")
		}
		parsed, err := x509.ParsePKIXPublicKey(block.Bytes)
		if err != nil {
			return nil, err
		}
		key, ok := parsed.(ed25519.PublicKey)
		if !ok {
			return nil, errors.New("public key is not Ed25519")
		}
		keys[kid] = key
	}
	return keys, nil
}

func audienceContains(raw json.RawMessage, expected string) bool {
	var one string
	if json.Unmarshal(raw, &one) == nil {
		return one == expected
	}
	var many []string
	if json.Unmarshal(raw, &many) != nil {
		return false
	}
	for _, value := range many {
		if value == expected {
			return true
		}
	}
	return false
}

func validateJWT(token string, secret []byte, publicKeys map[string]ed25519.PublicKey, issuer, audience string) bool {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return false
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return false
	}
	var header struct {
		Alg string `json:"alg"`
		Kid string `json:"kid"`
	}
	headerBody, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil || json.Unmarshal(headerBody, &header) != nil {
		return false
	}
	signingInput := []byte(parts[0] + "." + parts[1])
	legacyHMAC := false
	switch header.Alg {
	case "EdDSA":
		key, ok := publicKeys[header.Kid]
		if !ok || !ed25519.Verify(key, signingInput, sig) {
			return false
		}
	case "HS256":
		if len(secret) < 32 {
			return false
		}
		mac := hmac.New(sha256.New, secret)
		_, _ = mac.Write(signingInput)
		if !hmac.Equal(sig, mac.Sum(nil)) {
			return false
		}
		legacyHMAC = true
	default:
		return false
	}
	body, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return false
	}
	var c claims
	if json.Unmarshal(body, &c) != nil || c.Exp <= time.Now().Unix() || c.Sub == "" || c.Tier != "premium" {
		return false
	}
	if legacyHMAC {
		return c.Iss == "veritasvpn" || c.Iss == issuer
	}
	if c.Iss != issuer || c.TokenUse != "access" || !audienceContains(c.Aud, audience) {
		return false
	}
	return true
}

func publicAddresses(ctx context.Context, host string) ([]net.IP, error) {
	if ip := net.ParseIP(host); ip != nil {
		if ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || ip.IsMulticast() {
			return nil, errors.New("private target is not allowed")
		}
		return []net.IP{ip}, nil
	}
	ips, err := net.DefaultResolver.LookupIP(ctx, "ip", host)
	if err != nil || len(ips) == 0 {
		return nil, errors.New("target resolution failed")
	}
	for _, ip := range ips {
		if ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || ip.IsMulticast() {
			return nil, errors.New("private target is not allowed")
		}
	}
	return ips, nil
}

func allowedTarget(authority string) (string, error) {
	host, port, err := net.SplitHostPort(authority)
	if err != nil {
		return "", errors.New("target must include a port")
	}
	if port != "80" && port != "443" {
		return "", errors.New("target port is not allowed")
	}
	if _, err := publicAddresses(context.Background(), host); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, port), nil
}

// dialPublic resolves and validates the destination immediately before every
// connection. This prevents DNS rebinding between validation and dialing.
func dialPublic(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil || (port != "80" && port != "443") {
		return nil, errors.New("target port is not allowed")
	}
	ips, err := publicAddresses(ctx, host)
	if err != nil {
		return nil, err
	}
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	var lastErr error
	for _, ip := range ips {
		conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
		if err == nil {
			return conn, nil
		}
		lastErr = err
	}
	return nil, lastErr
}
func (p *proxy) connect(w http.ResponseWriter, r *http.Request) {
	target, err := allowedTarget(r.Host)
	if err != nil {
		http.Error(w, err.Error(), http.StatusForbidden)
		return
	}
	upstream, err := dialPublic(r.Context(), "tcp", target)
	if err != nil {
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
		return
	}
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		upstream.Close()
		http.Error(w, "tunneling unavailable", http.StatusInternalServerError)
		return
	}
	client, rw, err := hijacker.Hijack()
	if err != nil {
		upstream.Close()
		return
	}
	_, _ = rw.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n")
	_ = rw.Flush()
	go relay(upstream, client, rw.Reader)
	go relay(client, upstream, nil)
}

func relay(dst net.Conn, src net.Conn, buffered *bufio.Reader) {
	defer dst.Close()
	defer src.Close()
	if buffered != nil {
		_, _ = io.Copy(dst, buffered)
	} else {
		_, _ = io.Copy(dst, src)
	}
}

func (p *proxy) forward(w http.ResponseWriter, r *http.Request) {
	if r.URL == nil || r.URL.Host == "" {
		http.Error(w, "absolute URL required", http.StatusBadRequest)
		return
	}
	authority := r.URL.Host
	if !strings.Contains(authority, ":") {
		authority = net.JoinHostPort(authority, defaultPort(r.URL))
	}
	if _, err := allowedTarget(authority); err != nil {
		http.Error(w, err.Error(), http.StatusForbidden)
		return
	}
	out := r.Clone(r.Context())
	out.RequestURI = ""
	out.Header.Del("Proxy-Connection")
	res, err := p.transport.RoundTrip(out)
	if err != nil {
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
		return
	}
	defer res.Body.Close()
	for k, values := range res.Header {
		for _, value := range values {
			w.Header().Add(k, value)
		}
	}
	w.WriteHeader(res.StatusCode)
	_, _ = io.Copy(w, res.Body)
}
func defaultPort(u *url.URL) string {
	if u.Scheme == "https" {
		return "443"
	}
	return "80"
}
