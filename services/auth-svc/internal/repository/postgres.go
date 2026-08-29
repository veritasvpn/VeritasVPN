package repository

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/veritasvpn/services/auth-svc/internal/model"
)

type Postgres struct {
	pool *pgxpool.Pool
}

func NewPostgres(pool *pgxpool.Pool) *Postgres {
	return &Postgres{pool: pool}
}

func (p *Postgres) CreateAccount(ctx context.Context, acc *model.Account) error {
	query := `INSERT INTO accounts (id, hashed_device_id, hashed_public_key, email, password_hash, subscription_tier)
	           VALUES ($1, $2, $3, $4, $5, $6)
	           ON CONFLICT (hashed_device_id) DO UPDATE SET hashed_public_key = $3
	           RETURNING id, created_at, subscription_tier, subscription_expiry, account_status`

	row := p.pool.QueryRow(ctx, query, acc.ID, acc.HashedDeviceID, acc.HashedPublicKey,
		acc.Email, acc.PasswordHash, acc.SubscriptionTier)

	return row.Scan(&acc.ID, &acc.CreatedAt, &acc.SubscriptionTier,
		&acc.SubscriptionExpiry, &acc.AccountStatus)
}

func (p *Postgres) CreateAccountWithEmail(ctx context.Context, acc *model.Account) error {
	query := `INSERT INTO accounts (id, hashed_device_id, hashed_public_key, email, password_hash, subscription_tier)
	           VALUES ($1, $2, $3, $4, $5, $6)
	           ON CONFLICT (email) DO NOTHING
	           RETURNING id, created_at, subscription_tier, subscription_expiry, account_status`

	row := p.pool.QueryRow(ctx, query, acc.ID, acc.HashedDeviceID, acc.HashedPublicKey,
		acc.Email, acc.PasswordHash, acc.SubscriptionTier)

	return row.Scan(&acc.ID, &acc.CreatedAt, &acc.SubscriptionTier,
		&acc.SubscriptionExpiry, &acc.AccountStatus)
}

func (p *Postgres) CreatePendingEmailAccount(ctx context.Context, acc *model.Account) error {
	query := `INSERT INTO accounts (id, hashed_device_id, hashed_public_key, email, password_hash,
		subscription_tier, verification_token_hash, verification_token_expiry, verification_sent_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,NOW())
		RETURNING id, created_at, subscription_tier, subscription_expiry, account_status`
	return p.pool.QueryRow(ctx, query, acc.ID, acc.HashedDeviceID, acc.HashedPublicKey,
		acc.Email, acc.PasswordHash, acc.SubscriptionTier, acc.VerificationTokenHash,
		acc.VerificationTokenExpiry).Scan(&acc.ID, &acc.CreatedAt, &acc.SubscriptionTier,
		&acc.SubscriptionExpiry, &acc.AccountStatus)
}

func (p *Postgres) SetVerificationToken(ctx context.Context, accountID, tokenHash string, expiry time.Time) error {
	_, err := p.pool.Exec(ctx, `UPDATE accounts SET verification_token_hash=$2,
		verification_token_expiry=$3, verification_sent_at=NOW(),
		verification_attempts=verification_attempts+1 WHERE id=$1 AND email_verified_at IS NULL`,
		accountID, tokenHash, expiry)
	return err
}

func (p *Postgres) VerifyEmailToken(ctx context.Context, tokenHash string) (*model.Account, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	acc := &model.Account{}
	err = tx.QueryRow(ctx, `SELECT id, email FROM accounts WHERE verification_token_hash=$1
		AND verification_token_expiry > NOW() AND email_verified_at IS NULL FOR UPDATE`, tokenHash).
		Scan(&acc.ID, &acc.Email)
	if err != nil {
		return nil, fmt.Errorf("invalid or expired verification token: %w", err)
	}
	_, err = tx.Exec(ctx, `UPDATE accounts SET email_verified_at=NOW(), verification_token_hash=NULL,
		verification_token_expiry=NULL WHERE id=$1`, acc.ID)
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return acc, nil
}

func (p *Postgres) DeleteExpiredUnverifiedAccounts(ctx context.Context, olderThan time.Time) (int64, error) {
	result, err := p.pool.Exec(ctx, `DELETE FROM accounts WHERE email IS NOT NULL
		AND email_verified_at IS NULL AND created_at < $1`, olderThan)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected(), nil
}

func (p *Postgres) IsEmailVerified(ctx context.Context, accountID string) (bool, error) {
	var email *string
	var verified *time.Time
	err := p.pool.QueryRow(ctx, `SELECT email, email_verified_at FROM accounts
		WHERE id=$1 AND account_status='active'`, accountID).Scan(&email, &verified)
	if err != nil {
		return false, err
	}
	return email == nil || verified != nil, nil
}

