package tokenhash

import "testing"

func TestHashStable(t *testing.T) {
	a := Hash("test-token")
	b := Hash("test-token")
	if a != b || a == "" {
		t.Fatalf("hash=%q b=%q", a, b)
	}
	if Hash("other") == a {
		t.Fatal("expected different hashes for different inputs")
	}
}
