package repository

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/veritasvpn/services/billing-svc/internal/model"
)

type Postgres struct {
	pool *pgxpool.Pool
}

func NewPostgres(pool *pgxpool.Pool) *Postgres {
	return &Postgres{pool: pool}
}

func (p *Postgres) Ping(ctx context.Context) error {
	return p.pool.Ping(ctx)
}

func (p *Postgres) CreateSubscription(ctx context.Context, sub *model.Subscription) error {
	query := `INSERT INTO subscriptions (account_id, tier, status, payment_method,
	           current_period_start, current_period_end, cancel_at_period_end, plan_id, billing_period, price_cents, period_days, created_at, updated_at)
	           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
	           ON CONFLICT (account_id) DO UPDATE SET
	               tier = EXCLUDED.tier,
	               status = EXCLUDED.status,
	               payment_method = EXCLUDED.payment_method,
	               current_period_start = EXCLUDED.current_period_start,
	               current_period_end = EXCLUDED.current_period_end,
	               cancel_at_period_end = EXCLUDED.cancel_at_period_end,
	               plan_id = EXCLUDED.plan_id,
	               billing_period = EXCLUDED.billing_period,
	               price_cents = EXCLUDED.price_cents,
	               period_days = EXCLUDED.period_days,
	               updated_at = EXCLUDED.updated_at
	           RETURNING id, created_at, updated_at`

	now := time.Now().UTC()
	sub.CreatedAt = now
	sub.UpdatedAt = now

	return p.pool.QueryRow(ctx, query,
		sub.AccountID, sub.Tier, sub.Status, sub.PaymentMethod,
		sub.CurrentPeriodStart, sub.CurrentPeriodEnd, sub.CancelAtPeriodEnd,
		sub.PlanID, sub.BillingPeriod, sub.PriceCents, sub.PeriodDays,
		sub.CreatedAt, sub.UpdatedAt,
	).Scan(&sub.ID, &sub.CreatedAt, &sub.UpdatedAt)
}

func (p *Postgres) GetSubscription(ctx context.Context, accountID string) (*model.Subscription, error) {
	query := `SELECT id, account_id, tier, status, payment_method,
	           current_period_start, current_period_end, cancel_at_period_end,
	           plan_id, billing_period, price_cents, period_days,
	           created_at, updated_at
	           FROM subscriptions WHERE account_id = $1`

	sub := &model.Subscription{}
	err := p.pool.QueryRow(ctx, query, accountID).Scan(
		&sub.ID, &sub.AccountID, &sub.Tier, &sub.Status, &sub.PaymentMethod,
		&sub.CurrentPeriodStart, &sub.CurrentPeriodEnd, &sub.CancelAtPeriodEnd,
		&sub.PlanID, &sub.BillingPeriod, &sub.PriceCents, &sub.PeriodDays,
		&sub.CreatedAt, &sub.UpdatedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, pgx.ErrNoRows
		}
		return nil, fmt.Errorf("get subscription: %w", err)
	}
	return sub, nil
}

func (p *Postgres) UpdateSubscription(ctx context.Context, sub *model.Subscription) error {
	query := `UPDATE subscriptions SET
	           tier = $2, status = $3, payment_method = $4,
	           current_period_start = $5, current_period_end = $6,
	           cancel_at_period_end = $7, plan_id = $8, billing_period = $9,
	           price_cents = $10, period_days = $11, updated_at = NOW()
	           WHERE id = $1`

	_, err := p.pool.Exec(ctx, query,
		sub.ID, sub.Tier, sub.Status, sub.PaymentMethod,
		sub.CurrentPeriodStart, sub.CurrentPeriodEnd, sub.CancelAtPeriodEnd,
		sub.PlanID, sub.BillingPeriod, sub.PriceCents, sub.PeriodDays,
	)
	return err
}

func (p *Postgres) CancelSubscription(ctx context.Context, accountID string) error {
	query := `UPDATE subscriptions SET cancel_at_period_end = TRUE, updated_at = NOW()
	           WHERE account_id = $1`

	ct, err := p.pool.Exec(ctx, query, accountID)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		return pgx.ErrNoRows
	}
	return nil
}

func (p *Postgres) CreatePaymentRecord(ctx context.Context, pr *model.PaymentRecord) error {
	query := `INSERT INTO payment_records (subscription_id, account_id, amount, currency,
	           status, provider_transaction_id, plan_id, period_days, created_at)
	           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	           ON CONFLICT (provider_transaction_id) DO UPDATE SET
	               provider_transaction_id = EXCLUDED.provider_transaction_id
	           RETURNING id, created_at`

	pr.CreatedAt = time.Now().UTC()

	return p.pool.QueryRow(ctx, query,
		pr.SubscriptionID, pr.AccountID, pr.Amount, pr.Currency,
		pr.Status, pr.ProviderTransactionID, pr.PlanID, pr.PeriodDays, pr.CreatedAt,
	).Scan(&pr.ID, &pr.CreatedAt)
}

func (p *Postgres) GetPaymentByProviderTxn(ctx context.Context, providerTxnID string) (*model.PaymentRecord, error) {
	query := `SELECT id, subscription_id, COALESCE(account_id, ''), amount, currency, status,
	           provider_transaction_id, plan_id, period_days, created_at
	           FROM payment_records WHERE provider_transaction_id = $1`

	pr := &model.PaymentRecord{}
	err := p.pool.QueryRow(ctx, query, providerTxnID).Scan(
		&pr.ID, &pr.SubscriptionID, &pr.AccountID, &pr.Amount, &pr.Currency,
		&pr.Status, &pr.ProviderTransactionID, &pr.PlanID, &pr.PeriodDays, &pr.CreatedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, pgx.ErrNoRows
		}
		return nil, err
	}
	return pr, nil
}

func (p *Postgres) CompletePayment(ctx context.Context, providerTxnID string) error {
	query := `UPDATE payment_records SET status = $2
	           WHERE provider_transaction_id = $1 AND status = $3`

	_, err := p.pool.Exec(ctx, query, providerTxnID, model.PaymentCompleted, model.PaymentPending)
	return err
}

func (p *Postgres) ListExpiredPremium(ctx context.Context, now time.Time) ([]*model.Subscription, error) {
	query := `SELECT id, account_id, tier, status, payment_method,
	           current_period_start, current_period_end, cancel_at_period_end,
	           plan_id, billing_period, price_cents, period_days,
	           created_at, updated_at
	           FROM subscriptions
	           WHERE tier = $1 AND status = $2 AND current_period_end < $3`

	rows, err := p.pool.Query(ctx, query, model.TierPremium, model.StatusActive, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []*model.Subscription
	for rows.Next() {
		sub := &model.Subscription{}
		if err := rows.Scan(
			&sub.ID, &sub.AccountID, &sub.Tier, &sub.Status, &sub.PaymentMethod,
			&sub.CurrentPeriodStart, &sub.CurrentPeriodEnd, &sub.CancelAtPeriodEnd,
			&sub.PlanID, &sub.BillingPeriod, &sub.PriceCents, &sub.PeriodDays,
			&sub.CreatedAt, &sub.UpdatedAt,
		); err != nil {
			return nil, err
		}
		out = append(out, sub)
	}
	return out, rows.Err()
}
