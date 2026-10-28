package service

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/veritasvpn/services/billing-svc/internal/model"
	"github.com/veritasvpn/services/billing-svc/internal/provider"
	"github.com/veritasvpn/services/billing-svc/internal/repository"
)

// PlayVoidedSyncInterval is how often billing-svc polls the Voided Purchases
// API. Voided-purchase RTDNs are the fast path. This poll records a refund
// when Play does not publish that notice.
const PlayVoidedSyncInterval = 10 * time.Minute

// errVoidNotRecorded means the token is not one of our subscription payments.
// The caller skips it. The error text has no token or order id.
var errVoidNotRecorded = errors.New("voided purchase is not a recorded play subscription")

// SyncPlayVoidedPurchases marks refunded Play orders and drops Premium when
// Google no longer reports the subscription as entitled. It is safe to run
// more than once. Bitcoin payments are not read or written.
func (s *BillingService) SyncPlayVoidedPurchases(ctx context.Context) (int, error) {
	if s == nil || s.play == nil {
		return 0, nil
	}
	return s.play.SyncVoidedPurchases(ctx)
}

// SyncVoidedPurchases polls purchases.voidedpurchases.list and applies each
// order through the same decision as a voidedPurchaseNotification.
func (p *PlayBilling) SyncVoidedPurchases(ctx context.Context) (int, error) {
	if p == nil || p.client == nil {
		return 0, nil
	}
	lister, ok := p.client.(provider.VoidedPurchaseLister)
	if !ok {
		return 0, nil
	}
	start := p.now().UTC().Add(-provider.VoidedPurchaseLookback)
	voids, err := lister.ListVoidedPurchases(ctx, start)
	if err != nil {
		return 0, err
	}
	var applied, failed int
	for _, vp := range voids {
		orderID := strings.TrimSpace(vp.OrderID)
		token := strings.TrimSpace(vp.PurchaseToken)
		if orderID == "" || token == "" {
			continue
		}
		if p.voidedSeen(orderID) {
			continue
		}
		recorded, done, err := p.recordVoidedOrder(ctx, token, orderID)
		if err != nil {
			failed++
			if p.log != nil {
				p.log.Warn("google play voided purchase was not applied", "order_id", orderID)
			}
			continue
		}
		if done {
			p.rememberVoided(orderID)
		}
		if recorded {
			applied++
		}
	}
	if failed > 0 {
		return applied, errors.New("google play voided purchase sync incomplete")
	}
	return applied, nil
}

// recordVoidedOrder returns recorded=true when a payment row was marked
// refunded, and done=true when this order should not be fetched again.
// A purchase that is not linked to an account yet returns done=false so a
// later poll can record it. Errors are safe to log: they do not carry the
// purchase token.
func (p *PlayBilling) recordVoidedOrder(ctx context.Context, token, orderID string) (recorded bool, done bool, err error) {
	purchase, err := p.lookup(ctx, token)
	if errors.Is(err, ErrPlayPurchaseNotFound) || errors.Is(err, ErrPlayRejected) {
		accountID, accErr := p.store.PaymentAccount(ctx, token)
		if accErr != nil {
			return false, false, accErr
		}
		if strings.TrimSpace(accountID) == "" {
			return false, true, nil
		}
		if errors.Is(err, ErrPlayPurchaseNotFound) {
			return p.recordMissingVoid(ctx, token, orderID)
		}
		return false, false, err
	}
	if err != nil {
		return false, false, errors.New("google play voided purchase lookup failed")
	}
	accountID, err := p.accountForPurchase(ctx, token, purchase.ObfuscatedAccountID)
	if errors.Is(err, ErrPlayAccountUnbound) || strings.TrimSpace(accountID) == "" {
		return false, false, nil
	}
	if err != nil {
		return false, false, err
	}
	result, err := p.apply(ctx, accountID, "", token, purchase, false, orderID)
	if repository.IsPlayPaymentUniqueConflict(err) {
		return true, true, nil
	}
	if err != nil {
		return false, false, err
	}
	p.logVoidApplied(result, orderID)
	return true, true, nil
}

