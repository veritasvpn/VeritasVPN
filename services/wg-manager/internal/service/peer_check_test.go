package service

import "testing"

func TestPeerCheckHTTPStatus(t *testing.T) {
	cases := []struct {
		name   string
		found  bool
		status string
		failed bool
		want   int
	}{
		{name: "missing", found: false, want: PeerCheckNotFound},
		{name: "active", found: true, status: "active", want: PeerCheckOK},
		{name: "pending", found: true, status: "pending", want: PeerCheckOK},
		{name: "removed", found: true, status: "removed", want: PeerCheckGone},
		{name: "expired", found: true, status: "expired", want: PeerCheckGone},
		{name: "failed row", found: true, status: "failed", want: PeerCheckGone},
		{name: "blank", found: true, status: "", want: PeerCheckGone},
		{name: "database error", found: false, failed: true, want: PeerCheckFailed},
		{name: "database error beats a status", found: true, status: "removed", failed: true, want: PeerCheckFailed},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := PeerCheckHTTPStatus(tc.found, tc.status, tc.failed)
			if got != tc.want {
				t.Fatalf("status %q found=%v failed=%v: got %d want %d", tc.status, tc.found, tc.failed, got, tc.want)
			}
		})
	}
}
