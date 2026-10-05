package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type sessionTracker struct {
	mu     sync.Mutex
	counts map[string]int
}

func (s *sessionTracker) acquire(account string) (func(), bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if account == "" || s.counts[account] >= 32 {
		return nil, false
	}
	if s.counts == nil {
		s.counts = make(map[string]int)
	}
	s.counts[account]++
	var once sync.Once
	return func() {
		once.Do(func() {
			s.mu.Lock()
			defer s.mu.Unlock()
			s.counts[account]--
			if s.counts[account] == 0 {
				delete(s.counts, account)
			}
		})
	}, true
}

// Called only AFTER authorized() has verified the signature and live session.
func authorizedAccount(header string) string {
	raw, _ := base64.StdEncoding.DecodeString(strings.TrimPrefix(header, "Basic "))
	parts := strings.SplitN(string(raw), ":", 2)
	if len(parts) != 2 {
		return ""
	}
	segments := strings.Split(parts[1], ".")
	if len(segments) != 3 {
		return ""
	}
	body, _ := base64.RawURLEncoding.DecodeString(segments[1])
	var c claims
	if json.Unmarshal(body, &c) != nil {
		return ""
	}
	return c.Sub
}

type activityConn struct {
	net.Conn
	last *atomic.Int64
}

func (c activityConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 {
		c.last.Store(time.Now().UnixNano())
	}
	return n, err
}
func (c activityConn) Write(b []byte) (int, error) {
	n, err := c.Conn.Write(b)
	if n > 0 {
		c.last.Store(time.Now().UnixNano())
	}
	return n, err
}
func durationOr(value, fallback time.Duration) time.Duration {
	if value > 0 {
		return value
	}
	return fallback
}

func (p *proxy) serveTunnel(upstream, client net.Conn, buffered *bufio.Reader, header string) {
	parent := p.shutdown
	if parent == nil {
		parent = context.Background()
	}
	ctx, cancel := context.WithTimeout(parent, durationOr(p.tunnelLifetime, 30*time.Minute))
	defer cancel()
	idle := durationOr(p.tunnelIdle, 2*time.Minute)
	// Activity in either direction keeps the tunnel alive (e.g. a download).
	var last atomic.Int64
	last.Store(time.Now().UnixNano())
	up, down := activityConn{upstream, &last}, activityConn{client, &last}
	defer upstream.Close()
	defer client.Close()
	// The hijacker may already hold tunnel bytes; drain only those bytes before
	// using deadline-aware reads on the underlying connection.
	done := make(chan struct{}, 2)
	go func() {
		defer func() { done <- struct{}{} }()
		if n := buffered.Buffered(); n > 0 {
			if _, err := io.CopyN(up, buffered, int64(n)); err != nil {
				return
			}
		}
		_, _ = io.Copy(up, down)
	}()
	go func() { _, _ = io.Copy(down, up); done <- struct{}{} }()
	ticker := time.NewTicker(durationOr(p.tunnelRecheck, 30*time.Second))
	defer ticker.Stop()
	idleTimer := time.NewTimer(idle)
	defer idleTimer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-done:
			return
		case <-idleTimer.C:
			remaining := idle - time.Since(time.Unix(0, last.Load()))
			if remaining <= 0 {
				return
			}
			idleTimer.Reset(remaining)
		case <-ticker.C:
			check, stop := context.WithTimeout(ctx, 3*time.Second)
			valid := p.authorized(check, header)
			stop()
			if !valid {
				return
			}
		}
	}
}
