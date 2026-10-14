package service

// Peer-check HTTP statuses. The Android app treats 404 and 410 as "peer gone"
// and re-provisions only then. Anything else must not look like a deleted peer.
const (
	PeerCheckOK       = 200
	PeerCheckNotFound = 404
	PeerCheckGone     = 410
	PeerCheckFailed   = 500
)

// PeerCheckHTTPStatus maps a peer row to the peer-check status.
// found is false when the account has no such row. failed is a database
// error, which is not "gone". pending and active are the only live rows.
// removed (deleted or stale-expired) and every other status are gone.
func PeerCheckHTTPStatus(found bool, status string, failed bool) int {
	if failed {
		return PeerCheckFailed
	}
	if !found {
		return PeerCheckNotFound
	}
	switch status {
	case "pending", "active":
		return PeerCheckOK
	default:
		return PeerCheckGone
	}
}
