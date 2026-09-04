package service

import (
	"encoding/json"
	"testing"
)

func TestAccountTeardownRequestResponseJSON(t *testing.T) {
	reqRaw, err := json.Marshal(AccountTeardownRequest{AccountID: "acc_1"})
	if err != nil {
		t.Fatal(err)
	}
	var req AccountTeardownRequest
	if err := json.Unmarshal(reqRaw, &req); err != nil {
		t.Fatal(err)
	}
	if req.AccountID != "acc_1" {
		t.Fatalf("account_id: %q", req.AccountID)
	}

	respRaw, err := json.Marshal(AccountTeardownResponse{OK: true, PeersRemoved: 2})
	if err != nil {
		t.Fatal(err)
	}
	var resp AccountTeardownResponse
	if err := json.Unmarshal(respRaw, &resp); err != nil {
		t.Fatal(err)
	}
	if !resp.OK || resp.PeersRemoved != 2 {
		t.Fatalf("unexpected response: %+v", resp)
	}
}

func TestAccountTeardownSubjectStable(t *testing.T) {
	if AccountTeardownSubject != "account.teardown" {
		t.Fatalf("subject drifted: %q", AccountTeardownSubject)
	}
}