func (p *Postgres) GetAccountByID(ctx context.Context, id string) (*model.Account, error) {
	query := `SELECT id, hashed_device_id, hashed_public_key, email, password_hash,
	           email_verified_at, verification_token_hash, verification_token_expiry,
	           verification_sent_at, verification_attempts, reset_token, reset_token_expiry, created_at,
	           subscription_tier, subscription_expiry, account_status
	           FROM accounts WHERE id = $1 AND account_status != 'deleted'`

	acc := &model.Account{}
	err := p.pool.QueryRow(ctx, query, id).Scan(
		&acc.ID, &acc.HashedDeviceID, &acc.HashedPublicKey,
		&acc.Email, &acc.PasswordHash,
		&acc.EmailVerifiedAt, &acc.VerificationTokenHash, &acc.VerificationTokenExpiry,
		&acc.VerificationSentAt, &acc.VerificationAttempts,
		&acc.ResetToken, &acc.ResetTokenExpiry,
		&acc.CreatedAt,
		&acc.SubscriptionTier, &acc.SubscriptionExpiry, &acc.AccountStatus,
	)
	if err != nil {
		return nil, fmt.Errorf("get account: %w", err)
	}
	return acc, nil
}

func (p *Postgres) GetAccountByEmail(ctx context.Context, email string) (*model.Account, error) {
	query := `SELECT id, hashed_device_id, hashed_public_key, email, password_hash,
	           email_verified_at, verification_token_hash, verification_token_expiry,
	           verification_sent_at, verification_attempts, reset_token, reset_token_expiry, created_at,
	           subscription_tier, subscription_expiry, account_status
	           FROM accounts WHERE LOWER(email) = LOWER($1) AND account_status != 'deleted'`

	acc := &model.Account{}
	err := p.pool.QueryRow(ctx, query, email).Scan(
		&acc.ID, &acc.HashedDeviceID, &acc.HashedPublicKey,
		&acc.Email, &acc.PasswordHash,
		&acc.EmailVerifiedAt, &acc.VerificationTokenHash, &acc.VerificationTokenExpiry,
		&acc.VerificationSentAt, &acc.VerificationAttempts,
		&acc.ResetToken, &acc.ResetTokenExpiry,
		&acc.CreatedAt,
		&acc.SubscriptionTier, &acc.SubscriptionExpiry, &acc.AccountStatus,
	)
	if err != nil {
		return nil, fmt.Errorf("get account by email: %w", err)
	}
	return acc, nil
}

func (p *Postgres) UpdateAccountPassword(ctx context.Context, accountID, passwordHash string) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin password update: %w", err)
	}
	defer tx.Rollback(ctx)

	// Completing a reset proves access to the mailbox that received the one-time token.
	// Treat that account as verified and clear any outstanding verification token.
	query := `UPDATE accounts SET password_hash = $2,
		email_verified_at = COALESCE(email_verified_at, NOW()),
		verification_token_hash = NULL, verification_token_expiry = NULL,
		reset_token = NULL, reset_token_expiry = NULL
		WHERE id = $1`
	result, err := tx.Exec(ctx, query, accountID, passwordHash)
	if err != nil {
		return fmt.Errorf("update password: %w", err)
	}
	if result.RowsAffected() != 1 {
		return fmt.Errorf("update password: account not found")
	}
	// A password reset is an account-recovery event. Revoke every refresh
	// token atomically so stolen sessions cannot be renewed afterward.
	if _, err := tx.Exec(ctx, `DELETE FROM refresh_tokens WHERE account_id = $1`, accountID); err != nil {
		return fmt.Errorf("revoke sessions after password reset: %w", err)
	}
	return tx.Commit(ctx)
}

func (p *Postgres) SetResetToken(ctx context.Context, accountID, token string, expiry time.Time) error {
	query := `UPDATE accounts SET reset_token = $2, reset_token_expiry = $3 WHERE id = $1`
	_, err := p.pool.Exec(ctx, query, accountID, token, expiry)
	return err
}

func (p *Postgres) GetAccountByResetToken(ctx context.Context, token string) (*model.Account, error) {
	query := `SELECT id, hashed_device_id, hashed_public_key, email, password_hash,
	           email_verified_at, verification_token_hash, verification_token_expiry,
	           verification_sent_at, verification_attempts, reset_token, reset_token_expiry, created_at,
	           subscription_tier, subscription_expiry, account_status
	           FROM accounts WHERE reset_token = $1 AND reset_token_expiry > NOW() AND account_status != 'deleted'`

	acc := &model.Account{}
	err := p.pool.QueryRow(ctx, query, token).Scan(
		&acc.ID, &acc.HashedDeviceID, &acc.HashedPublicKey,
		&acc.Email, &acc.PasswordHash,
		&acc.EmailVerifiedAt, &acc.VerificationTokenHash, &acc.VerificationTokenExpiry,
		&acc.VerificationSentAt, &acc.VerificationAttempts,
		&acc.ResetToken, &acc.ResetTokenExpiry,
		&acc.CreatedAt,
		&acc.SubscriptionTier, &acc.SubscriptionExpiry, &acc.AccountStatus,
	)
	if err != nil {
		return nil, fmt.Errorf("get account by reset token: %w", err)
	}
	return acc, nil
}

