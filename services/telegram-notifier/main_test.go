package main

import (
	"testing"
)

func TestFormatMessage(t *testing.T) {
	tests := []struct {
		name     string
		subject  string
		event    map[string]interface{}
		expected string
	}{
		{
			name:    "account registered with type",
			subject: "account.registered",
			event: map[string]interface{}{
				"account_type": "google",
			},
			expected: "New google account registered",
		},
		{
			name:    "account registered without type",
			subject: "account.registered",
			event:   map[string]interface{}{},
			expected: "New unknown account registered",
		},
		{
			name:    "trial started",
			subject: "subscription.renewed",
			event: map[string]interface{}{
				"payment_method": "Google Play",
				"trial":          true,
			},
			expected: "Free trial started (Google Play); subscription activated",
		},
		{
			name:    "trial converted",
			subject: "subscription.renewed",
			event: map[string]interface{}{
				"payment_method": "Google Play",
				"converted":      true,
			},
			expected: "Trial converted to paid (Google Play); subscription activated",
		},
		{
			name:    "premium payment",
			subject: "subscription.renewed",
			event: map[string]interface{}{
				"payment_method": "Bitcoin",
			},
			expected: "Premium payment received (Bitcoin); subscription activated",
		},
		{
			name:    "premium payment default method",
			subject: "subscription.renewed",
			event:   map[string]interface{}{},
			expected: "Premium payment received (Bitcoin); subscription activated",
		},
		{
			name:     "unknown subject",
			subject:  "unknown.subject",
			event:    map[string]interface{}{},
			expected: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := formatMessage(tt.subject, tt.event)
			if result != tt.expected {
				t.Errorf("formatMessage(%q, %v) = %q, want %q", tt.subject, tt.event, result, tt.expected)
			}
		})
	}
}
