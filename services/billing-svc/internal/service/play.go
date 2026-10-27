package service

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/billing-svc/internal/model"
	"github.com/veritasvpn/services/billing-svc/internal/provider"
	"github.com/veritasvpn/services/billing-svc/internal/repository"
)

const maxPurchaseTokenLen = 4096

var (
	ErrPlayNotConfigured     = errors.New("Google Play billing is not configured")
	ErrPlayRTDNNotConfigured = errors.New("Google Play real-time developer notifications are not configured")
	ErrPlayAccountMismatch   = errors.New("this purchase is linked to a different account")
	ErrPlayUnknownProduct    = errors.New("unknown Google Play subscription product")
	ErrPlayPurchaseNotFound  = errors.New("Google Play could not find that purchase")
	ErrPlayRejected          = errors.New("Google Play rejected that purchase")
	// Returned so the RTDN webhook is not HTTP 200. Pub/Sub must retry until
	// the app's Verify call links the purchase to an account.
	ErrPlayAccountUnbound = errors.New("google play purchase is not linked to an account yet")
)

// PlayStore is the persistence Play Billing needs. *repository.Postgres implements it.
type PlayStore interface {
	PaymentAccount(ctx context.Context, purchaseToken string) (string, error)
	// UpdateGooglePlay locks the subscription and every payment row for this
	// purchase token. apply returns the one order row to insert or update.
	// Other linked rows stay as they are.
	UpdateGooglePlay(ctx context.Context, accountID, token string, apply func(sub *model.Subscription, linked []model.PaymentRecord) (*model.PaymentRecord, string, error)) (string, error)
}

// PlayVerifyResult tells the app whether Premium is active and whether the
// Play purchase should be acknowledged.
type PlayVerifyResult struct {
	IsPremium   bool   `json:"is_premium"`
	Acknowledge bool   `json:"acknowledge"`
	Pending     bool   `json:"pending"`
	Status      string `json:"status"`
}

// PlayBilling verifies Play purchases and applies Real-time Developer Notifications.
// A nil client keeps the endpoints disabled without affecting Bitcoin billing.
type PlayBilling struct {
	log     *logging.Logger
	store   PlayStore
	client  provider.PlayClient
	push    provider.PushVerifier
	now     func() time.Time
	publish func(subject string, payload map[string]interface{})
}

func NewPlayBilling(log *logging.Logger, store PlayStore, client provider.PlayClient, push provider.PushVerifier, publish func(string, map[string]interface{})) *PlayBilling {
	return &PlayBilling{
		log:     log,
		store:   store,
		client:  client,
		push:    push,
		now:     time.Now,
		publish: publish,
	}
}

func (s *BillingService) SetPlayBilling(play *PlayBilling) {
	s.play = play
}

func (s *BillingService) VerifyGooglePlayPurchase(ctx context.Context, accountID, productID, purchaseToken string) (*PlayVerifyResult, error) {
	if s.play == nil || s.play.client == nil {
		return nil, ErrPlayNotConfigured
	}
	return s.play.Verify(ctx, accountID, productID, purchaseToken)
}

func (s *BillingService) ProcessGooglePlayNotification(ctx context.Context, body []byte, authorization string) error {
	if s.play == nil || s.play.client == nil {
		return ErrPlayNotConfigured
	}
	if s.play.push == nil {
		return ErrPlayRTDNNotConfigured
	}
	return s.play.ProcessNotification(ctx, body, authorization)
}

