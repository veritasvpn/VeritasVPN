package handler

import (
	"encoding/json"
	"testing"
)

func TestGetPeerResponseOmitsStealthPathPrefix(t *testing.T) {
	// Mirrors GET /api/v1/wg/peers/{id} fields when a server is attached.
	resp := map[string]interface{}{
		"peer": map[string]interface{}{"id": "p1"},
		"server": map[string]interface{}{
			"id": "s1",
		},
		"server_endpoint":   "1.2.3.4:51820",
		"stealth_endpoint":  "1.2.3.4:443",
		"stealth_available": true,
	}
	raw, err := json.Marshal(resp)
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]interface{}
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if _, ok := decoded["stealth_path_prefix"]; ok {
		t.Fatalf("GET peer must not include stealth_path_prefix: %s", raw)
	}
}
