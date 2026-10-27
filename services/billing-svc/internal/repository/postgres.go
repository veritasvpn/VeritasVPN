package repository

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
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
	           current_period_start, current_period_end, cancel_at_period_end, plan_id, billing_period, price_cents, period_days, external_ref, created_at, updated_at)
	           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
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
		sub.PlanID, sub.BillingPeriod, sub.PriceCents, sub.PeriodDays, sub.ExternalRef,
		sub.CreatedAt, sub.UpdatedAt,
	).Scan(&sub.ID, &sub.CreatedAt, &sub.UpdatedAt)
}

func (p *Postgres) GetSubscription(ctx context.Context, accountID string) (*model.Subscription, error) {
	query := `SELECT id, account_id, tier, status, payment_method,
	           current_period_start, current_period_end, cancel_at_period_end,
	           plan_id, billing_period, price_cents, period_days,
	           created_at, updated_at, external_ref, COALESCE(access_state, ''), pause_resumes_at
	           FROM subscriptions WHERE account_id = $1`

	sub := &model.Subscription{}
	err := p.pool.QueryRow(ctx, query, accountID).Scan(
		&sub.ID, &sub.AccountID, &sub.Tier, &sub.Status, &sub.PaymentMethod,
		&sub.CurrentPeriodStart, &sub.CurrentPeriodEnd, &sub.CancelAtPeriodEnd,
		&sub.PlanID, &sub.BillingPeriod, &sub.PriceCents, &sub.PeriodDays,
		&sub.CreatedAt, &sub.UpdatedAt, &sub.ExternalRef, &sub.AccessState, &sub.PauseResumesAt,
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
	           price_cents = $10, period_days = $11, external_ref = $12, updated_at = NOW()
	           WHERE id = $1`

	_, err := p.pool.Exec(ctx, query,
		sub.ID, sub.Tier, sub.Status, sub.PaymentMethod,
		sub.CurrentPeriodStart, sub.CurrentPeriodEnd, sub.CancelAtPeriodEnd,
		sub.PlanID, sub.BillingPeriod, sub.PriceCents, sub.PeriodDays, sub.ExternalRef,
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
	           status, provider_transaction_id, plan_id, period_days, created_at, provider, is_trial)
	           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
	           ON CONFLICT (provider_transaction_id) DO UPDATE SET
	               provider_transaction_id = EXCLUDED.provider_transaction_id
	           RETURNING id, created_at`

	pr.CreatedAt = time.Now().UTC()
	providerName := pr.Provider
	if providerName == "" {
		providerName = model.PaymentBTCPay
	}

	return p.pool.QueryRow(ctx, query,
		pr.SubscriptionID, pr.AccountID, pr.Amount, pr.Currency,
		pr.Status, pr.ProviderTransactionID, pr.PlanID, pr.PeriodDays, pr.CreatedAt, providerName, pr.IsTrial,
	).Scan(&pr.ID, &pr.CreatedAt)
}

func (p *Postgres) GetPaymentByProviderTxn(ctx context.Context, providerTxnID string) (*model.PaymentRecord, error) {
	query := `SELECT id, subscription_id, COALESCE(account_id, ''), amount, currency, status,
	           provider_transaction_id, plan_id, period_days, created_at, COALESCE(is_trial, false)
	           FROM payment_records WHERE provider_transaction_id = $1`

	pr := &model.PaymentRecord{}
	err := p.pool.QueryRow(ctx, query, providerTxnID).Scan(
		&pr.ID, &pr.SubscriptionID, &pr.AccountID, &pr.Amount, &pr.Currency,
		&pr.Status, &pr.ProviderTransactionID, &pr.PlanID, &pr.PeriodDays, &pr.CreatedAt, &pr.IsTrial,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, pgx.ErrNoRows
		}
		return nil, err
	}
	return pr, nil
}