func (p *PlayBilling) Verify(ctx context.Context, accountID, productID, purchaseToken string) (*PlayVerifyResult, error) {
	accountID = strings.TrimSpace(accountID)
	productID = strings.TrimSpace(productID)
	purchaseToken = strings.TrimSpace(purchaseToken)
	if accountID == "" || productID == "" || purchaseToken == "" {
		return nil, fmt.Errorf("product_id and purchase_token are required")
	}
	if len(purchaseToken) > maxPurchaseTokenLen {
		return nil, fmt.Errorf("purchase_token is too long")
	}
	if _, ok := model.PlayProductByID(productID); !ok {
		return nil, ErrPlayUnknownProduct
	}
	purchase, err := p.lookup(ctx, purchaseToken)
	if err != nil {
		return nil, err
	}
	if !playAccountMatches(accountID, purchase.ObfuscatedAccountID) {
		return nil, ErrPlayAccountMismatch
	}
	return p.apply(ctx, accountID, productID, purchaseToken, purchase, false, "")
}

func (p *PlayBilling) ProcessNotification(ctx context.Context, body []byte, authorization string) error {
	if err := p.push.Verify(ctx, authorization); err != nil {
		return fmt.Errorf("rtdn auth: %w", err)
	}
	note, err := decodeDeveloperNotification(body)
	if err != nil {
		return err
	}
	if note.PackageName != "" && note.PackageName != p.client.PackageName() {
		return fmt.Errorf("google play package mismatch")
	}
	if note.Test {
		p.log.Info("google play test notification")
		return nil
	}
	if note.PurchaseToken == "" {
		return nil
	}
	purchase, err := p.lookup(ctx, note.PurchaseToken)
	if err != nil {
		return err
	}
	accountID, err := p.accountForPurchase(ctx, note.PurchaseToken, purchase.ObfuscatedAccountID)
	if err != nil {
		return err
	}
	if accountID == "" {
		return ErrPlayAccountUnbound
	}
	_, err = p.apply(ctx, accountID, note.ProductID, note.PurchaseToken, purchase, note.ForceRevoke, note.RefundOrderID)
	if repository.IsPlayPaymentUniqueConflict(err) {
		// Only a duplicate provider_transaction_id is acknowledged. Any other
		// unique violation stays retryable. The message has no token or order id.
		if p.log != nil {
			p.log.Warn("google play payment order id already stored; acknowledging notification")
		}
		return nil
	}
	return err
}

func (p *PlayBilling) lookup(ctx context.Context, token string) (*provider.SubscriptionPurchase, error) {
	purchase, err := p.client.GetSubscription(ctx, token)
	if err != nil {
		if errors.Is(err, provider.ErrPlayPurchaseNotFound) {
			return nil, ErrPlayPurchaseNotFound
		}
		if errors.Is(err, provider.ErrPlayRejected) {
			return nil, ErrPlayRejected
		}
		return nil, err
	}
	return purchase, nil
}

