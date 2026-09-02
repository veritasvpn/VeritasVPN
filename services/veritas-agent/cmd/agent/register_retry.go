package main

import (
	"context"
	"errors"
	"fmt"
	"math"
	"math/rand"
	"time"
)

// httpStatusError is returned for non-OK HTTP responses from wg-manager.
type httpStatusError struct {
	Code int
	Body string
}

func (e *httpStatusError) Error() string {
	return fmt.Sprintf("register returned %d: %s", e.Code, e.Body)
}

// isRetryableRegisterError reports whether registration should be retried.
// Auth/client errors (most 4xx) are permanent; transport failures and 5xx/429 are transient.
func isRetryableRegisterError(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return false
	}
	var st *httpStatusError
	if errors.As(err, &st) {
		if st.Code == 429 || st.Code >= 500 {
			return true
		}
		return false
	}
	return true
}

// nextRegisterBackoff returns the sleep before the next attempt (1-based attempt
// after a failure), capped at max, with +/-20% jitter to avoid thundering herds.
func nextRegisterBackoff(attempt int, initial, max time.Duration) time.Duration {
	if initial <= 0 {
		initial = time.Second
	}
	if max < initial {
		max = initial
	}
	if attempt < 1 {
		attempt = 1
	}
	// 2^(attempt-1) * initial
	mult := math.Pow(2, float64(attempt-1))
	raw := float64(initial) * mult
	if raw > float64(max) || math.IsInf(raw, 1) {
		raw = float64(max)
	}
	// jitter in [0.8, 1.2]
	jitter := 0.8 + rand.Float64()*0.4
	d := time.Duration(raw * jitter)
	if d < initial {
		d = initial
	}
	if d > max {
		d = max
	}
	return d
}

func (a *Agent) registerWithManagerRetry(ctx context.Context) (*RegisterServerResponse, error) {
	initial := a.cfg.RegisterRetryInitial
	maxInterval := a.cfg.RegisterRetryMaxInterval
	if initial <= 0 {
		initial = time.Second
	}
	if maxInterval <= 0 {
		maxInterval = 15 * time.Second
	}

	retryCtx := ctx
	cancel := func() {}
	if a.cfg.RegisterRetryTimeout > 0 {
		retryCtx, cancel = context.WithTimeout(ctx, a.cfg.RegisterRetryTimeout)
	}
	defer cancel()

	var lastErr error
	for attempt := 1; ; attempt++ {
		resp, err := a.registerWithManager(retryCtx)
		if err == nil {
			if attempt > 1 {
				a.logger.Info("Registered with wg-manager after retries",
					"server_id", resp.ServerID,
					"attempts", attempt)
			}
			return resp, nil
		}
		lastErr = err
		if !isRetryableRegisterError(err) {
			return nil, err
		}
		if retryCtx.Err() != nil {
			return nil, fmt.Errorf("register with manager aborted: %w (last error: %v)", retryCtx.Err(), lastErr)
		}

		wait := nextRegisterBackoff(attempt, initial, maxInterval)
		a.logger.Warn("wg-manager registration failed; retrying",
			"attempt", attempt,
			"retry_in", wait.String(),
			"error", err.Error())

		timer := time.NewTimer(wait)
		select {
		case <-retryCtx.Done():
			timer.Stop()
			return nil, fmt.Errorf("register with manager aborted: %w (last error: %v)", retryCtx.Err(), lastErr)
		case <-timer.C:
		}
	}
}
