package handler

import "testing"

func TestClassifySignInAttempts(t *testing.T) {
	tests := []struct {
		name                           string
		ipAttempts, identityAttempts   int64
		rateLimited, turnstileRequired bool
	}{
		{
			name:       "shared network activity does not challenge a first identity attempt",
			ipAttempts: 4, identityAttempts: 1,
		},
		{
			name:       "repeated attempts for one identity require turnstile",
			ipAttempts: 4, identityAttempts: 4,
			turnstileRequired: true,
		},
		{
			name:       "high volume network activity is rate limited",
			ipAttempts: 11, identityAttempts: 1,
			rateLimited: true,
		},
		{
			name:       "high volume identity activity is rate limited",
			ipAttempts: 1, identityAttempts: 11,
			rateLimited: true,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			rateLimited, turnstileRequired := classifySignInAttempts(test.ipAttempts, test.identityAttempts)
			if rateLimited != test.rateLimited || turnstileRequired != test.turnstileRequired {
				t.Fatalf("classifySignInAttempts(%d, %d) = (%t, %t), want (%t, %t)",
					test.ipAttempts, test.identityAttempts, rateLimited, turnstileRequired, test.rateLimited, test.turnstileRequired)
			}
		})
	}
}
