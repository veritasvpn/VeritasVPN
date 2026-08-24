package service

import (
	"testing"

	"github.com/veritasvpn/services/wg-manager/internal/model"
)

func TestUseLANEndpoint(t *testing.T) {
	svc := &Service{lanIP: "192.168.0.6", lanPort: 51820}

	tests := []struct {
		name      string
		clientIP  string
		publicIP  string
		lanIP     string
		lanPort   int32
		want      bool
	}{
		{name: "disabled when lan unset", clientIP: "192.168.0.10", publicIP: "203.0.113.1", want: false},
		{name: "same public IP as client", clientIP: "203.0.113.1", publicIP: "203.0.113.1", want: true},
		{name: "same /24 as lan endpoint", clientIP: "192.168.0.50", publicIP: "203.0.113.1", want: true},
		{name: "different subnet", clientIP: "10.0.0.5", publicIP: "203.0.113.1", want: false},
		{name: "empty client IP", clientIP: "", publicIP: "203.0.113.1", want: false},
		{name: "invalid client IP", clientIP: "not-an-ip", publicIP: "203.0.113.1", want: false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			s := *svc
			if tt.lanIP != "" {
				s.lanIP = tt.lanIP
			}
			if tt.lanPort != 0 {
				s.lanPort = tt.lanPort
			}
			if tt.lanIP == "unset" {
				s.lanIP = ""
			}
			if tt.name == "disabled when lan unset" {
				s.lanIP = ""
			}
			if got := s.useLANEndpoint(tt.clientIP, tt.publicIP); got != tt.want {
				t.Fatalf("useLANEndpoint(%q, %q) = %v, want %v", tt.clientIP, tt.publicIP, got, tt.want)
			}
		})
	}
}

func TestClientEndpoint(t *testing.T) {
	svc := &Service{lanIP: "192.168.0.6", lanPort: 51820}
	srv := &model.Server{PublicIP: "203.0.113.1", WGPort: 51820}

	if got := svc.ClientEndpoint(nil, "192.168.0.10"); got != "" {
		t.Fatalf("nil server: got %q", got)
	}
	if got := svc.ClientEndpoint(srv, "192.168.0.10"); got != "192.168.0.6:51820" {
		t.Fatalf("LAN client: got %q", got)
	}
	if got := svc.ClientEndpoint(srv, "198.51.100.9"); got != "203.0.113.1:51820" {
		t.Fatalf("remote client: got %q", got)
	}
	if got := svc.ClientEndpoint(srv, "203.0.113.1"); got != "192.168.0.6:51820" {
		t.Fatalf("client on public IP: got %q", got)
	}
}
