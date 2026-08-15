package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

type state struct {
	ready                 atomic.Bool
	rpcUp                 atomic.Bool
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

func (s *state) refresh(ctx context.Context, rpcURL, user, password string) error {
	s.lastRefresh.Store(time.Now().Unix())
	body, _ := json.Marshal(map[string]any{"jsonrpc": "1.0", "id": "readiness", "method": "getblockchaininfo", "params": []any{}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, rpcURL, bytes.NewReader(body))
	if err != nil {
		s.rpcUp.Store(false)
		return err
	}
	req.SetBasicAuth(user, password)
	req.Header.Set("Content-Type", "application/json")
	resp, err := (&http.Client{Timeout: 4 * time.Second}).Do(req)
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
	ready := bitcoinReady(envelope.Result)
	s.ready.Store(ready)
	return nil
}

func bitcoinReady(i info) bool {
	return i.Chain == "main" && !i.InitialBlockDownload && i.Headers >= i.Blocks && i.Headers-i.Blocks <= 6
}

func main() {
	rpcURL := env("BTC_RPC_URL", "http://bitcoind.btcpay.svc.cluster.local:8332")
	user := required("BTC_RPC_USER")
	password := required("BTC_RPC_PASSWORD")
	var s state
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	_ = s.refresh(ctx, rpcURL, user, password)
	cancel()
	go func() {
		t := time.NewTicker(15 * time.Second)
		defer t.Stop()
		for range t.C {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			if err := s.refresh(ctx, rpcURL, user, password); err != nil {
				s.ready.Store(false)
				log.Printf("bitcoin refresh failed: %v", err)
			}
			cancel()
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
		fmt.Fprintf(w, "bitcoin_readiness %d\\nbitcoin_rpc_up %d\\nbitcoin_initial_block_download %d\\nbitcoin_blocks %d\\nbitcoin_headers %d\\nbitcoin_sync_percent %.4f\\nbitcoin_last_refresh_timestamp_seconds %d\\nbitcoin_last_successful_refresh_timestamp_seconds %d\\nbitcoin_chain{chain=%q} 1\\n", ready, boolInt(s.rpcUp.Load()), boolInt(s.initial.Load()), blocks, headers, syncPercent, s.lastRefresh.Load(), s.lastSuccessfulRefresh.Load(), chain)
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
