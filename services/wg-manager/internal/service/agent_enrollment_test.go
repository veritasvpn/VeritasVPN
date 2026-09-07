package service

import (
	"testing"

	"github.com/veritasvpn/lib/tokenhash"
	"github.com/veritasvpn/services/wg-manager/internal/model"
)

func TestAgentTokenMatches(t *testing.T) {
	agentToken, agentHash, err := mintAgentToken()
	if err != nil {
		t.Fatalf("mintAgentToken: %v", err)
	}

	if !agentTokenMatches(agentToken, agentHash) {
		t.Fatal("the node's own token must match its stored hash")
	}

	// The bootstrap secret is shared by every agent pod. It must never satisfy
	// the per-server check, or holding it would be enough to re-register an
	// enrolled hostname and take the node over.
	bootstrapToken := "shared-bootstrap-agent-auth-token"
	if agentTokenMatches(bootstrapToken, agentHash) {
		t.Fatal("bootstrap token must not match a per-server agent token hash")
	}

	otherToken, _, err := mintAgentToken()
	if err != nil {
		t.Fatalf("mintAgentToken second: %v", err)
	}
	if agentTokenMatches(otherToken, agentHash) {
		t.Fatal("another node's token must not match this node's hash")
	}

	if agentTokenMatches("", agentHash) {
		t.Fatal("an absent token must never authorize re-registration")
	}
	// A row with no stored hash is treated as not-yet-enrolled and is handled by
	// the bootstrap path, so the comparison itself must reject it outright.
	if agentTokenMatches(agentToken, "") {
		t.Fatal("an empty stored hash must not match any token")
	}
	if agentTokenMatches("", "") {
		t.Fatal("two empty values must not compare equal")
	}
}

func TestMintAgentTokenStoresOnlyHash(t *testing.T) {
	plaintext, hash, err := mintAgentToken()
	if err != nil {
		t.Fatalf("mintAgentToken: %v", err)
	}
	if plaintext == hash {
		t.Fatal("stored value must be a hash, not the plaintext token")
	}
	if hash != tokenhash.Hash(plaintext) {
		t.Fatal("stored hash must be the hash of the returned plaintext")
	}
}

func TestApplyServerIdentityKeepsLocationWhenAgentOmitsIt(t *testing.T) {
	srv := &model.Server{
		Region:    "eu-west",
		City:      "Dublin",
		Country:   "IE",
		Status:    "offline",
		PublicIP:  "203.0.113.1",
		WGPort:    51820,
		PublicKey: "old-key",
	}

	applyServerIdentity(srv, "new-key", "198.51.100.7", 443, "", "", "")

	if srv.PublicKey != "new-key" || srv.PublicIP != "198.51.100.7" || srv.WGPort != 443 {
		t.Fatalf("identity fields not refreshed: %+v", srv)
	}
	if srv.Status != "online" {
		t.Fatalf("want status online, got %q", srv.Status)
	}
	if srv.Region != "eu-west" || srv.City != "Dublin" || srv.Country != "IE" {
		t.Fatalf("empty location fields must not clear stored values: %+v", srv)
	}

	applyServerIdentity(srv, "new-key", "198.51.100.7", 443, "us-east", "Ashburn", "US")

	if srv.Region != "us-east" || srv.City != "Ashburn" || srv.Country != "US" {
		t.Fatalf("non-empty location fields must overwrite: %+v", srv)
	}
}
