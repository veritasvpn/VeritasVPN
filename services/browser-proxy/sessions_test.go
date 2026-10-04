package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestSessionAdmission(t *testing.T) {
	var tracker sessionTracker
	releases := []func(){}
	for i := 0; i < 32; i++ {
		release, ok := tracker.acquire("account")
		if !ok {
			t.Fatal("early limit")
		}
		releases = append(releases, release)
	}
	if _, ok := tracker.acquire("account"); ok {
		t.Fatal("limit bypassed")
	}
	other, ok := tracker.acquire("other")
	if !ok {
		t.Fatal("other account blocked")
	}
	other()
	for _, release := range releases {
		release()
		release()
	}
	if len(tracker.counts) != 0 {
		t.Fatal("session slots leaked")
	}
}

func TestTunnelTermination(t *testing.T) {
	for _, reason := range []string{"revoked", "idle", "lifetime", "shutdown", "auth-outage"} {
		t.Run(reason, func(t *testing.T) {
			var valid atomic.Bool
			valid.Store(true)
			auth := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if !valid.Load() {
					w.WriteHeader(http.StatusUnauthorized)
					return
				}
				w.Write([]byte(`{"valid":true,"tier":"premium"}`))
			}))
			defer auth.Close()
			secret := []byte("test-secret-for-proxy-session-lifecycle")
			token := hs256Token(t, secret, time.Now().Add(time.Hour).Unix(), "account", "premium")
			header := "Basic " + base64.StdEncoding.EncodeToString([]byte("veritas:"+token))
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			p := &proxy{secret: secret, validateURL: auth.URL, authClient: auth.Client(), shutdown: ctx, tunnelRecheck: 10 * time.Millisecond, tunnelIdle: time.Second, tunnelLifetime: time.Second}
			if reason == "idle" {
				p.tunnelIdle = 30 * time.Millisecond
			}
			if reason == "lifetime" {
				p.tunnelLifetime = 30 * time.Millisecond
			}
			up, peer := net.Pipe()
			defer peer.Close()
			down, client := net.Pipe()
			defer client.Close()
			done := make(chan struct{})
			go func() { p.serveTunnel(up, down, bufio.NewReader(down), header); close(done) }()
			if reason == "revoked" {
				valid.Store(false)
			}
			if reason == "auth-outage" {
				auth.Close()
			}
			if reason == "shutdown" {
				cancel()
			}
			select {
			case <-done:
			case <-time.After(750 * time.Millisecond):
				t.Fatal("tunnel remained open")
			}
			_ = client.SetReadDeadline(time.Now().Add(time.Second))
			if _, err := client.Read(make([]byte, 1)); err == nil {
				t.Fatal("client not closed")
			}
		})
	}
}