// recordMissingVoid refunds the order and removes Premium when Play no longer
// returns the subscription. A full refund with revoke can 404. The order id,
// including a GPA.xxxx..N renewal, is matched as stored.
func (p *PlayBilling) recordMissingVoid(ctx context.Context, token, orderID string) (bool, bool, error) {
	accountID, err := p.store.PaymentAccount(ctx, token)
	if err != nil {
		return false, false, err
	}
	accountID = strings.TrimSpace(accountID)
	if accountID == "" {
		return false, true, nil
	}
	now := p.now().UTC()
	var result PlayVerifyResult
	var planID string
	var periodDays int
	var periodEnd time.Time
	var isTrialStart, isConversion bool
	event, err := p.store.UpdateGooglePlay(ctx, accountID, token, func(sub *model.Subscription, linked []model.PaymentRecord) (*model.PaymentRecord, string, error) {
		productID := playProductFromLinked(sub, linked, orderID)
		if productID == "" {
			return nil, "", errVoidNotRecorded
		}
		purchase := &provider.SubscriptionPurchase{
			State:         "SUBSCRIPTION_STATE_EXPIRED",
			LatestOrderID: orderID,
			LineItems: []provider.PlayLineItem{{
				ProductID:               productID,
				Expiry:                  now.Add(-time.Minute),
				LatestSuccessfulOrderID: orderID,
			}},
		}
		payment, ev, trialStart, conversion, res, err := decidePlayEntitlement(now, accountID, "", token, purchase, sub, linked, false, orderID)
		if err != nil {
			return nil, "", err
		}
		result = res
		planID = sub.PlanID
		periodDays = sub.PeriodDays
		periodEnd = sub.CurrentPeriodEnd
		isTrialStart = trialStart
		isConversion = conversion
		return payment, ev, nil
	})
	if errors.Is(err, errVoidNotRecorded) {
		return false, true, nil
	}
	if repository.IsPlayPaymentUniqueConflict(err) {
		return true, true, nil
	}
	if err != nil {
		return false, false, err
	}
	p.emit(event, accountID, planID, periodDays, periodEnd, isTrialStart, isConversion)
	p.logVoidApplied(&result, orderID)
	return true, true, nil
}

func playProductFromLinked(sub *model.Subscription, linked []model.PaymentRecord, orderID string) string {
	if productID := productIDFromRow(linked, orderID); productID != "" {
		return productID
	}
	for _, row := range linked {
		if product, ok := model.PlayProductByID(row.PlanID); ok {
			return product.ProductID
		}
	}
	if sub != nil {
		if product, ok := model.PlayProductByID(sub.PlanID); ok {
			return product.ProductID
		}
	}
	return ""
}

func productIDFromRow(linked []model.PaymentRecord, orderID string) string {
	for _, row := range linked {
		if row.ProviderTransactionID != orderID {
			continue
		}
		if product, ok := model.PlayProductByID(row.PlanID); ok {
			return product.ProductID
		}
	}
	return ""
}

func (p *PlayBilling) logVoidApplied(result *PlayVerifyResult, orderID string) {
	if p == nil || p.log == nil || strings.TrimSpace(orderID) == "" {
		return
	}
	revoked := result == nil || !result.IsPremium
	p.log.Info("google play voided purchase applied", "order_id", strings.TrimSpace(orderID), "access_revoked", revoked, "renewal_order", isPlayRenewalOrder(orderID))
}

func (p *PlayBilling) voidedSeen(orderID string) bool {
	p.voidedMu.Lock()
	defer p.voidedMu.Unlock()
	if p.voidedApplied == nil {
		return false
	}
	_, ok := p.voidedApplied[orderID]
	return ok
}

func (p *PlayBilling) rememberVoided(orderID string) {
	p.voidedMu.Lock()
	defer p.voidedMu.Unlock()
	if p.voidedApplied == nil {
		p.voidedApplied = map[string]struct{}{}
	}
	p.voidedApplied[orderID] = struct{}{}
}
