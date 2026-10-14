package service

import "sync"

// deviceCreateGate lets one CreatePeer run per account device. A second
// re-provision waits until the first has finished, including a failure
// path that deletes the row, so it cannot remove the peer the other
// request just created.
type deviceCreateGate struct {
	mu    sync.Mutex
	slots map[string]*sync.Mutex
}

func (g *deviceCreateGate) hold(accountID, deviceID string) func() {
	if accountID == "" || deviceID == "" {
		return func() {}
	}
	key := accountID + "\x00" + deviceID
	g.mu.Lock()
	if g.slots == nil {
		g.slots = make(map[string]*sync.Mutex)
	}
	slot := g.slots[key]
	if slot == nil {
		slot = &sync.Mutex{}
		g.slots[key] = slot
	}
	g.mu.Unlock()
	slot.Lock()
	return func() { slot.Unlock() }
}
