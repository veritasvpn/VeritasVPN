package main

import "testing"

func TestBitcoinReadyRequiresSyncedExpectedChain(t *testing.T) {
	cases := []struct {
		name      string
		info      info
		wantChain string
		want      bool
	}{
		{name: "synced mainnet", info: info{Chain: "main", Blocks: 100, Headers: 104}, wantChain: "main", want: true},
		{name: "synced testnet", info: info{Chain: "test", Blocks: 100, Headers: 104}, wantChain: "test", want: true},
		{name: "initial download", info: info{Chain: "main", Blocks: 100, Headers: 104, InitialBlockDownload: true}, wantChain: "main", want: false},
		{name: "too far behind", info: info{Chain: "main", Blocks: 100, Headers: 107}, wantChain: "main", want: false},
		{name: "wrong chain for mainnet", info: info{Chain: "test", Blocks: 100, Headers: 100}, wantChain: "main", want: false},
		{name: "wrong chain for testnet", info: info{Chain: "main", Blocks: 100, Headers: 100}, wantChain: "test", want: false},
		{name: "headers behind", info: info{Chain: "main", Blocks: 101, Headers: 100}, wantChain: "main", want: false},
		{name: "default wantChain is main", info: info{Chain: "main", Blocks: 100, Headers: 100}, wantChain: "", want: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := bitcoinReady(tc.info, tc.wantChain); got != tc.want {
				t.Fatalf("bitcoinReady(%+v, %q) = %v, want %v", tc.info, tc.wantChain, got, tc.want)
			}
		})
	}
}

func TestExpectedChain(t *testing.T) {
	cases := map[string]string{
		"": "main", "mainnet": "main", "main": "main",
		"testnet": "test", "test": "test", "TESTNET": "test",
		"signet": "signet", "regtest": "regtest",
	}
	for in, want := range cases {
		if got := expectedChain(in); got != want {
			t.Fatalf("expectedChain(%q) = %q, want %q", in, got, want)
		}
	}
}