// GetLatestPendingPayment returns at most one outstanding checkout for an
// account. A pending payment is the only time status reconciliation may call
// BTCPay, so normal plan reads remain local and fast.
func (p *Postgres) GetLatestPendingPayment(ctx context.Context, accountID string) (*model.PaymentRecord, error) {
	query := `SELECT id, subscription_id, COALESCE(account_id, ''), amount, currency, status,
	          provider_transaction_id, plan_id, period_days, created_at, COALESCE(is_trial, false)
	          FROM payment_records
	          WHERE account_id = $1 AND status = $2
	          ORDER BY created_at DESC LIMIT 1`
	pr := &model.PaymentRecord{}
	err := p.pool.QueryRow(ctx, query, accountID, model.PaymentPending).Scan(
		&pr.ID, &pr.SubscriptionID, &pr.AccountID, &pr.Amount, &pr.Currency,
		&pr.Status, &pr.ProviderTransactionID, &pr.PlanID, &pr.PeriodDays, &pr.CreatedAt, &pr.IsTrial,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, pgx.ErrNoRows
	}
	if err != nil {
		return nil, fmt.Errorf("get latest pending payment: %w", err)
	}
	return pr, nil
}

const purchaseHistoryLimit = 100

// ListAccountPayments returns Bitcoin payments already stored for an account,
// newest first. It does not select invoice or transaction identifiers.
func (p *Postgres) ListAccountPayments(ctx context.Context, accountID string) ([]model.PaymentRecord, error) {
	query := `SELECT pr.amount, pr.currency, pr.status, COALESCE(pr.plan_id, ''), pr.period_days, pr.created_at,
	                 COALESCE(pr.provider, 'btcpay'), COALESCE(pr.is_trial, false)
	          FROM payment_records pr
	          WHERE pr.account_id = $1
	             OR (
	               COALESCE(pr.account_id, '') = ''
	               AND pr.subscription_id IN (SELECT id FROM subscriptions WHERE account_id = $1)
	             )
	          ORDER BY pr.created_at DESC
	          LIMIT $2`
	rows, err := p.pool.Query(ctx, query, accountID, purchaseHistoryLimit)
	if err != nil {
		return nil, fmt.Errorf("list account payments: %w", err)
	}
	defer rows.Close()

	out := make([]model.PaymentRecord, 0)
	for rows.Next() {
		var pr model.PaymentRecord
		if err := rows.Scan(&pr.Amount, &pr.Currency, &pr.Status, &pr.PlanID, &pr.PeriodDays, &pr.CreatedAt, &pr.Provider, &pr.IsTrial); err != nil {
			return nil, fmt.Errorf("scan account payment: %w", err)
		}
		out = append(out, pr)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("list account payments: %w", err)
	}
	return out, nil
}

// FailStalePendingPayments marks still-pending Bitcoin payments created before
// cutoff as failed. Completed, refunded, and already-failed rows are left
// unchanged. It does not read or write subscriptions.
func (p *Postgres) FailStalePendingPayments(ctx context.Context, cutoff time.Time) (int64, error) {
	query := `UPDATE payment_records
	          SET status = $1
	          WHERE status = $2 AND created_at < $3
	            AND COALESCE(provider, 'btcpay') <> 'google_play'`
	ct, err := p.pool.Exec(ctx, query, model.PaymentFailed, model.PaymentPending, cutoff.UTC())
	if err != nil {
		return 0, fmt.Errorf("fail stale pending payments: %w", err)
	}
	return ct.RowsAffected(), nil
}

