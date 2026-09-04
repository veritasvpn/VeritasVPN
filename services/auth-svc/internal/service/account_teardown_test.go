package service

import (
	"encoding/json"
	"testing"
)

func TestAccountTeardownWireFormat(t *testing.T) {
	if accountTeardownSubject != "account.teardown" {
		t.Fatalf("subject must match wg-manager: %q", accountTeardownSubject)
	}

	raw, err := json.Marshal(accountTeardownRequest{AccountID: "acc_delete"})
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]string
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded["account_id"] != "acc_delete" {
		t.Fatalf("expected account_id field, got %v", decoded)
	}

	respRaw := []byte(`{"ok":false,"error":"teardown incomplete (0/1 peers removed): no agent","peers_removed":0}`)
	var resp accountTeardownResponse
	if err := json.Unmarshal(respRaw, &resp); err != nil {
		t.Fatal(err)
	}
	if resp.OK || resp.Error == "" {
		t.Fatalf("expected failure payload, got %+v", resp)
	}
}
