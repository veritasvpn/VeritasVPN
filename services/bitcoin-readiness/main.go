package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

type state struct {
	ready                 atomic.Bool
	rpcUp                 atomic.Bool
	kubernetesDNSUp       atomic.Bool
	chain                 atomic.Value
	blocks                atomic.Int64
	headers               atomic.Int64
	initial               atomic.Bool
	lastRefresh           atomic.Int64
	lastSuccessfulRefresh atomic.Int64
}

type info struct {
	Chain                string `json:"chain"`
	Blocks               int64  `json:"blocks"`
	Headers              int64  `json:"headers"`
	InitialBlockDownload bool   `json:"initialblockdownload"`
}

func env(name, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(name)); v != "" {
		return v
	}
	return fallback
}

// expectedChain maps BTCPAY_NETWORK / BITCOIN_NETWORK to bitcoind chain names.
func expectedChain(network string) string {
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "", "mainnet", "main":
		return "main"
	case "testnet", "test":
		return "test"
	case "signet":
		return "signet"
	case "regtest":
		return "regtest"
	default:
		return strings.ToLower(strings.TrimSpace(network))
	}
}

func (s *state) refresh(ctx context.Context, rpcURL, user, password string) error {
	s.lastRefresh.Store(time.Now().Unix())
	// Bitcoin RPC uses the injected Service IP. Check CoreDNS independently so
	// a DNS outage is visible without making payment readiness depend on it.
	s.checkKubernetesDNS(ctx)
	body, _ := json.Marshal(map[string]any{"jsonrpc": "1.0", "id": "readiness", "method": "getblockchaininfo", "params": []any{}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, rpcURL, bytes.NewReader(body))
	if err != nil {
		s.rpcUp.Store(false)
		return err
	}
	req.SetBasicAuth(user, password)
	req.Header.Set("Content-Type", "application/json")
	resp, err := (&http.Client{Timeout: 15 * time.Second}).Do(req)
	if err != nil {
		s.rpcUp.Store(false)
		return err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		s.rpcUp.Store(false)
		return err
	}
	if resp.StatusCode >= 300 {
		s.rpcUp.Store(false)
		return fmt.Errorf("bitcoin rpc returned %s", resp.Status)
	}
	var envelope struct {
		Result info `json:"result"`
		Error  any  `json:"error"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		s.rpcUp.Store(false)
		return err
	}
	if envelope.Error != nil {
		s.rpcUp.Store(false)
		return fmt.Errorf("bitcoin rpc error: %v", envelope.Error)
	}
	s.rpcUp.Store(true)
	s.lastSuccessfulRefresh.Store(time.Now().Unix())
	s.chain.Store(envelope.Result.Chain)
	s.blocks.Store(envelope.Result.Blocks)
	s.headers.Store(envelope.Result.Headers)
	s.initial.Store(envelope.Result.InitialBlockDownload)
	return nil
}

func (s *state) checkKubernetesDNS(ctx context.Context) {
	server := env("KUBERNETES_DNS_ADDR", "10.43.0.10:53")
	name := env("KUBERNETES_DNS_NAME", "kubernetes.default.svc.cluster.local")
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			dialer := net.Dialer{Timeout: 3 * time.Second}
			return dialer.DialContext(ctx, network, server)
		},
	}
	lookupCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	_, err := resolver.LookupHost(lookupCtx, name)
	s.kubernetesDNSUp.Store(err == nil)
}

func (s *state) refreshWithRetry(rpcURL, user, password string) error {
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		err := s.refresh(ctx, rpcURL, user, password)
		cancel()
		if err == nil {
			return nil
		}
		lastErr = err
		if attempt < 2 {
			time.Sleep(time.Duration(attempt+1) * 2 * time.Second)
		}
	}
	return lastErr
}

func bitcoinReady(i info, wantChain string) bool {
	if wantChain == "" {
		wantChain = "main"
	}
	return i.Chain == wantChain && !i.InitialBlockDownload && i.Headers >= i.Blocks && i.Headers-i.Blocks <= 6
}

func (s *state) evaluateReady(wantChain string) {
	chain, _ := s.chain.Load().(string)
	ready := bitcoinReady(info{
		Chain:                chain,
		Blocks:               s.blocks.Load(),
		Headers:              s.headers.Load(),
		InitialBlockDownload: s.initial.Load(),
	}, wantChain)
	s.ready.Store(ready)
}

func main() {
	rpcURL := env("BTC_RPC_URL", "http://bitcoind.btcpay.svc.cluster.local:8332")
	user := required("BTC_RPC_USER")
	password := required("BTC_RPC_PASSWORD")
	wantChain := expectedChain(env("BITCOIN_NETWORK", env("BTCPAY_NETWORK", "mainnet")))
	var s state
	if err := s.refreshWithRetry(rpcURL, user, password); err != nil {
		log.Printf("initial bitcoin refresh failed: %v", err)
	} else {
		s.evaluateReady(wantChain)
	}
	go func() {
		t := time.NewTicker(15 * time.Second)
		defer t.Stop()
		for range t.C {
			if err := s.refreshWithRetry(rpcURL, user, password); err != nil {
				s.ready.Store(false)
				log.Printf("bitcoin refresh failed after retries: %v", err)
				continue
			}
			s.evaluateReady(wantChain)
		}
	}()
	http.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok\n"))
	})
	http.HandleFunc("/readyz", func(w http.ResponseWriter, _ *http.Request) {
		if !s.ready.Load() {
			w.Header().Set("Retry-After", "60")
			http.Error(w, "bitcoin node is not synchronized", http.StatusServiceUnavailable)
			return
		}
		_, _ = w.Write([]byte("ready\n"))
	})
	http.HandleFunc("/metrics", func(w http.ResponseWriter, _ *http.Request) {
		ready := 0
		if s.ready.Load() {
			ready = 1
		}
		chain, _ := s.chain.Load().(string)
		blocks := s.blocks.Load()
		headers := s.headers.Load()
		syncPercent := 0.0
		if headers > 0 {
			syncPercent = 100 * float64(blocks) / float64(headers)
		}
		fmt.Fprintf(w, `bitcoin_readiness %d
bitcoin_rpc_up %d
kubernetes_dns_up %d
bitcoin_initial_block_download %d
bitcoin_blocks %d
bitcoin_headers %d
bitcoin_header_lag %d
bitcoin_sync_percent %.4f
bitcoin_last_refresh_timestamp_seconds %d
bitcoin_last_successful_refresh_timestamp_seconds %d
bitcoin_chain{chain=%q} 1
`, ready, boolInt(s.rpcUp.Load()), boolInt(s.kubernetesDNSUp.Load()), boolInt(s.initial.Load()), blocks, headers, headers-blocks, syncPercent, s.lastRefresh.Load(), s.lastSuccessfulRefresh.Load(), chain)
	})
	addr := env("LISTEN_ADDR", ":8080")
	log.Printf("bitcoin readiness listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, nil))
}

func required(name string) string {
	v := strings.TrimSpace(os.Getenv(name))
	if v == "" {
		log.Fatalf("%s is required", name)
	}
	return v
}
func boolInt(v bool) int {
	if v {
		return 1
	}
	return 0
}