// FailStalePendingPaymentsForAccount is the account-scoped form used when
// loading billing status, so the payments that account can see are updated
// immediately. Attribution matches ListAccountPayments.
func (p *Postgres) FailStalePendingPaymentsForAccount(ctx context.Context, accountID string, cutoff time.Time) (int64, error) {
	query := `UPDATE payment_records AS pr
	          SET status = $1
	          WHERE pr.status = $2
	            AND pr.created_at < $3
	            AND COALESCE(pr.provider, 'btcpay') <> 'google_play'
	            AND (
	              pr.account_id = $4
	              OR (
	                COALESCE(pr.account_id, '') = ''
	                AND pr.subscription_id IN (SELECT id FROM subscriptions WHERE account_id = $4)
	              )
	            )`
	ct, err := p.pool.Exec(ctx, query, model.PaymentFailed, model.PaymentPending, cutoff.UTC(), accountID)
	if err != nil {
		return 0, fmt.Errorf("fail stale pending payments for account: %w", err)
	}
	return ct.RowsAffected(), nil
}

func (p *Postgres) CompletePayment(ctx context.Context, providerTxnID string) error {
	query := `UPDATE payment_records SET status = $2
	           WHERE provider_transaction_id = $1 AND status = $3`

	_, err := p.pool.Exec(ctx, query, providerTxnID, model.PaymentCompleted, model.PaymentPending)
	return err
}

// SettlePaymentTx locks the payment row and subscription row, applies updates,
// and marks the payment completed in a single transaction. Concurrent webhook
// and reconcile calls for the same invoice serialize on the payment row.
func (p *Postgres) SettlePaymentTx(
	ctx context.Context,
	invoiceID string,
	apply func(pr *model.PaymentRecord, sub *model.Subscription) error,
) (alreadyCompleted bool, err error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)

	pr, err := getPaymentForUpdate(ctx, tx, invoiceID)
	if err != nil {
		return false, err
	}
	if pr.Status == model.PaymentCompleted {
		if err := tx.Commit(ctx); err != nil {
			return false, err
		}
		return true, nil
	}

	sub, err := getSubscriptionForUpdate(ctx, tx, pr.AccountID)
	if err != nil {
		return false, err
	}

	if err := apply(pr, sub); err != nil {
		return false, err
	}

	updateQuery := `UPDATE subscriptions SET
	           tier = $2, status = $3, payment_method = $4,
	           current_period_start = $5, current_period_end = $6,
	           cancel_at_period_end = $7, plan_id = $8, billing_period = $9,
	           price_cents = $10, period_days = $11, external_ref = $12, updated_at = NOW()
	           WHERE id = $1`
	if _, err := tx.Exec(ctx, updateQuery,
		sub.ID, sub.Tier, sub.Status, sub.PaymentMethod,
		sub.CurrentPeriodStart, sub.CurrentPeriodEnd, sub.CancelAtPeriodEnd,
		sub.PlanID, sub.BillingPeriod, sub.PriceCents, sub.PeriodDays, sub.ExternalRef,
	); err != nil {
		return false, fmt.Errorf("update subscription: %w", err)
	}

	completeQuery := `UPDATE payment_records SET status = $2
	                  WHERE provider_transaction_id = $1 AND status = $3`
	ct, err := tx.Exec(ctx, completeQuery, invoiceID, model.PaymentCompleted, model.PaymentPending)
	if err != nil {
		return false, fmt.Errorf("complete payment: %w", err)
	}
	if ct.RowsAffected() == 0 {
		return false, fmt.Errorf("payment not pending for invoice %s", invoiceID)
	}

	if err := tx.Commit(ctx); err != nil {
		return false, err
	}
	return false, nil
}

func getPaymentForUpdate(ctx context.Context, tx pgx.Tx, providerTxnID string) (*model.PaymentRecord, error) {
	query := `SELECT id, subscription_id, COALESCE(account_id, ''), amount, currency, status,
	           provider_transaction_id, plan_id, period_days, created_at
	           FROM payment_records WHERE provider_transaction_id = $1 FOR UPDATE`

	pr := &model.PaymentRecord{}
	err := tx.QueryRow(ctx, query, providerTxnID).Scan(
		&pr.ID, &pr.SubscriptionID, &pr.AccountID, &pr.Amount, &pr.Currency,
		&pr.Status, &pr.ProviderTransactionID, &pr.PlanID, &pr.PeriodDays, &pr.CreatedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, pgx.ErrNoRows
		}
		return nil, fmt.Errorf("lock payment: %w", err)
	}
	return pr, nil
}

