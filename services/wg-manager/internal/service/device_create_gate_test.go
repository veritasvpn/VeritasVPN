package service

import (
	"sync"
	"testing"
	"time"
)

func TestDeviceCreateGateOneAtATimePerDevice(t *testing.T) {
	var gate deviceCreateGate
	releaseFirst := gate.hold("acct", "phone")
	started := make(chan struct{})
	releaseSecond := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		unlock := gate.hold("acct", "phone")
		close(started)
		<-releaseSecond
		unlock()
	}()
	select {
	case <-started:
		t.Fatal("second create for the same device ran while the first held the gate")
	case <-time.After(50 * time.Millisecond):
	}
	other := gate.hold("acct", "tablet")
	other()
	releaseFirst()
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("second create did not start after the first released")
	}
	close(releaseSecond)
	wg.Wait()
}

func TestDeviceCreateGateBlankDeviceDoesNotBlock(t *testing.T) {
	var gate deviceCreateGate
	first := gate.hold("acct", "")
	second := gate.hold("acct", "")
	second()
	first()
}
