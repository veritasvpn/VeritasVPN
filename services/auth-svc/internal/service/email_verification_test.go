package service

import "testing"

func TestNormalizeEmail(t *testing.T) {
	tests := []struct {
		name, input, want string
		ok                bool
	}{
		{"normalizes case and spaces", "  User@Example.COM  ", "user@example.com", true},
		{"rejects malformed", "not-an-email", "", false},
		{"rejects display name", "User <user@example.com>", "", false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, err := normalizeEmail(test.input)
			if (err == nil) != test.ok {
				t.Fatalf("normalizeEmail error = %v, want ok %v", err, test.ok)
			}
			if got != test.want {
				t.Fatalf("normalizeEmail = %q, want %q", got, test.want)
			}
		})
	}
}

func TestValidatePassword(t *testing.T) {
	tests := []struct {
		name     string
		password string
		valid    bool
	}{
		{"accepts all requirements", "SecurePass1", true},
		{"accepts unicode character classes", "ContraseñaÜ9", true},
		{"rejects short password", "Short1Aa", false},
		{"rejects missing uppercase", "lowercase1", false},
		{"rejects missing lowercase", "UPPERCASE1", false},
		{"rejects missing number", "NoNumbersHere", false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := validatePassword(test.password)
			if (err == nil) != test.valid {
				t.Fatalf("validatePassword(%q) error = %v, want valid %v", test.password, err, test.valid)
			}
		})
	}
}