func getSubscriptionForUpdate(ctx context.Context, tx pgx.Tx, accountID string) (*model.Subscription, error) {
	query := `SELECT id, account_id, tier, status, payment_method,
	           current_period_start, current_period_end, cancel_at_period_end,
	           plan_id, billing_period, price_cents, period_days,
	           created_at, updated_at, external_ref, COALESCE(access_state, ''), pause_resumes_at
	           FROM subscriptions WHERE account_id = $1 FOR UPDATE`

	sub := &model.Subscription{}
	err := tx.QueryRow(ctx, query, accountID).Scan(
		&sub.ID, &sub.AccountID, &sub.Tier, &sub.Status, &sub.PaymentMethod,
		&sub.CurrentPeriodStart, &sub.CurrentPeriodEnd, &sub.CancelAtPeriodEnd,
		&sub.PlanID, &sub.BillingPeriod, &sub.PriceCents, &sub.PeriodDays,
		&sub.CreatedAt, &sub.UpdatedAt, &sub.ExternalRef, &sub.AccessState, &sub.PauseResumesAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, pgx.ErrNoRows
		}
		return nil, fmt.Errorf("lock subscription: %w", err)
	}
	return sub, nil
}

func (p *Postgres) ListExpiredPremium(ctx context.Context, now time.Time) ([]*model.Subscription, error) {
	query := `SELECT id, account_id, tier, status, payment_method,
	           current_period_start, current_period_end, cancel_at_period_end,
	           plan_id, billing_period, price_cents, period_days,
	           created_at, updated_at, external_ref
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
			&sub.CreatedAt, &sub.UpdatedAt, &sub.ExternalRef,
		); err != nil {
			return nil, err
		}
		out = append(out, sub)
	}
	return out, rows.Err()
}

// ErrPurchaseAccountConflict means a Google Play purchase token is already
// stored for a different Veritas account.
var ErrPurchaseAccountConflict = errors.New("purchase token is already linked to another account")

// PaymentAccount returns the account linked to a Play purchase token, or empty
// when that token has not been recorded. Newer rows key payments by Google
// order id and keep the token on purchase_token. Older rows used the token as
// provider_transaction_id. The subscription external_ref is the same token
// while the Play subscription is the active method.
func (p *Postgres) PaymentAccount(ctx context.Context, purchaseToken string) (string, error) {
	query := `SELECT account_id FROM (
	            SELECT COALESCE(account_id, '') AS account_id, created_at
	            FROM payment_records
	            WHERE provider_transaction_id = $1
	               OR (purchase_token <> '' AND purchase_token = $1)
	            UNION ALL
	            SELECT account_id, updated_at
	            FROM subscriptions
	            WHERE external_ref <> '' AND external_ref = $1
	          ) linked
	          WHERE account_id <> ''
	          ORDER BY created_at ASC
	          LIMIT 1`
	var accountID string
	err := p.pool.QueryRow(ctx, query, purchaseToken).Scan(&accountID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return accountID, nil
}

// UpdateGooglePlay locks the account subscription and every payment row for
// this purchase token, then lets apply decide the entitlement. Each Google
// order id is its own payment_records row. A second call for the same order
// updates that row and does not insert another.
func (p *Postgres) UpdateGooglePlay(
	ctx context.Context,
	accountID, token string,
	apply func(sub *model.Subscription, linked []model.PaymentRecord) (*model.PaymentRecord, string, error),
) (string, error) {
	if err := p.ensureSubscriptionRow(ctx, accountID); err != nil {
		return "", err
	}
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)

	sub, err := getSubscriptionForUpdate(ctx, tx, accountID)
	if err != nil {
		return "", err
	}
	linked, err := listPlayPaymentsForUpdate(ctx, tx, token)
	if err != nil {
		return "", err
	}
	for _, row := range linked {
		if row.AccountID != "" && row.AccountID != accountID {
			return "", ErrPurchaseAccountConflict
		}
	}

	payment, event, err := apply(sub, linked)
	if err != nil {
		return "", err
	}
	if payment == nil || strings.TrimSpace(payment.ProviderTransactionID) == "" {
		return "", fmt.Errorf("google play payment was not recorded")
	}
	payment.Provider = model.PaymentGooglePlay
	payment.AccountID = accountID
	payment.SubscriptionID = sub.ID
	if strings.TrimSpace(payment.PurchaseToken) == "" {
		payment.PurchaseToken = token
	}

	updateQuery := `UPDATE subscriptions SET
	           tier = $2, status = $3, payment_method = $4,
	           current_period_start = $5, current_period_end = $6,
	           cancel_at_period_end = $7, plan_id = $8, billing_period = $9,
	           price_cents = $10, period_days = $11, external_ref = $12,
	           access_state = $13, pause_resumes_at = $14, updated_at = NOW()
	           WHERE id = $1`
	if _, err := tx.Exec(ctx, updateQuery,
		sub.ID, sub.Tier, sub.Status, sub.PaymentMethod,
		sub.CurrentPeriodStart, sub.CurrentPeriodEnd, sub.CancelAtPeriodEnd,
		sub.PlanID, sub.BillingPeriod, sub.PriceCents, sub.PeriodDays, sub.ExternalRef,
		sub.AccessState, sub.PauseResumesAt,
	); err != nil {
		return "", fmt.Errorf("update google play subscription: %w", err)
	}

	if err := upsertPlayPayment(ctx, tx, payment); err != nil {
		return "", err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", err
	}
	return event, nil
}

func listPlayPaymentsForUpdate(ctx context.Context, tx pgx.Tx, token string) ([]model.PaymentRecord, error) {
	query := `SELECT id, subscription_id, COALESCE(account_id, ''), amount, currency, status,
	           provider_transaction_id, plan_id, period_days, created_at, COALESCE(is_trial, false),
	           COALESCE(purchase_token, '')
	           FROM payment_records
	           WHERE provider_transaction_id = $1
	              OR (purchase_token <> '' AND purchase_token = $1)
	           FOR UPDATE`
	rows, err := tx.Query(ctx, query, token)
	if err != nil {
		return nil, fmt.Errorf("lock google play payments: %w", err)
	}
	defer rows.Close()
	var out []model.PaymentRecord
	for rows.Next() {
		var pr model.PaymentRecord
		if err := rows.Scan(
			&pr.ID, &pr.SubscriptionID, &pr.AccountID, &pr.Amount, &pr.Currency,
			&pr.Status, &pr.ProviderTransactionID, &pr.PlanID, &pr.PeriodDays, &pr.CreatedAt,
			&pr.IsTrial, &pr.PurchaseToken,
		); err != nil {
			return nil, fmt.Errorf("scan google play payment: %w", err)
		}
		out = append(out, pr)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("lock google play payments: %w", err)
	}
	return out, nil
}

// IsPlayPaymentUniqueConflict is a duplicate payment_records.provider_transaction_id
// (SQLSTATE 23505). Callers acknowledge the notification instead of returning
// a retryable 502. The PostgreSQL error text can contain the stored key, so
// it is not logged.
func IsPlayPaymentUniqueConflict(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}

func upsertPlayPayment(ctx context.Context, tx pgx.Tx, payment *model.PaymentRecord) error {
	if strings.TrimSpace(payment.ID) != "" {
		if _, err := tx.Exec(ctx, "SAVEPOINT play_payment_retarget"); err != nil {
			return fmt.Errorf("play payment savepoint: %w", err)
		}
		query := `UPDATE payment_records SET
		           provider_transaction_id = $2,
		           amount = $3,
		           currency = $4,
		           status = $5,
		           plan_id = $6,
		           period_days = $7,
		           provider = $8,
		           is_trial = $9,
		           purchase_token = $10,
		           subscription_id = $11,
		           account_id = $12
		           WHERE id = $1`
		ct, err := tx.Exec(ctx, query,
			payment.ID, payment.ProviderTransactionID, payment.Amount, payment.Currency,
			payment.Status, payment.PlanID, payment.PeriodDays, model.PaymentGooglePlay,
			payment.IsTrial, payment.PurchaseToken, payment.SubscriptionID, payment.AccountID,
		)
		if err != nil {
			if _, rbErr := tx.Exec(ctx, "ROLLBACK TO SAVEPOINT play_payment_retarget"); rbErr != nil {
				return fmt.Errorf("play payment savepoint rollback: %w", rbErr)
			}
			if !IsPlayPaymentUniqueConflict(err) {
				return fmt.Errorf("update google play payment: %w", err)
			}
			// The order id is already on another row. Update that row below.
		} else {
			if _, relErr := tx.Exec(ctx, "RELEASE SAVEPOINT play_payment_retarget"); relErr != nil {
				return fmt.Errorf("play payment savepoint release: %w", relErr)
			}
			if ct.RowsAffected() == 1 {
				return nil
			}
		}
	}
	return insertPlayPayment(ctx, tx, payment)
}

func insertPlayPayment(ctx context.Context, tx pgx.Tx, payment *model.PaymentRecord) error {
	createdAt := time.Now().UTC()
	if !payment.CreatedAt.IsZero() {
		createdAt = payment.CreatedAt
	}
	upsert := `INSERT INTO payment_records (subscription_id, account_id, amount, currency,
	           status, provider_transaction_id, plan_id, period_days, created_at, provider, is_trial, purchase_token)
	           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
	           ON CONFLICT (provider_transaction_id) DO UPDATE SET
	               status = CASE WHEN payment_records.status = 'refunded' THEN payment_records.status ELSE EXCLUDED.status END,
	               amount = CASE WHEN payment_records.status = 'refunded' THEN payment_records.amount ELSE EXCLUDED.amount END,
	               currency = CASE WHEN payment_records.status = 'refunded' THEN payment_records.currency ELSE EXCLUDED.currency END,
	               plan_id = EXCLUDED.plan_id,
	               period_days = EXCLUDED.period_days,
	               provider = EXCLUDED.provider,
	               is_trial = CASE WHEN payment_records.status = 'refunded' THEN payment_records.is_trial ELSE EXCLUDED.is_trial END,
	               purchase_token = EXCLUDED.purchase_token,
	               subscription_id = EXCLUDED.subscription_id,
	               account_id = EXCLUDED.account_id`
	if _, err := tx.Exec(ctx, upsert,
		payment.SubscriptionID, payment.AccountID, payment.Amount, payment.Currency,
		payment.Status, payment.ProviderTransactionID, payment.PlanID, payment.PeriodDays,
		createdAt, model.PaymentGooglePlay, payment.IsTrial, payment.PurchaseToken,
	); err != nil {
		return fmt.Errorf("upsert google play payment: %w", err)
	}
	return nil
}

func (p *Postgres) ensureSubscriptionRow(ctx context.Context, accountID string) error {
	now := time.Now().UTC()
	end := now.Add(100 * 365 * 24 * time.Hour)
	query := `INSERT INTO subscriptions (account_id, tier, status, payment_method,
	           current_period_start, current_period_end, cancel_at_period_end,
	           plan_id, billing_period, price_cents, period_days, external_ref, created_at, updated_at)
	           VALUES ($1, 'free', 'active', 'none', $2, $3, FALSE, 'free', 'lifetime', 0, 0, '', $2, $2)
	           ON CONFLICT (account_id) DO NOTHING`
	_, err := p.pool.Exec(ctx, query, accountID, now, end)
	return err
}
