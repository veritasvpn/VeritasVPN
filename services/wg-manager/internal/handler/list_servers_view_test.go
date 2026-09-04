package handler

import (
	"encoding/json"
	"testing"

	"github.com/veritasvpn/services/wg-manager/internal/model"
)

func TestListServersPublicViewOmitsStealthPathPrefix(t *testing.T) {
	srv := &model.Server{
		ID:        "srv-1",
		Hostname:  "node",
		PublicIP:  "203.0.113.10",
		WGPort:    51820,
		PublicKey: "pk",
		Status:    "online",
		Region:    "sa",
		City:      "asuncion",
		Country:   "PY",
	}
	view := listServersPublicView(srv, "203.0.113.10:443", "203.0.113.10:443", true)
	raw, err := json.Marshal(view)
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]json.RawMessage
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if _, ok := decoded["stealth_path_prefix"]; ok {
		t.Fatalf("list servers must not include stealth_path_prefix: %s", raw)
	}
	if _, ok := decoded["stealth_available"]; !ok {
		t.Fatal("expected stealth_available in list view")
	}
}
