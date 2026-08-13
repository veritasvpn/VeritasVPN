package model

import "time"

type Account struct {
	ID                      string     `db:"id"`
	HashedDeviceID          string     `db:"hashed_device_id"`
	HashedPublicKey         string     `db:"hashed_public_key"`
	Email                   *string    `db:"email"`
	PasswordHash            *string    `db:"password_hash"`
	EmailVerifiedAt         *time.Time `db:"email_verified_at"`
	VerificationTokenHash   *string    `db:"verification_token_hash"`
	VerificationTokenExpiry *time.Time `db:"verification_token_expiry"`
	VerificationSentAt      *time.Time `db:"verification_sent_at"`
	VerificationAttempts    int        `db:"verification_attempts"`
	ResetToken              *string    `db:"reset_token"`
	ResetTokenExpiry        *time.Time `db:"reset_token_expiry"`
	CreatedAt               time.Time  `db:"created_at"`
	SubscriptionTier        string     `db:"subscription_tier"`
	SubscriptionExpiry      *time.Time `db:"subscription_expiry"`
	AccountStatus           string     `db:"account_status"`
}

type RefreshToken struct {
	ID        string    `db:"id"`
	AccountID string    `db:"account_id"`
	TokenHash string    `db:"token_hash"`
	ExpiresAt time.Time `db:"expires_at"`
	CreatedAt time.Time `db:"created_at"`
}
