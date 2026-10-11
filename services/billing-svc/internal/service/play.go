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
	"go.uber.org/zap"
)

const maxPurchaseTokenLen = 4096

var (
	ErrPlayNotConfigured     = errors.New("Google Play billing is not configured")
	ErrPlayRTDNNotConfigured = errors.New("Google Play real-time developer notifications are not configured")
	ErrPlayAccountMismatch   = errors.New("this purchase is linked to a different account")
	ErrPlayUnknownProduct    = errors.New("unknown Google Play subscription product")
	ErrPlayPurchaseNotFound  = errors.New("Google Play could not find that purchase")
	ErrPlayRejected          = errors.New("Google Play rejected that purchase")
)

// PlayStore is the persistence Play Billing needs. *repository.Postgres implements it.
type PlayStore interface {
	PaymentAccount(ctx context.Context, providerTxnID string) (string, error)
	UpdateGooglePlay(ctx context.Context, accountID, token string, apply func(sub *model.Subscription, existing *model.PaymentRecord) (*model.PaymentRecord, string, error)) (string, error)
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
	if purchase.ObfuscatedAccountID == "" || purchase.ObfuscatedAccountID != accountID {
		return nil, ErrPlayAccountMismatch
	}
	return p.apply(ctx, accountID, productID, purchaseToken, purchase, false)
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
	accountID := purchase.ObfuscatedAccountID
	if accountID == "" {
		accountID, err = p.store.PaymentAccount(ctx, note.PurchaseToken)
		if err != nil {
			return err
		}
	}
	if accountID == "" {
		p.log.Warn("google play notification has no account binding",
			zap.String("token_hash", logging.HashIdentifier(note.PurchaseToken)),
			zap.Int("notification_type", note.Type),
		)
		return nil
	}
	if purchase.ObfuscatedAccountID != "" && purchase.ObfuscatedAccountID != accountID {
		return ErrPlayAccountMismatch
	}
	_, err = p.apply(ctx, accountID, note.ProductID, note.PurchaseToken, purchase, note.ForceRevoke)
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

func (p *PlayBilling) apply(ctx context.Context, accountID, requestedProduct, token string, purchase *provider.SubscriptionPurchase, forceRevoke bool) (*PlayVerifyResult, error) {
	now := p.now().UTC()
	var result PlayVerifyResult
	var planID string
	var periodDays int
	var periodEnd time.Time
	event, err := p.store.UpdateGooglePlay(ctx, accountID, token, func(sub *model.Subscription, existing *model.PaymentRecord) (*model.PaymentRecord, string, error) {
		payment, ev, res, err := decidePlayEntitlement(now, accountID, requestedProduct, token, purchase, sub, existing, forceRevoke)
		if err != nil {
			return nil, "", err
		}
		result = res
		planID = sub.PlanID
		periodDays = sub.PeriodDays
		periodEnd = sub.CurrentPeriodEnd
		return payment, ev, nil
	})
	if errors.Is(err, repository.ErrPurchaseAccountConflict) {
		return nil, ErrPlayAccountMismatch
	}
	if err != nil {
		return nil, err
	}
	p.emit(event, accountID, planID, periodDays, periodEnd)
	return &result, nil
}

func (p *PlayBilling) emit(event, accountID, planID string, periodDays int, periodEnd time.Time) {
	if p.publish == nil || event == "" || event == "none" {
		return
	}
	switch event {
	case "subscription.renewed":
		p.publish(event, renewedEventPayload(accountID, model.TierPremium, model.PaymentGooglePlay, planID, periodDays, periodEnd))
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

func decidePlayEntitlement(now time.Time, accountID, requestedProduct, token string, purchase *provider.SubscriptionPurchase, sub *model.Subscription, existing *model.PaymentRecord, forceRevoke bool) (*model.PaymentRecord, string, PlayVerifyResult, error) {
	if purchase.ObfuscatedAccountID != "" && purchase.ObfuscatedAccountID != accountID {
		return nil, "", PlayVerifyResult{}, ErrPlayAccountMismatch
	}
	line, err := selectPlayLine(purchase, requestedProduct)
	if err != nil {
		return nil, "", PlayVerifyResult{}, err
	}
	product, ok := model.PlayProductByID(line.ProductID)
	if !ok {
		return nil, "", PlayVerifyResult{}, ErrPlayUnknownProduct
	}
	if requestedProduct != "" {
		requested, known := model.PlayProductByID(requestedProduct)
		if !known || requested.ProductID != product.ProductID {
			return nil, "", PlayVerifyResult{}, ErrPlayUnknownProduct
		}
	}

	before := snapPlay(sub)
	state := purchase.State
	pending := state == "SUBSCRIPTION_STATE_PENDING"
	entitled := !forceRevoke && !pending && playEntitled(state, line.Expiry, now)

	amount := product.PriceCents
	currency := "usd"
	if line.HasPrice && line.PriceCents > 0 {
		amount = line.PriceCents
		if line.Currency != "" {
			currency = line.Currency
		}
	}
	// Trials are free: set amount to 0 and mark as trial
	isTrial := line.InTrial
	if isTrial {
		amount = 0
	}
	payment := &model.PaymentRecord{
		SubscriptionID:        sub.ID,
		AccountID:             accountID,
		Amount:                amount,
		Currency:              currency,
		ProviderTransactionID: token,
		PlanID:                product.PlanID,
		PeriodDays:            product.PeriodDays,
		Provider:              model.PaymentGooglePlay,
		Status:                playPaymentStatus(state, forceRevoke, entitled),
		IsTrial:               isTrial,
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
		revokePlaySubscription(sub, now, state, forceRevoke)
	}

	event := playEvent(before, sub, payment.IsTrial)
	premium := sub.Tier == model.TierPremium && sub.Status == model.StatusActive && now.Before(sub.CurrentPeriodEnd)
	result := PlayVerifyResult{
		IsPremium:   premium,
		Acknowledge: entitled,
		Pending:     pending,
		Status:      sub.Status,
	}
	return payment, event, result, nil
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
	case "SUBSCRIPTION_STATE_EXPIRED":
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
}

func revokePlaySubscription(sub *model.Subscription, now time.Time, state string, forceRevoke bool) {
	if !forceRevoke && (state == "SUBSCRIPTION_STATE_ON_HOLD" || state == "SUBSCRIPTION_STATE_PAUSED") {
		sub.Tier = model.TierPremium
		sub.Status = model.StatusPastDue
		sub.PaymentMethod = model.PaymentGooglePlay
		sub.CancelAtPeriodEnd = false
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

func playEvent(before playSnap, sub *model.Subscription, isTrial bool) string {
	after := snapPlay(sub)
	if before == after {
		return "none"
	}
	wasPremium := before.tier == model.TierPremium && before.status == model.StatusActive
	nowPremium := after.tier == model.TierPremium && after.status == model.StatusActive
	if wasPremium && !nowPremium {
		return "subscription.expired"
	}
	// Trial started: new premium subscription with is_trial flag
	if isTrial && nowPremium && !wasPremium {
		return "subscription.trial_started"
	}
	// Trial converted to paid: was in trial period, now in paid period
	if !isTrial && nowPremium && before.tier == model.TierPremium {
		// Check if this is a conversion from trial by looking at the payment history
		// For now, we'll use a simpler heuristic: if we're not in trial but we have premium,
		// and the period changed, it might be a conversion
		if !before.end.Equal(after.end) {
			return "subscription.converted"
		}
	}
	if nowPremium && (!before.end.Equal(after.end) || before.tier != model.TierPremium || before.status != model.StatusActive) {
		return "subscription.renewed"
	}
	if nowPremium && after.cancel && !before.cancel {
		return "subscription.canceled"
	}
	if wasPremium && !nowPremium {
		return "subscription.expired"
	}
	if before.tier == model.TierPremium && after.status == model.StatusPastDue && before.status != model.StatusPastDue {
		return "subscription.expired"
	}
	return "none"
}

type developerNote struct {
	PackageName   string
	PurchaseToken string
	ProductID     string
	Type          int
	Test          bool
	ForceRevoke   bool
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
		out.ForceRevoke = true
		out.Type = 12
		return out, nil
	}
	return out, nil
}
