package main

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
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
	Exp  int64  `json:"exp"`
	Sub  string `json:"sub"`
	Tier string `json:"tier"`
	Iss  string `json:"iss"`
}
type proxy struct {
	secret    []byte
	transport *http.Transport
}

func main() {
	secret := os.Getenv("JWT_SECRET")
	if len(secret) < 32 {
		log.Fatal("JWT_SECRET must contain at least 32 characters")
	}
	p := &proxy{secret: []byte(secret), transport: &http.Transport{
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
	return len(parts) == 2 && parts[0] == "veritas" && validateJWT(parts[1], p.secret)
}

func validateJWT(token string, secret []byte) bool {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return false
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(parts[0] + "." + parts[1]))
	if !hmac.Equal(sig, mac.Sum(nil)) {
		return false
	}
	body, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return false
	}
	var c claims
	if json.Unmarshal(body, &c) != nil || c.Exp <= time.Now().Unix() || c.Sub == "" || c.Tier != "premium" || c.Iss != "veritasvpn" {
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