func (p *Postgres) GetAccountByDeviceID(ctx context.Context, hashedDeviceID string) (*model.Account, error) {
	query := `SELECT id, hashed_device_id, hashed_public_key, email, password_hash,
	           email_verified_at, verification_token_hash, verification_token_expiry,
	           verification_sent_at, verification_attempts, reset_token, reset_token_expiry, created_at,
	           subscription_tier, subscription_expiry, account_status
	           FROM accounts WHERE hashed_device_id = $1 AND account_status != 'deleted'`

	acc := &model.Account{}
	err := p.pool.QueryRow(ctx, query, hashedDeviceID).Scan(
		&acc.ID, &acc.HashedDeviceID, &acc.HashedPublicKey,
		&acc.Email, &acc.PasswordHash,
		&acc.EmailVerifiedAt, &acc.VerificationTokenHash, &acc.VerificationTokenExpiry,
		&acc.VerificationSentAt, &acc.VerificationAttempts,
		&acc.ResetToken, &acc.ResetTokenExpiry,
		&acc.CreatedAt,
		&acc.SubscriptionTier, &acc.SubscriptionExpiry, &acc.AccountStatus,
	)
	if err != nil {
		return nil, fmt.Errorf("get account by device: %w", err)
	}
	return acc, nil
}

func (p *Postgres) UpdateAccountTier(ctx context.Context, accountID, tier string, expiry *time.Time) error {
	query := `UPDATE accounts SET subscription_tier = $2, subscription_expiry = $3
	           WHERE id = $1`
	_, err := p.pool.Exec(ctx, query, accountID, tier, expiry)
	return err
}

func (p *Postgres) DeleteAccount(ctx context.Context, accountID string) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin account deletion: %w", err)
	}
	defer tx.Rollback(ctx)

	// Account-scoped records do not all have foreign keys to accounts because
	// they are owned by separate services. Remove them explicitly in the same
	// database transaction before deleting the account itself.
	for _, query := range []string{
		`DELETE FROM payment_records WHERE account_id = $1`,
		`DELETE FROM subscriptions WHERE account_id = $1`,
		`DELETE FROM peers WHERE account_id = $1`,
		`DELETE FROM refresh_tokens WHERE account_id = $1`,
	} {
		if _, err := tx.Exec(ctx, query, accountID); err != nil {
			return fmt.Errorf("delete account data: %w", err)
		}
	}

	result, err := tx.Exec(ctx, `DELETE FROM accounts WHERE id = $1`, accountID)
	if err != nil {
		return fmt.Errorf("delete account: %w", err)
	}
	if result.RowsAffected() != 1 {
		return fmt.Errorf("delete account: account not found")
	}

	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit account deletion: %w", err)
	}
	return nil
}

func (p *Postgres) StoreRefreshToken(ctx context.Context, token *model.RefreshToken) error {
	query := `INSERT INTO refresh_tokens (account_id, token_hash, expires_at)
	           VALUES ($1, $2, $3)`
	_, err := p.pool.Exec(ctx, query, token.AccountID, token.TokenHash, token.ExpiresAt)
	return err
}

func (p *Postgres) GetRefreshToken(ctx context.Context, tokenHash string) (*model.RefreshToken, error) {
	query := `SELECT id, account_id, token_hash, expires_at, created_at
	           FROM refresh_tokens WHERE token_hash = $1 AND expires_at > NOW()`

	token := &model.RefreshToken{}
	err := p.pool.QueryRow(ctx, query, tokenHash).Scan(
		&token.ID, &token.AccountID, &token.TokenHash, &token.ExpiresAt, &token.CreatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("get refresh token: %w", err)
	}
	return token, nil
}

func (p *Postgres) DeleteRefreshToken(ctx context.Context, tokenHash string) error {
	query := `DELETE FROM refresh_tokens WHERE token_hash = $1`
	_, err := p.pool.Exec(ctx, query, tokenHash)
	return err
}

// RotateRefreshToken consumes an existing token and inserts its replacement in
// one transaction. The DELETE must affect exactly one row, which prevents two
// concurrent replays of the same refresh token from both succeeding.
func (p *Postgres) RotateRefreshToken(ctx context.Context, oldTokenHash string, replacement *model.RefreshToken) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin refresh token rotation: %w", err)
	}
	defer tx.Rollback(ctx)

	result, err := tx.Exec(ctx, `DELETE FROM refresh_tokens WHERE token_hash = $1 AND account_id = $2 AND expires_at > NOW()`, oldTokenHash, replacement.AccountID)
	if err != nil {
		return fmt.Errorf("consume refresh token: %w", err)
	}
	if result.RowsAffected() != 1 {
		return fmt.Errorf("refresh token already used or expired")
	}
	if _, err := tx.Exec(ctx, `INSERT INTO refresh_tokens (account_id, token_hash, expires_at) VALUES ($1, $2, $3)`, replacement.AccountID, replacement.TokenHash, replacement.ExpiresAt); err != nil {
		return fmt.Errorf("store rotated refresh token: %w", err)
	}
	return tx.Commit(ctx)
}

func (p *Postgres) DeleteAllRefreshTokens(ctx context.Context, accountID string) error {
	query := `DELETE FROM refresh_tokens WHERE account_id = $1`
	_, err := p.pool.Exec(ctx, query, accountID)
	return err
}
