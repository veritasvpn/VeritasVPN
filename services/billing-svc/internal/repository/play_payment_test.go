package repository

import (
	"errors"
	"fmt"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
)

func TestPlayPaymentUniqueConflictDoesNotMatchOtherSQLErrors(t *testing.T) {
	conflict := fmt.Errorf("update google play payment: %w", &pgconn.PgError{Code: "23505"})
	if !IsPlayPaymentUniqueConflict(conflict) {
		t.Fatal("duplicate provider_transaction_id was not recognized")
	}
	fk := fmt.Errorf("update google play payment: %w", &pgconn.PgError{Code: "23503"})
	if IsPlayPaymentUniqueConflict(fk) {
		t.Fatal("foreign key was treated as a duplicate order id")
	}
	if IsPlayPaymentUniqueConflict(errors.New("database unavailable")) {
		t.Fatal("plain error was treated as a duplicate order id")
	}
}
