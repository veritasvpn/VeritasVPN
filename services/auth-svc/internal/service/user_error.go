package service

import (
	"errors"
	"fmt"
)

// UserError marks a message the caller is meant to read. Everything else this
// package returns wraps pgx, bcrypt or the email provider, and echoing those
// back to an unauthenticated endpoint describes the backend to whoever is
// probing it.
type UserError struct {
	Message string
}

func (e *UserError) Error() string { return e.Message }

// userErrorf builds a UserError with a formatted message.
func userErrorf(format string, args ...interface{}) error {
	return &UserError{Message: fmt.Sprintf(format, args...)}
}

// ClientMessage returns err's message when the service marked it safe to show,
// and fallback otherwise.
func ClientMessage(err error, fallback string) string {
	var userErr *UserError
	if errors.As(err, &userErr) {
		return userErr.Message
	}
	return fallback
}
