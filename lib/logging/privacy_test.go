package logging

import (
	"strings"
	"testing"
)

func TestRedactURLRemovesPassword(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"nats", "nats://veritas:s3cr3t@nats:4222", "nats://veritas@nats:4222"},
		{"redis with no username", "redis://:s3cr3t@redis:6379/0", "redis://redis:6379/0"},
		{"postgres with query", "postgres://veritas:s3cr3t@postgres:5432/veritas?sslmode=prefer", "postgres://veritas@postgres:5432/veritas?sslmode=prefer"},
		{"no credentials", "nats://nats:4222", "nats://nats:4222"},
		{"empty", "", ""},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := RedactURL(tc.in)
			if got != tc.want {
				t.Fatalf("RedactURL(%q) = %q, want %q", tc.in, got, tc.want)
			}
			if strings.Contains(got, "s3cr3t") {
				t.Fatalf("password survived redaction: %q", got)
			}
		})
	}
}

func TestRedactURLDoesNotEchoUnparseableInput(t *testing.T) {
	got := RedactURL("nats://veritas:s3cr3t@nats:4222/\x7f\x00")
	if strings.Contains(got, "s3cr3t") {
		t.Fatalf("password survived redaction of unparseable input: %q", got)
	}
}