func (p *PlayBilling) apply(ctx context.Context, accountID, requestedProduct, token string, purchase *provider.SubscriptionPurchase, forceRevoke bool, refundOrderID string) (*PlayVerifyResult, error) {
	now := p.now().UTC()
	var result PlayVerifyResult
	var planID string
	var periodDays int
	var periodEnd time.Time
	var isTrialStart, isConversion bool
	event, err := p.store.UpdateGooglePlay(ctx, accountID, token, func(sub *model.Subscription, linked []model.PaymentRecord) (*model.PaymentRecord, string, error) {
		payment, ev, trialStart, conversion, res, err := decidePlayEntitlement(now, accountID, requestedProduct, token, purchase, sub, linked, forceRevoke, refundOrderID)
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
	if errors.Is(err, repository.ErrPurchaseAccountConflict) {
		return nil, ErrPlayAccountMismatch
	}
	if err != nil {
		return nil, err
	}
	p.emit(event, accountID, planID, periodDays, periodEnd, isTrialStart, isConversion)
	return &result, nil
}

func (p *PlayBilling) emit(event, accountID, planID string, periodDays int, periodEnd time.Time, isTrialStart, isConversion bool) {
	if p.publish == nil || event == "" || event == "none" {
		return
	}
	switch event {
	case "subscription.renewed":
		payload := renewedEventPayload(accountID, model.TierPremium, model.PaymentGooglePlay, planID, periodDays, periodEnd)
		if isTrialStart {
			payload["trial"] = true
		}
		if isConversion {
			payload["converted"] = true
		}
		p.publish(event, payload)
	case "subscription.expired":
		p.publish(event, map[string]interface{}{
			"account_id": accountID,
			"tier":       model.TierFree,
		})
	case "subscription.canceled":
		p.publish(event, map[string]interface{}{
			"account_id": accountID,
			"tier":       model.TierPremium,
		})
	}
}

func (p *PlayBilling) accountForPurchase(ctx context.Context, token, obfuscated string) (string, error) {
	stored, err := p.store.PaymentAccount(ctx, token)
	if err != nil {
		return "", err
	}
	stored = strings.TrimSpace(stored)
	obfuscated = strings.TrimSpace(obfuscated)
	if stored != "" {
		if obfuscated == "" || playAccountMatches(stored, obfuscated) {
			return stored, nil
		}
		return "", ErrPlayAccountMismatch
	}
	// A hash cannot be reversed into an Account ID. Leave the notification
	// unacknowledged so the app's Verify call can link it. An empty binding
	// with no stored row is the same: there is nothing to attribute.
	if obfuscated == "" || isPlayAccountHash(obfuscated) {
		return "", ErrPlayAccountUnbound
	}
	return obfuscated, nil
}

func decidePlayEntitlement(now time.Time, accountID, requestedProduct, token string, purchase *provider.SubscriptionPurchase, sub *model.Subscription, linked []model.PaymentRecord, forceRevoke bool, refundOrderID string) (*model.PaymentRecord, string, bool, bool, PlayVerifyResult, error) {
	if purchase.ObfuscatedAccountID != "" && !playAccountMatches(accountID, purchase.ObfuscatedAccountID) {
		return nil, "", false, false, PlayVerifyResult{}, ErrPlayAccountMismatch
	}
	line, err := selectPlayLine(purchase, requestedProduct)
	if err != nil {
		return nil, "", false, false, PlayVerifyResult{}, err
	}
	product, ok := model.PlayProductByID(line.ProductID)
	if !ok {
		return nil, "", false, false, PlayVerifyResult{}, ErrPlayUnknownProduct
	}
	if requestedProduct != "" {
		requested, known := model.PlayProductByID(requestedProduct)
		if !known || requested.ProductID != product.ProductID {
			return nil, "", false, false, PlayVerifyResult{}, ErrPlayUnknownProduct
		}
	}

	before := snapPlay(sub)
	state := purchase.State
	pending := state == "SUBSCRIPTION_STATE_PENDING"
	latestOrder := strings.TrimSpace(playChargeOrderID(purchase, line))
	refundID := strings.TrimSpace(refundOrderID)
	// A refund records that one order. Premium follows the fresh lookup:
	// active, in grace, and canceled with time left stay entitled, including
	// when the refunded order is the latest one. SUBSCRIPTION_REVOKED and a
	// lookup that is no longer entitled still remove Premium.
	entitled := !forceRevoke && !pending && playEntitled(state, line.Expiry, now)

	orderKey := latestOrder
	if refundID != "" {
		orderKey = refundID
	}
	if orderKey == "" {
		orderKey = token
	}
	renewalOrder := isPlayRenewalOrder(orderKey)
	// A renewal order is a real charge. License-tester renewals happen in
	// minutes, while the line item still carries the trial offer and
	// PLAY_TRIAL_DAYS has not elapsed. The base order stays the trial.
	isTrial := line.InTrial && !renewalOrder
	amount := product.PriceCents
	currency := "usd"
	if line.HasPrice {
		amount = line.PriceCents
		if line.Currency != "" {
			currency = strings.ToLower(line.Currency)
		}
	}
	if isTrial {
		amount = 0
	}

	// Older builds stored every renewal on the purchase-token row. Re-point
	// that row when it already is this charge. A different charge, including
	// a token-keyed trial next to a paid renewal, stays put.
	allowLegacy := !renewalOrder || legacyTokenRowIsThisCharge(linked, token, sub, line.Expiry)
	if refundID != "" && renewalOrder && latestOrder != "" && refundID != latestOrder {
		allowLegacy = false
	}
	existing := matchPlayPayment(linked, token, orderKey, allowLegacy)
	if existing != nil && existing.IsTrial && !renewalOrder && !forceRevoke && refundID == "" {
		isTrial = true
		amount = 0
	}
	status := playPaymentStatus(state, forceRevoke, entitled)
	if state == "SUBSCRIPTION_STATE_EXPIRED" && orderKey != token && refundID == "" && !forceRevoke {
		// The charge succeeded. Expiry only removes Premium.
		status = model.PaymentCompleted
	}
	if existing != nil && existing.Status == model.PaymentCompleted && status == model.PaymentFailed && !forceRevoke && refundID == "" {
		status = model.PaymentCompleted
	}
	if forceRevoke || refundID != "" {
		status = model.PaymentRefunded
		if existing != nil {
			amount = existing.Amount
			if existing.Currency != "" {
				currency = existing.Currency
			}
			isTrial = existing.IsTrial
		}
	}
	// A later verify or RTDN must not turn a refund back into a completed charge.
	if existing != nil && existing.Status == model.PaymentRefunded && status != model.PaymentRefunded {
		status = model.PaymentRefunded
		amount = existing.Amount
		if existing.Currency != "" {
			currency = existing.Currency
		}
		isTrial = existing.IsTrial
	}

	payment := &model.PaymentRecord{
		SubscriptionID:        sub.ID,
		AccountID:             accountID,
		Amount:                amount,
		Currency:              currency,
		ProviderTransactionID: orderKey,
		PlanID:                product.PlanID,
		PeriodDays:            product.PeriodDays,
		Provider:              model.PaymentGooglePlay,
		Status:                status,
		IsTrial:               isTrial,
		PurchaseToken:         token,
	}
	if existing != nil {
		payment.ID = existing.ID
		payment.CreatedAt = existing.CreatedAt
	}

	if entitled {
		newEnd := line.Expiry.UTC()
		otherPlayToken := sub.PaymentMethod == model.PaymentGooglePlay && sub.ExternalRef != "" && sub.ExternalRef != token
		keepLongerOther := sub.Tier == model.TierPremium && sub.Status == model.StatusActive &&
			sub.PaymentMethod != model.PaymentGooglePlay && sub.PaymentMethod != model.PaymentNone &&
			sub.CurrentPeriodEnd.After(newEnd) && sub.CurrentPeriodEnd.After(now)
		if otherPlayToken && !newEnd.After(sub.CurrentPeriodEnd) {
			// A different Play token still covers a longer period.
		} else if keepLongerOther {
			// Don't shorten an active Bitcoin period that outlasts this Play expiry.
		} else {
			assignPlaySubscription(sub, product, token, line, newEnd, now, state)
		}
	} else if playOwnsEntitlement(sub, token) {
		revokePlaySubscription(sub, now, state, forceRevoke, purchase.PauseResumesAt)
	}

	event, isTrialStart, isConversion := playEvent(before, sub, payment.IsTrial, conversionMarker(linked, existing, payment.IsTrial))
	premium := sub.Tier == model.TierPremium && sub.Status == model.StatusActive && now.Before(sub.CurrentPeriodEnd)
	result := PlayVerifyResult{
		IsPremium:   premium,
		Acknowledge: entitled,
		Pending:     pending,
		Status:      sub.Status,
	}
	return payment, event, isTrialStart, isConversion, result, nil
}

// playChargeOrderID is the idempotency key for one successful charge.
// Renewals use a new Google order id (GPA.xxxx..0) on the same purchase token.
func playChargeOrderID(purchase *provider.SubscriptionPurchase, line provider.PlayLineItem) string {
	if id := strings.TrimSpace(line.LatestSuccessfulOrderID); id != "" {
		return id
	}
	if purchase != nil {
		if id := strings.TrimSpace(purchase.LatestOrderID); id != "" {
			return id
		}
	}
	return ""
}

func isPlayRenewalOrder(orderID string) bool {
	return strings.Contains(orderID, "..")
}

// matchPlayPayment finds the row for this order. allowLegacy also matches the
// purchase-token row older builds used for every charge.
func matchPlayPayment(linked []model.PaymentRecord, token, orderKey string, allowLegacy bool) *model.PaymentRecord {
	for i := range linked {
		if linked[i].ProviderTransactionID == orderKey {
			cp := linked[i]
			return &cp
		}
	}
	if !allowLegacy || token == "" || token == orderKey {
		return nil
	}
	for i := range linked {
		if linked[i].ProviderTransactionID == token {
			cp := linked[i]
			return &cp
		}
	}
	return nil
}

// legacyTokenRowIsThisCharge is true when the only pre-order-id row already
// records this renewal. Old builds updated that token row and the period end
// together, so a later unpaid renewal has a later expiry and must be a new row.
func legacyTokenRowIsThisCharge(linked []model.PaymentRecord, token string, sub *model.Subscription, expiry time.Time) bool {
	if token == "" || sub == nil || expiry.IsZero() || sub.CurrentPeriodEnd.IsZero() {
		return false
	}
	var legacy *model.PaymentRecord
	for i := range linked {
		if linked[i].ProviderTransactionID != token {
			continue
		}
		row := linked[i]
		legacy = &row
		break
	}
	if legacy == nil || legacy.IsTrial || legacy.Amount <= 0 || legacy.Status == model.PaymentRefunded {
		return false
	}
	return !sub.CurrentPeriodEnd.UTC().Before(expiry.UTC())
}

// conversionMarker is non-nil only for the first paid order after a trial,
// so a later renewal is not reported as a second conversion.
func conversionMarker(linked []model.PaymentRecord, existing *model.PaymentRecord, isTrial bool) *model.PaymentRecord {
	if isTrial {
		return nil
	}
	if existing != nil && existing.IsTrial {
		return existing
	}
	if existing != nil {
		return nil
	}
	priorTrial := false
	for _, row := range linked {
		if row.IsTrial {
			priorTrial = true
			continue
		}
		if row.Amount > 0 && (row.Status == model.PaymentCompleted || row.Status == model.PaymentRefunded) {
			return nil
		}
	}
	if !priorTrial {
		return nil
	}
	return &model.PaymentRecord{IsTrial: true}
}

func selectPlayLine(purchase *provider.SubscriptionPurchase, requestedProduct string) (provider.PlayLineItem, error) {
	if purchase == nil || len(purchase.LineItems) == 0 {
		return provider.PlayLineItem{}, fmt.Errorf("google play subscription has no line items")
	}
	if requestedProduct != "" {
		product, ok := model.PlayProductByID(requestedProduct)
		if !ok {
			return provider.PlayLineItem{}, ErrPlayUnknownProduct
		}
		for _, line := range purchase.LineItems {
			if line.ProductID == product.ProductID {
				return line, nil
			}
		}
		return provider.PlayLineItem{}, ErrPlayUnknownProduct
	}
	var best provider.PlayLineItem
	found := false
	for _, line := range purchase.LineItems {
		if _, ok := model.PlayProductByID(line.ProductID); !ok {
			continue
		}
		if !found || line.Expiry.After(best.Expiry) {
			best = line
			found = true
		}
	}
	if !found {
		return provider.PlayLineItem{}, ErrPlayUnknownProduct
	}
	return best, nil
}

func playEntitled(state string, expiry, now time.Time) bool {
	if !expiry.After(now) {
		return false
	}
	switch state {
	case "SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED":
		return true
	default:
		return false
	}
}

func playPaymentStatus(state string, forceRevoke, entitled bool) string {
	if forceRevoke {
		return model.PaymentRefunded
	}
	switch state {
	case "SUBSCRIPTION_STATE_PENDING":
		return model.PaymentPending
	case "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED":
		return model.PaymentFailed
	default:
		if entitled {
			return model.PaymentCompleted
		}
		if state == "SUBSCRIPTION_STATE_ON_HOLD" || state == "SUBSCRIPTION_STATE_PAUSED" {
			return model.PaymentCompleted
		}
		return model.PaymentFailed
	}
}

func playOwnsEntitlement(sub *model.Subscription, token string) bool {
	if sub.PaymentMethod != model.PaymentGooglePlay {
		return false
	}
	return sub.ExternalRef == "" || sub.ExternalRef == token
}

func assignPlaySubscription(sub *model.Subscription, product model.PlayProduct, token string, line provider.PlayLineItem, newEnd, now time.Time, state string) {
	start := now
	if !sub.CurrentPeriodStart.IsZero() && sub.Tier == model.TierPremium && sub.CurrentPeriodStart.Before(now) {
		start = sub.CurrentPeriodStart
	}
	sub.Tier = model.TierPremium
	sub.Status = model.StatusActive
	sub.PaymentMethod = model.PaymentGooglePlay
	sub.PlanID = product.PlanID
	sub.BillingPeriod = product.BillingPeriod
	sub.PriceCents = product.PriceCents
	sub.PeriodDays = product.PeriodDays
	sub.CurrentPeriodStart = start
	sub.CurrentPeriodEnd = newEnd
	sub.ExternalRef = token
	sub.CancelAtPeriodEnd = state == "SUBSCRIPTION_STATE_CANCELED" || !line.AutoRenewEnabled
	sub.AccessState = ""
	sub.PauseResumesAt = nil
}

func revokePlaySubscription(sub *model.Subscription, now time.Time, state string, forceRevoke bool, pauseResumesAt *time.Time) {
	if !forceRevoke && state == "SUBSCRIPTION_STATE_PAUSED" {
		sub.Tier = model.TierPremium
		sub.Status = model.StatusPastDue
		sub.PaymentMethod = model.PaymentGooglePlay
		sub.CancelAtPeriodEnd = false
		sub.AccessState = model.AccessPaused
		sub.PauseResumesAt = nil
		if pauseResumesAt != nil {
			utc := pauseResumesAt.UTC()
			sub.PauseResumesAt = &utc
		}
		return
	}
	if !forceRevoke && state == "SUBSCRIPTION_STATE_ON_HOLD" {
		sub.Tier = model.TierPremium
		sub.Status = model.StatusPastDue
		sub.PaymentMethod = model.PaymentGooglePlay
		sub.CancelAtPeriodEnd = false
		sub.AccessState = ""
		sub.PauseResumesAt = nil
		return
	}
	if !forceRevoke && state == "SUBSCRIPTION_STATE_PENDING" {
		return
	}
	sub.Tier = model.TierFree
	sub.Status = model.StatusActive
	sub.PaymentMethod = model.PaymentNone
	sub.PlanID = "free"
	sub.BillingPeriod = "lifetime"
	sub.PriceCents = 0
	sub.PeriodDays = 0
	sub.CurrentPeriodStart = now
	sub.CurrentPeriodEnd = now.Add(100 * 365 * 24 * time.Hour)
	sub.CancelAtPeriodEnd = false
	sub.ExternalRef = ""
	sub.AccessState = ""
	sub.PauseResumesAt = nil
}

type playSnap struct {
	tier, status, method, plan, external string
	end                                  time.Time
	cancel                               bool
}

func snapPlay(sub *model.Subscription) playSnap {
	return playSnap{
		tier: sub.Tier, status: sub.Status, method: sub.PaymentMethod,
		plan: sub.PlanID, external: sub.ExternalRef, end: sub.CurrentPeriodEnd.UTC(),
		cancel: sub.CancelAtPeriodEnd,
	}
}

func playEvent(before playSnap, sub *model.Subscription, isTrial bool, existing *model.PaymentRecord) (string, bool, bool) {
	after := snapPlay(sub)
	if before == after {
		return "none", false, false
	}
	wasPremium := before.tier == model.TierPremium && before.status == model.StatusActive
	nowPremium := after.tier == model.TierPremium && after.status == model.StatusActive
	if wasPremium && !nowPremium {
		return "subscription.expired", false, false
	}

	isTrialStart := isTrial && nowPremium && !wasPremium
	isConversion := !isTrial && nowPremium && existing != nil && existing.IsTrial

	if nowPremium && (!before.end.Equal(after.end) || before.tier != model.TierPremium || before.status != model.StatusActive) {
		return "subscription.renewed", isTrialStart, isConversion
	}
	if nowPremium && after.cancel && !before.cancel {
		return "subscription.canceled", false, false
	}
	if wasPremium && !nowPremium {
		return "subscription.expired", false, false
	}
	if before.tier == model.TierPremium && after.status == model.StatusPastDue && before.status != model.StatusPastDue {
		return "subscription.expired", false, false
	}
	return "none", false, false
}

type developerNote struct {
	PackageName   string
	PurchaseToken string
	ProductID     string
	Type          int
	Test          bool
	ForceRevoke   bool
	RefundOrderID string
}

func decodeDeveloperNotification(body []byte) (developerNote, error) {
	var push struct {
		Message struct {
			Data string `json:"data"`
		} `json:"message"`
	}
	if err := json.Unmarshal(body, &push); err != nil {
		return developerNote{}, fmt.Errorf("invalid pubsub body")
	}
	if push.Message.Data == "" {
		return developerNote{}, fmt.Errorf("pubsub message was empty")
	}
	raw, err := base64.StdEncoding.DecodeString(push.Message.Data)
	if err != nil {
		raw, err = base64.RawStdEncoding.DecodeString(push.Message.Data)
		if err != nil {
			return developerNote{}, fmt.Errorf("pubsub message data was not base64")
		}
	}
	var note struct {
		PackageName      string `json:"packageName"`
		TestNotification *struct {
			Version string `json:"version"`
		} `json:"testNotification"`
		SubscriptionNotification *struct {
			NotificationType int    `json:"notificationType"`
			PurchaseToken    string `json:"purchaseToken"`
			SubscriptionID   string `json:"subscriptionId"`
		} `json:"subscriptionNotification"`
		VoidedPurchaseNotification *struct {
			PurchaseToken string `json:"purchaseToken"`
			OrderID       string `json:"orderId"`
			ProductType   int    `json:"productType"`
		} `json:"voidedPurchaseNotification"`
	}
	if err := json.Unmarshal(raw, &note); err != nil {
		return developerNote{}, fmt.Errorf("invalid developer notification")
	}
	out := developerNote{PackageName: note.PackageName}
	if note.TestNotification != nil {
		out.Test = true
		return out, nil
	}
	if note.SubscriptionNotification != nil {
		out.PurchaseToken = note.SubscriptionNotification.PurchaseToken
		out.ProductID = note.SubscriptionNotification.SubscriptionID
		out.Type = note.SubscriptionNotification.NotificationType
		out.ForceRevoke = out.Type == 12
		return out, nil
	}
	if note.VoidedPurchaseNotification != nil {
		if note.VoidedPurchaseNotification.ProductType != 0 && note.VoidedPurchaseNotification.ProductType != 1 {
			return out, nil
		}
		out.PurchaseToken = note.VoidedPurchaseNotification.PurchaseToken
		out.RefundOrderID = strings.TrimSpace(note.VoidedPurchaseNotification.OrderID)
		// The fresh subscriptionsv2 lookup decides entitlement. Refunding an
		// order, including the latest one, does not revoke a subscription
		// Google still reports as entitled.
		out.ForceRevoke = false
		return out, nil
	}
	return out, nil
}
