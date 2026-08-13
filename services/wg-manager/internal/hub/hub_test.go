package hub

import (
	"testing"
	"time"

	"github.com/veritasvpn/lib/logging"
)

func TestPublishRequiresSubscriber(t *testing.T) {
	log, err := logging.New("error")
	if err != nil {
		t.Fatal(err)
	}
	h := New(log)
	if h.Publish("srv-1", PeerUpdate{Action: "ADD", PeerID: "p1"}) {
		t.Fatal("expected false with no subscribers")
	}

	ch, unsub := h.Subscribe("srv-1")
	defer unsub()

	if !h.Publish("srv-1", PeerUpdate{Action: "ADD", PeerID: "p1"}) {
		t.Fatal("expected true with a subscriber")
	}

	select {
	case u := <-ch:
		if u.PeerID != "p1" {
			t.Fatalf("peer_id=%s", u.PeerID)
		}
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for update")
	}
}
