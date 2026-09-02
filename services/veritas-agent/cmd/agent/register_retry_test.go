package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"testing"
	"time"

	"github.com/veritasvpn/lib/logging"
)

func TestIsRetryableRegisterError(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name string
		err  error
		want bool
	}{
		{name: "nil", err: nil, want: false},
		{name: "canceled", err: context.Canceled, want: false},
		{name: "deadline", err: context.DeadlineExceeded, want: false},
		{name: "wrapped canceled", err: fmt.Errorf("register: %w", context.Canceled), want: false},
		{name: "401", err: &httpStatusError{Code: 401, Body: "unauthorized"}, want: false},
		{name: "403", err: &httpStatusError{Code: 403, Body: "forbidden"}, want: false},
		{name: "400", err: &httpStatusError{Code: 400, Body: "bad"}, want: false},
		{name: "404", err: &httpStatusError{Code: 404, Body: "missing"}, want: false},
		{name: "429", err: &httpStatusError{Code: 429, Body: "slow down"}, want: true},
		{name: "502", err: &httpStatusError{Code: 502, Body: "bad gateway"}, want: true},
		{name: "503", err: &httpStatusError{Code: 503, Body: "unavailable"}, want: true},
		{name: "dial", err: &net.OpError{Op: "dial", Err: errors.New("connection refused")}, want: true},
		{name: "generic", err: errors.New("register request: connection refused"), want: true},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			if got := isRetryableRegisterError(tc.err); got != tc.want {
				t.Fatalf("isRetryableRegisterError(%v)=%v want %v", tc.err, got, tc.want)
			}
		})
	}
}

func TestNextRegisterBackoffCapsAndGrows(t *testing.T) {
	t.Parallel()

	initial := time.Second
	max := 8 * time.Second

	first := nextRegisterBackoff(1, initial, max)
	if first < time.Duration(float64(initial)*0.8) || first > time.Duration(float64(initial)*1.2) {
		t.Fatalf("attempt 1 backoff out of jitter range: %s", first)
	}

	capped := nextRegisterBackoff(20, initial, max)
	if capped > max {
		t.Fatalf("backoff exceeded max: %s", capped)
	}
	if capped < time.Duration(float64(max)*0.8) {
		t.Fatalf("expected near-max backoff, got %s", capped)
	}
}

func TestRegisterWithManagerRetrySucceedsAfterTransientFailures(t *testing.T) {
	t.Parallel()

	client := &seqRegisterClient{errs: []error{
		&net.OpError{Op: "dial", Err: errors.New("connection refused")},
		&httpStatusError{Code: 503, Body: "starting"},
		nil,
	}}
	client.resp = &RegisterServerResponse{ServerID: "srv-1", WGSubnet: "10.0.0.0/24"}

	logger, err := logging.New("error")
	if err != nil {
		t.Fatal(err)
	}
	a := &Agent{
		cfg: &AgentConfig{
			RegisterRetryInitial:     5 * time.Millisecond,
			RegisterRetryMaxInterval: 10 * time.Millisecond,
			RegisterRetryTimeout:     2 * time.Second,
		},
		logger:        logger,
		managerClient: client,
		publicKey:     "test-key",
	}

	resp, err := a.registerWithManagerRetry(context.Background())
	if err != nil {
		t.Fatalf("registerWithManagerRetry: %v", err)
	}
	if resp.ServerID != "srv-1" {
		t.Fatalf("server id=%q", resp.ServerID)
	}
	if client.calls != 3 {
		t.Fatalf("calls=%d want 3", client.calls)
	}
}

func TestRegisterWithManagerRetryFailsFastOnUnauthorized(t *testing.T) {
	t.Parallel()

	client := &seqRegisterClient{errs: []error{
		&httpStatusError{Code: 401, Body: "unauthorized"},
	}}
	logger, err := logging.New("error")
	if err != nil {
		t.Fatal(err)
	}
	a := &Agent{
		cfg: &AgentConfig{
			RegisterRetryInitial:     5 * time.Millisecond,
			RegisterRetryMaxInterval: 10 * time.Millisecond,
			RegisterRetryTimeout:     time.Second,
		},
		logger:        logger,
		managerClient: client,
		publicKey:     "test-key",
	}

	_, err = a.registerWithManagerRetry(context.Background())
	if err == nil {
		t.Fatal("expected unauthorized error")
	}
	if client.calls != 1 {
		t.Fatalf("calls=%d want 1 (no retry)", client.calls)
	}
}

type seqRegisterClient struct {
	errs  []error
	resp  *RegisterServerResponse
	calls int
}

func (c *seqRegisterClient) RegisterServer(ctx context.Context, req *RegisterServerRequest) (*RegisterServerResponse, error) {
	c.calls++
	idx := c.calls - 1
	if idx < len(c.errs) && c.errs[idx] != nil {
		return nil, c.errs[idx]
	}
	return c.resp, nil
}

func (c *seqRegisterClient) SendHeartbeat(context.Context, *HeartbeatRequest) error {
	return nil
}
func (c *seqRegisterClient) StreamPeerUpdates(context.Context, string, string) (<-chan *PeerUpdate, <-chan error) {
	return nil, nil
}
func (c *seqRegisterClient) ReportPeerApplied(context.Context, string, string, string) error {
	return nil
}
func (c *seqRegisterClient) ReportPeerExpired(context.Context, string, string, string) error {
	return nil
}
