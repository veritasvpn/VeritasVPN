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
	ready   atomic.Bool
	chain   atomic.Value
	blocks  atomic.Int64
	headers atomic.Int64
	initial atomic.Bool
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
	body, _ := json.Marshal(map[string]any{"jsonrpc": "1.0", "id": "readiness", "method": "getblockchaininfo", "params": []any{}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, rpcURL, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.SetBasicAuth(user, password)
	req.Header.Set("Content-Type", "application/json")
	resp, err := (&http.Client{Timeout: 4 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode >= 300 {
		return fmt.Errorf("bitcoin rpc returned %s", resp.Status)
	}
	var envelope struct {
		Result info `json:"result"`
		Error  any  `json:"error"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return err
	}
	if envelope.Error != nil {
		return fmt.Errorf("bitcoin rpc error: %v", envelope.Error)
	}
	s.chain.Store(envelope.Result.Chain)
	s.blocks.Store(envelope.Result.Blocks)
	s.headers.Store(envelope.Result.Headers)
	s.initial.Store(envelope.Result.InitialBlockDownload)
	ready := envelope.Result.Chain == "main" && !envelope.Result.InitialBlockDownload && envelope.Result.Headers-envelope.Result.Blocks <= 6
	s.ready.Store(ready)
	return nil
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
		fmt.Fprintf(w, "bitcoin_readiness %d\\nbitcoin_initial_block_download %d\\nbitcoin_blocks %d\\nbitcoin_headers %d\\nbitcoin_chain{chain=%q} 1\\n", ready, boolInt(s.initial.Load()), s.blocks.Load(), s.headers.Load(), chain)
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
