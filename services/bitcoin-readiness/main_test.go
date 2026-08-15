package main

import "testing"

func TestBitcoinReadyRequiresSyncedMainnet(t *testing.T) {
	cases := []struct {
		name string
		info info
		want bool
	}{
		{name: "synced mainnet", info: info{Chain: "main", Blocks: 100, Headers: 104}, want: true},
		{name: "initial download", info: info{Chain: "main", Blocks: 100, Headers: 104, InitialBlockDownload: true}, want: false},
		{name: "too far behind", info: info{Chain: "main", Blocks: 100, Headers: 107}, want: false},
		{name: "wrong chain", info: info{Chain: "test", Blocks: 100, Headers: 100}, want: false},
		{name: "headers behind", info: info{Chain: "main", Blocks: 101, Headers: 100}, want: false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := bitcoinReady(tc.info); got != tc.want {
				t.Fatalf("bitcoinReady(%+v) = %v, want %v", tc.info, got, tc.want)
			}
		})
	}
}
