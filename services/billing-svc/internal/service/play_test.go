package service

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/billing-svc/internal/model"
	"github.com/veritasvpn/services/billing-svc/internal/provider"
	"github.com/veritasvpn/services/billing-svc/internal/repository"
)

type memPlayStore struct {
	mu   sync.Mutex
	subs map[string]*model.Subscription
	pays map[string]*model.PaymentRecord
}

func newMemPlayStore() *memPlayStore {
	return &memPlayStore{
		subs: map[string]*model.Subscription{},
		pays: map[string]*model.PaymentRecord{},
	}
}

func (m *memPlayStore) PaymentAccount(ctx context.Context, token string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var found string
	var foundAt time.Time
	for _, payment := range m.pays {
		if payment.ProviderTransactionID != token && payment.PurchaseToken != token {
			continue
		}
		if found == "" || payment.CreatedAt.Before(foundAt) {
			found = payment.AccountID
			foundAt = payment.CreatedAt
		}
	}
	if found != "" {
		return found, nil
	}
	for _, sub := range m.subs {
		if sub.ExternalRef == token && token != "" {
			return sub.AccountID, nil
		}
	}
	return "", nil
}

func (m *memPlayStore) linked(token string) []model.PaymentRecord {
	var out []model.PaymentRecord
	for _, payment := range m.pays {
		if payment.ProviderTransactionID == token || payment.PurchaseToken == token {
			out = append(out, *payment)
		}
	}
	return out
}

func (m *memPlayStore) UpdateGooglePlay(ctx context.Context, accountID, token string, apply func(sub *model.Subscription, linked []model.PaymentRecord) (*model.PaymentRecord, string, error)) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	sub := m.subs[accountID]
	if sub == nil {
		now := time.Now().UTC()
		sub = &model.Subscription{
			ID:                 "sub-" + accountID,
			AccountID:          accountID,
			Tier:               model.TierFree,
			Status:             model.StatusActive,
			PaymentMethod:      model.PaymentNone,
			PlanID:             "free",
			BillingPeriod:      "lifetime",
			CurrentPeriodStart: now,
			CurrentPeriodEnd:   now.Add(100 * 365 * 24 * time.Hour),
		}
		m.subs[accountID] = sub
	}
	linked := m.linked(token)
	for _, row := range linked {
		if row.AccountID != "" && row.AccountID != accountID {
			return "", repository.ErrPurchaseAccountConflict
		}
	}
	payment, event, err := apply(sub, linked)
	if err != nil {
		return "", err
	}
	if payment == nil || strings.TrimSpace(payment.ProviderTransactionID) == "" {
		return "", errors.New("google play payment was not recorded")
	}
	payment.AccountID = accountID
	payment.Provider = model.PaymentGooglePlay
	if strings.TrimSpace(payment.PurchaseToken) == "" {
		payment.PurchaseToken = token
	}
	if payment.ID != "" {
		for key, row := range m.pays {
			if row.ID == payment.ID && key != payment.ProviderTransactionID {
				delete(m.pays, key)
			}
		}
	} else if existing := m.pays[payment.ProviderTransactionID]; existing != nil {
		payment.ID = existing.ID
		if !existing.CreatedAt.IsZero() {
			payment.CreatedAt = existing.CreatedAt
		}
	}
	if payment.ID == "" {
		payment.ID = "pay-" + payment.ProviderTransactionID
	}
	if payment.CreatedAt.IsZero() {
		payment.CreatedAt = time.Now().UTC()
	}
	stored := *payment
	m.pays[payment.ProviderTransactionID] = &stored
	return event, nil
}

func (m *memPlayStore) paymentCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.pays)
}

type stubPlayClient struct {
	pkg      string
	purchase *provider.SubscriptionPurchase
	err      error
	calls    int
}

func (s *stubPlayClient) PackageName() string { return s.pkg }

func (s *stubPlayClient) GetSubscription(ctx context.Context, purchaseToken string) (*provider.SubscriptionPurchase, error) {
	s.calls++
	if s.err != nil {
		return nil, s.err
	}
	return s.purchase, nil
}

type allowPush struct{}

func (allowPush) Verify(ctx context.Context, authorizationHeader string) error {
	if authorizationHeader != "Bearer test" {
		return errors.New("missing push authorization")
	}
	return nil
}

func testLog(t *testing.T) *logging.Logger {
	t.Helper()
	log, err := logging.New("error")
	if err != nil {
		t.Fatal(err)
	}
	return log
}

func activePurchase(account, product string, expiry time.Time, state string) *provider.SubscriptionPurchase {
	return &provider.SubscriptionPurchase{
		State:               state,
		ObfuscatedAccountID: account,
		LineItems: []provider.PlayLineItem{{
			ProductID:        product,
			BasePlanID:       "monthly",
			Expiry:           expiry.UTC(),
			AutoRenewEnabled: state == "SUBSCRIPTION_STATE_ACTIVE" || state == "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
		}},
	}
}

func TestGooglePlayVerifyGrantsAndIsIdempotentPerToken(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	expiry := now.Add(30 * 24 * time.Hour)
	store := newMemPlayStore()
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase("acc-1", "premium_monthly", expiry, "SUBSCRIPTION_STATE_ACTIVE")}
	var events []string
	play := NewPlayBilling(testLog(t), store, client, nil, func(subject string, payload map[string]interface{}) {
		events = append(events, subject)
	})
	play.now = func() time.Time { return now }

	first, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-1")
	if err != nil {
		t.Fatal(err)
	}
	if !first.IsPremium || !first.Acknowledge || first.Pending {
		t.Fatalf("first result = %+v", first)
	}
	sub := store.subs["acc-1"]
	if !sub.CurrentPeriodEnd.Equal(expiry) || sub.PaymentMethod != model.PaymentGooglePlay || sub.ExternalRef != "token-1" {
		t.Fatalf("subscription = %+v", sub)
	}
	if store.paymentCount() != 1 || store.pays["token-1"].Provider != model.PaymentGooglePlay || store.pays["token-1"].Status != model.PaymentCompleted {
		t.Fatalf("payment = %+v count=%d", store.pays["token-1"], store.paymentCount())
	}

	second, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-1")
	if err != nil {
		t.Fatal(err)
	}
	if !second.IsPremium || store.paymentCount() != 1 {
		t.Fatalf("second result=%+v payments=%d", second, store.paymentCount())
	}
	if !store.subs["acc-1"].CurrentPeriodEnd.Equal(expiry) {
		t.Fatal("idempotent verify extended the period")
	}
	if len(events) != 1 || events[0] != "subscription.renewed" {
		t.Fatalf("events = %v", events)
	}
}

func TestGooglePlayRenewalRecordsOnePaymentPerOrder(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	firstEnd := now.Add(30 * 24 * time.Hour)
	const token = "token-1"
	const firstOrder = "GPA.1234-5678-9012-34567"
	const renewalOrder = "GPA.1234-5678-9012-34567..0"
	store := newMemPlayStore()
	first := activePurchase("acc-1", "premium_monthly", firstEnd, "SUBSCRIPTION_STATE_ACTIVE")
	first.LatestOrderID = firstOrder
	first.LineItems[0].LatestSuccessfulOrderID = firstOrder
	first.LineItems[0].HasPrice = true
	first.LineItems[0].PriceCents = 300
	first.LineItems[0].Currency = "usd"
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: first}
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(string, map[string]interface{}) {})
	play.now = func() time.Time { return now }
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", token); err != nil {
		t.Fatal(err)
	}
	if store.paymentCount() != 1 || store.pays[firstOrder].Amount != 300 || store.pays[firstOrder].PurchaseToken != token {
		t.Fatalf("first payment = %+v count=%d", store.pays[firstOrder], store.paymentCount())
	}

	renewedEnd := firstEnd.Add(30 * 24 * time.Hour)
	renewed := activePurchase("acc-1", "premium_monthly", renewedEnd, "SUBSCRIPTION_STATE_ACTIVE")
	renewed.LatestOrderID = renewalOrder
	renewed.LineItems[0].LatestSuccessfulOrderID = renewalOrder
	renewed.LineItems[0].HasPrice = true
	renewed.LineItems[0].PriceCents = 300
	renewed.LineItems[0].Currency = "PYG"
	client.purchase = renewed
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", token); err != nil {
		t.Fatal(err)
	}
	if !store.subs["acc-1"].CurrentPeriodEnd.Equal(renewedEnd) {
		t.Fatalf("end = %s, want %s", store.subs["acc-1"].CurrentPeriodEnd, renewedEnd)
	}
	if store.paymentCount() != 2 {
		t.Fatalf("payments = %d, want 2", store.paymentCount())
	}
	paid := store.pays[renewalOrder]
	if paid == nil || paid.Amount != 300 || paid.Currency != "pyg" || paid.Status != model.PaymentCompleted || paid.IsTrial || paid.PurchaseToken != token {
		t.Fatalf("renewal payment = %+v", paid)
	}
	if store.pays[firstOrder].Amount != 300 || store.pays[firstOrder].Status != model.PaymentCompleted {
		t.Fatalf("first order was rewritten: %+v", store.pays[firstOrder])
	}

	body := pubSubBody(t, map[string]any{
		"packageName": "cloud.veritasvpn",
		"subscriptionNotification": map[string]any{
			"notificationType": 2,
			"purchaseToken":    token,
			"subscriptionId":   "premium_monthly",
		},
	})
	if err := play.ProcessNotification(context.Background(), body, "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", token); err != nil {
		t.Fatal(err)
	}
	if store.paymentCount() != 2 {
		t.Fatalf("redelivery duplicated payments: %d", store.paymentCount())
	}
	account, err := store.PaymentAccount(context.Background(), token)
	if err != nil || account != "acc-1" {
		t.Fatalf("account for token = %q err=%v", account, err)
	}
}

func TestGooglePlayRejectsMismatchedAccount(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	store := newMemPlayStore()
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase("someone-else", "premium_monthly", now.Add(24*time.Hour), "SUBSCRIPTION_STATE_ACTIVE")}
	play := NewPlayBilling(testLog(t), store, client, nil, nil)
	play.now = func() time.Time { return now }
	_, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-1")
	if !errors.Is(err, ErrPlayAccountMismatch) {
		t.Fatalf("err = %v", err)
	}
	if store.paymentCount() != 0 {
		t.Fatal("mismatched purchase was stored")
	}
}

func TestGooglePlayStatesUpdateEntitlement(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	expiry := now.Add(10 * 24 * time.Hour)
	cases := []struct {
		name      string
		state     string
		revoke    bool
		wantTier  string
		wantState string
		wantPay   string
		premium   bool
		cancel    bool
	}{
		{name: "grace", state: "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", wantTier: model.TierPremium, wantState: model.StatusActive, wantPay: model.PaymentCompleted, premium: true},
		{name: "canceled", state: "SUBSCRIPTION_STATE_CANCELED", wantTier: model.TierPremium, wantState: model.StatusActive, wantPay: model.PaymentCompleted, premium: true, cancel: true},
		{name: "hold", state: "SUBSCRIPTION_STATE_ON_HOLD", wantTier: model.TierPremium, wantState: model.StatusPastDue, wantPay: model.PaymentCompleted},
		{name: "paused", state: "SUBSCRIPTION_STATE_PAUSED", wantTier: model.TierPremium, wantState: model.StatusPastDue, wantPay: model.PaymentCompleted},
		{name: "expired", state: "SUBSCRIPTION_STATE_EXPIRED", wantTier: model.TierFree, wantState: model.StatusActive, wantPay: model.PaymentCompleted},
		{name: "revoked", state: "SUBSCRIPTION_STATE_EXPIRED", revoke: true, wantTier: model.TierFree, wantState: model.StatusActive, wantPay: model.PaymentRefunded},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store := newMemPlayStore()
			client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase("acc-1", "premium_annual", expiry, "SUBSCRIPTION_STATE_ACTIVE")}
			play := NewPlayBilling(testLog(t), store, client, allowPush{}, nil)
			play.now = func() time.Time { return now }
			if _, err := play.Verify(context.Background(), "acc-1", "premium_annual", "token-1"); err != nil {
				t.Fatal(err)
			}
			client.purchase = activePurchase("acc-1", "premium_annual", expiry, tc.state)
			if tc.name == "expired" {
				client.purchase.LineItems[0].Expiry = now.Add(-time.Hour)
			}
			_, err := play.apply(context.Background(), "acc-1", "premium_annual", "token-1", client.purchase, tc.revoke, "")
			if err != nil {
				t.Fatal(err)
			}
			sub := store.subs["acc-1"]
			if sub.Tier != tc.wantTier || sub.Status != tc.wantState || sub.CancelAtPeriodEnd != tc.cancel {
				t.Fatalf("sub = %+v", sub)
			}
			premium := sub.Tier == model.TierPremium && sub.Status == model.StatusActive && now.Before(sub.CurrentPeriodEnd)
			if premium != tc.premium {
				t.Fatalf("premium = %v, want %v", premium, tc.premium)
			}
			if store.pays["token-1"].Status != tc.wantPay || store.pays["token-1"].PlanID != model.PlanAnnual {
				t.Fatalf("payment = %+v", store.pays["token-1"])
			}
		})
	}
}

func TestGooglePlayPendingDoesNotGrant(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	store := newMemPlayStore()
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase("acc-1", "premium_monthly", now.Add(24*time.Hour), "SUBSCRIPTION_STATE_PENDING")}
	play := NewPlayBilling(testLog(t), store, client, nil, nil)
	play.now = func() time.Time { return now }
	result, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-1")
	if err != nil {
		t.Fatal(err)
	}
	if result.IsPremium || result.Acknowledge || !result.Pending {
		t.Fatalf("result = %+v", result)
	}
	if store.subs["acc-1"].Tier != model.TierFree {
		t.Fatalf("tier = %s", store.subs["acc-1"].Tier)
	}
	if store.pays["token-1"].Status != model.PaymentPending {
		t.Fatalf("status = %s", store.pays["token-1"].Status)
	}
}

func TestGooglePlayUnconfigured(t *testing.T) {
	log := testLog(t)
	svc := New(log, nil, nil, nil, nil, nil, BillingConfig{})
	if _, err := svc.VerifyGooglePlayPurchase(context.Background(), "acc", "premium_monthly", "token"); !errors.Is(err, ErrPlayNotConfigured) {
		t.Fatalf("verify err = %v", err)
	}
	if err := svc.ProcessGooglePlayNotification(context.Background(), []byte(`{}`), "Bearer x"); !errors.Is(err, ErrPlayNotConfigured) {
		t.Fatalf("rtdn err = %v", err)
	}
	svc.SetPlayBilling(NewPlayBilling(log, newMemPlayStore(), &stubPlayClient{pkg: "cloud.veritasvpn"}, nil, nil))
	if err := svc.ProcessGooglePlayNotification(context.Background(), []byte(`{}`), "Bearer x"); !errors.Is(err, ErrPlayRTDNNotConfigured) {
		t.Fatalf("rtdn without audience err = %v", err)
	}
}

func TestGooglePlayRTDNRenewsAndIgnoresTestNotification(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	expiry := now.Add(30 * 24 * time.Hour)
	store := newMemPlayStore()
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase("acc-1", "premium_monthly", expiry, "SUBSCRIPTION_STATE_ACTIVE")}
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(string, map[string]interface{}) {})
	play.now = func() time.Time { return now }

	testBody := pubSubBody(t, map[string]any{
		"version":          "1.0",
		"packageName":      "cloud.veritasvpn",
		"testNotification": map[string]string{"version": "1.0"},
	})
	if err := play.ProcessNotification(context.Background(), testBody, "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if client.calls != 0 {
		t.Fatalf("test notification called Google %d times", client.calls)
	}

	body := pubSubBody(t, map[string]any{
		"version":     "1.0",
		"packageName": "cloud.veritasvpn",
		"subscriptionNotification": map[string]any{
			"version":          "1.0",
			"notificationType": 2,
			"purchaseToken":    "token-1",
			"subscriptionId":   "premium_monthly",
		},
	})
	if err := play.ProcessNotification(context.Background(), body, "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if store.subs["acc-1"].Tier != model.TierPremium || store.paymentCount() != 1 {
		t.Fatalf("sub=%+v payments=%d", store.subs["acc-1"], store.paymentCount())
	}

	wrong := pubSubBody(t, map[string]any{
		"packageName":      "other.package",
		"testNotification": map[string]string{"version": "1.0"},
	})
	if err := play.ProcessNotification(context.Background(), wrong, "Bearer test"); err == nil {
		t.Fatal("expected package mismatch")
	}
	if err := play.ProcessNotification(context.Background(), body, ""); err == nil {
		t.Fatal("expected auth failure")
	}
}

func TestGooglePlayRTDNRevokeRefunds(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	expiry := now.Add(30 * 24 * time.Hour)
	store := newMemPlayStore()
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase("acc-1", "premium_monthly", expiry, "SUBSCRIPTION_STATE_ACTIVE")}
	var expired bool
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(subject string, payload map[string]interface{}) {
		if subject == "subscription.expired" {
			expired = true
		}
	})
	play.now = func() time.Time { return now }
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-1"); err != nil {
		t.Fatal(err)
	}
	client.purchase = activePurchase("acc-1", "premium_monthly", expiry, "SUBSCRIPTION_STATE_EXPIRED")
	body := pubSubBody(t, map[string]any{
		"packageName": "cloud.veritasvpn",
		"subscriptionNotification": map[string]any{
			"notificationType": 12,
			"purchaseToken":    "token-1",
		},
	})
	if err := play.ProcessNotification(context.Background(), body, "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if store.subs["acc-1"].Tier != model.TierFree || store.pays["token-1"].Status != model.PaymentRefunded || !expired {
		t.Fatalf("sub=%+v pay=%+v expired=%v", store.subs["acc-1"], store.pays["token-1"], expired)
	}
}

func TestPlayTrialStart(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	expiry := now.Add(7 * 24 * time.Hour)
	store := newMemPlayStore()
	purchase := &provider.SubscriptionPurchase{
		ObfuscatedAccountID: "acc-1",
		State:               "SUBSCRIPTION_STATE_ACTIVE",
		LineItems: []provider.PlayLineItem{{
			ProductID: "premium_monthly",
			Expiry:    expiry,
			InTrial:   true,
		}},
	}
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: purchase}
	var gotSubject string
	var gotPayload map[string]interface{}
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(subject string, payload map[string]interface{}) {
		gotSubject = subject
		gotPayload = payload
	})
	play.now = func() time.Time { return now }

	_, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-trial")
	if err != nil {
		t.Fatal(err)
	}

	if gotSubject != "subscription.renewed" {
		t.Fatalf("subject = %q, want subscription.renewed", gotSubject)
	}
	if trial, _ := gotPayload["trial"].(bool); !trial {
		t.Fatalf("trial flag not set in payload: %+v", gotPayload)
	}
	if store.pays["token-trial"].Amount != 0 {
		t.Fatalf("trial amount = %d, want 0", store.pays["token-trial"].Amount)
	}
	if !store.pays["token-trial"].IsTrial {
		t.Fatalf("IsTrial = false, want true")
	}
}

func TestPlayTrialConversion(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	expiry := now.Add(30 * 24 * time.Hour)
	store := newMemPlayStore()
	// First: trial
	const trialOrder = "GPA.1000-2000-3000-40000"
	const paidOrder = "GPA.1000-2000-3000-40000..0"
	trialPurchase := &provider.SubscriptionPurchase{
		ObfuscatedAccountID: "acc-1",
		LatestOrderID:       trialOrder,
		State:               "SUBSCRIPTION_STATE_ACTIVE",
		LineItems: []provider.PlayLineItem{{
			ProductID:               "premium_monthly",
			Expiry:                  now.Add(7 * 24 * time.Hour),
			InTrial:                 true,
			LatestSuccessfulOrderID: trialOrder,
		}},
	}
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: trialPurchase}
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(string, map[string]interface{}) {})
	play.now = func() time.Time { return now }

	_, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-1")
	if err != nil {
		t.Fatal(err)
	}

	// License testers renew in minutes, so the line can still say trial.
	// The new order id is what makes this a paid conversion.
	paidPurchase := &provider.SubscriptionPurchase{
		ObfuscatedAccountID: "acc-1",
		LatestOrderID:       paidOrder,
		State:               "SUBSCRIPTION_STATE_ACTIVE",
		LineItems: []provider.PlayLineItem{{
			ProductID:               "premium_monthly",
			Expiry:                  expiry,
			InTrial:                 true,
			HasPrice:                true,
			PriceCents:              300,
			Currency:                "usd",
			LatestSuccessfulOrderID: paidOrder,
		}},
	}
	client.purchase = paidPurchase
	var gotSubject string
	var gotPayload map[string]interface{}
	play.publish = func(subject string, payload map[string]interface{}) {
		gotSubject = subject
		gotPayload = payload
	}

	_, err = play.Verify(context.Background(), "acc-1", "premium_monthly", "token-1")
	if err != nil {
		t.Fatal(err)
	}

	if gotSubject != "subscription.renewed" {
		t.Fatalf("subject = %q, want subscription.renewed", gotSubject)
	}
	if converted, _ := gotPayload["converted"].(bool); !converted {
		t.Fatalf("converted flag not set in payload: %+v", gotPayload)
	}
	trial := store.pays[trialOrder]
	paid := store.pays[paidOrder]
	if trial == nil || trial.Amount != 0 || !trial.IsTrial || trial.Status != model.PaymentCompleted {
		t.Fatalf("trial row = %+v", trial)
	}
	if paid == nil || paid.Amount != 300 || paid.IsTrial || paid.Currency != "usd" || paid.Status != model.PaymentCompleted {
		t.Fatalf("paid row = %+v", paid)
	}
	if store.paymentCount() != 2 {
		t.Fatalf("payments = %d, want 2", store.paymentCount())
	}
}

func TestPlayPlainRenewal(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	expiry := now.Add(30 * 24 * time.Hour)
	store := newMemPlayStore()
	purchase := &provider.SubscriptionPurchase{
		ObfuscatedAccountID: "acc-1",
		State:               "SUBSCRIPTION_STATE_ACTIVE",
		LineItems: []provider.PlayLineItem{{
			ProductID:  "premium_monthly",
			Expiry:     expiry,
			InTrial:    false,
			HasPrice:   true,
			PriceCents: 300,
			Currency:   "usd",
		}},
	}
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: purchase}
	var gotSubject string
	var gotPayload map[string]interface{}
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(subject string, payload map[string]interface{}) {
		gotSubject = subject
		gotPayload = payload
	})
	play.now = func() time.Time { return now }

	_, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-1")
	if err != nil {
		t.Fatal(err)
	}

	if gotSubject != "subscription.renewed" {
		t.Fatalf("subject = %q, want subscription.renewed", gotSubject)
	}
	if _, ok := gotPayload["trial"]; ok {
		t.Fatalf("trial flag should not be set for plain renewal: %+v", gotPayload)
	}
	if _, ok := gotPayload["converted"]; ok {
		t.Fatalf("converted flag should not be set for plain renewal: %+v", gotPayload)
	}
}

func TestObfuscatedPlayAccountIDMatchesAndroidVector(t *testing.T) {
	got := obfuscatedPlayAccountID("acc-1")
	if got != "7032575faa823e04f6009640a1f6def620fbdbbd33201375babbc25d7537fc7d" || len(got) != 64 {
		t.Fatalf("hash = %s", got)
	}
	if obfuscatedPlayAccountID(" acc-1 ") != got {
		t.Fatal("hash should ignore surrounding space")
	}
	if !playAccountMatches("acc-1", got) || !playAccountMatches("acc-1", strings.ToUpper(got)) {
		t.Fatal("hash should match the account")
	}
	if !playAccountMatches("acc-1", "acc-1") {
		t.Fatal("legacy raw account id should still match")
	}
	if playAccountMatches("acc-1", obfuscatedPlayAccountID("other")) || playAccountMatches("acc-1", "") {
		t.Fatal("unrelated or empty binding matched")
	}
	if !isPlayAccountHash(got) || isPlayAccountHash("acc-1") {
		t.Fatal("hash detection")
	}
}

func TestGooglePlayVerifyAcceptsHashAndLegacyRawID(t *testing.T) {
	now := time.Date(2026, 10, 21, 12, 0, 0, 0, time.UTC)
	expiry := now.Add(30 * 24 * time.Hour)
	store := newMemPlayStore()
	hash := obfuscatedPlayAccountID("acc-1")
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase(hash, "premium_monthly", expiry, "SUBSCRIPTION_STATE_ACTIVE")}
	play := NewPlayBilling(testLog(t), store, client, nil, func(string, map[string]interface{}) {})
	play.now = func() time.Time { return now }
	result, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-hash")
	if err != nil || !result.IsPremium {
		t.Fatalf("hashed verify = %+v err=%v", result, err)
	}
	if _, ok := store.subs[hash]; ok {
		t.Fatal("subscription was stored under the hash")
	}
	if store.subs["acc-1"] == nil || store.subs["acc-1"].Tier != model.TierPremium {
		t.Fatalf("sub = %+v", store.subs["acc-1"])
	}

	legacy := newMemPlayStore()
	legacyClient := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase("acc-1", "premium_monthly", expiry, "SUBSCRIPTION_STATE_ACTIVE")}
	legacyPlay := NewPlayBilling(testLog(t), legacy, legacyClient, nil, func(string, map[string]interface{}) {})
	legacyPlay.now = func() time.Time { return now }
	if _, err := legacyPlay.Verify(context.Background(), "acc-1", "premium_monthly", "token-raw"); err != nil {
		t.Fatal(err)
	}
	if legacy.subs["acc-1"] == nil || !legacy.subs["acc-1"].CurrentPeriodEnd.Equal(expiry) {
		t.Fatal("legacy raw account id was rejected")
	}
}

func TestGooglePlayRTDNResolvesHashFromStoredPurchase(t *testing.T) {
	now := time.Date(2026, 10, 21, 12, 0, 0, 0, time.UTC)
	expiry := now.Add(30 * 24 * time.Hour)
	hash := obfuscatedPlayAccountID("acc-1")
	store := newMemPlayStore()
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase(hash, "premium_monthly", expiry, "SUBSCRIPTION_STATE_ACTIVE")}
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(string, map[string]interface{}) {})
	play.now = func() time.Time { return now }

	early := pubSubBody(t, map[string]any{
		"packageName": "cloud.veritasvpn",
		"subscriptionNotification": map[string]any{
			"notificationType": 4,
			"purchaseToken":    "token-hash",
			"subscriptionId":   "premium_monthly",
		},
	})
	if err := play.ProcessNotification(context.Background(), early, "Bearer test"); !errors.Is(err, ErrPlayAccountUnbound) {
		t.Fatalf("unlinked hash was acknowledged: %v", err)
	}
	if len(store.subs) != 0 {
		t.Fatalf("hash was used as an account id: %+v", store.subs)
	}

	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-hash"); err != nil {
		t.Fatal(err)
	}
	renewed := expiry.Add(30 * 24 * time.Hour)
	client.purchase = activePurchase(hash, "premium_monthly", renewed, "SUBSCRIPTION_STATE_ACTIVE")
	body := pubSubBody(t, map[string]any{
		"packageName": "cloud.veritasvpn",
		"subscriptionNotification": map[string]any{
			"notificationType": 2,
			"purchaseToken":    "token-hash",
			"subscriptionId":   "premium_monthly",
		},
	})
	if err := play.ProcessNotification(context.Background(), body, "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if !store.subs["acc-1"].CurrentPeriodEnd.Equal(renewed) {
		t.Fatalf("end = %s", store.subs["acc-1"].CurrentPeriodEnd)
	}
	if _, ok := store.subs[hash]; ok {
		t.Fatal("renewal created a subscription for the hash")
	}
}

func TestLegacyTokenRowStaysWhenRenewalOrderArrives(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	store := newMemPlayStore()
	trial := activePurchase("acc-1", "premium_monthly", now.Add(7*24*time.Hour), "SUBSCRIPTION_STATE_ACTIVE")
	trial.LineItems[0].InTrial = true
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: trial}
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(string, map[string]interface{}) {})
	play.now = func() time.Time { return now }
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-legacy"); err != nil {
		t.Fatal(err)
	}
	if store.pays["token-legacy"] == nil || !store.pays["token-legacy"].IsTrial || store.pays["token-legacy"].Amount != 0 {
		t.Fatalf("legacy trial = %+v", store.pays["token-legacy"])
	}

	const renewal = "GPA.9999-0000-1111-22222..0"
	paid := activePurchase("acc-1", "premium_monthly", now.Add(30*24*time.Hour), "SUBSCRIPTION_STATE_ACTIVE")
	paid.LatestOrderID = renewal
	paid.LineItems[0].LatestSuccessfulOrderID = renewal
	paid.LineItems[0].InTrial = true
	paid.LineItems[0].HasPrice = true
	paid.LineItems[0].PriceCents = 300
	paid.LineItems[0].Currency = "usd"
	client.purchase = paid
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-legacy"); err != nil {
		t.Fatal(err)
	}
	if store.pays["token-legacy"] == nil || !store.pays["token-legacy"].IsTrial || store.pays["token-legacy"].Amount != 0 {
		t.Fatalf("legacy trial was rewritten: %+v", store.pays["token-legacy"])
	}
	if store.pays[renewal] == nil || store.pays[renewal].Amount != 300 || store.pays[renewal].IsTrial {
		t.Fatalf("renewal = %+v", store.pays[renewal])
	}
	account, err := store.PaymentAccount(context.Background(), "token-legacy")
	if err != nil || account != "acc-1" {
		t.Fatalf("account = %q err=%v", account, err)
	}
}

func TestVoidedRenewalRefundsOnlyThatOrder(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	const token = "token-void"
	const trialOrder = "GPA.3333-4444-5555-66666"
	const paidOrder = "GPA.3333-4444-5555-66666..0"
	store := newMemPlayStore()
	trial := activePurchase("acc-1", "premium_monthly", now.Add(24*time.Hour), "SUBSCRIPTION_STATE_ACTIVE")
	trial.LatestOrderID = trialOrder
	trial.LineItems[0].LatestSuccessfulOrderID = trialOrder
	trial.LineItems[0].InTrial = true
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: trial}
	var expired int
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(subject string, _ map[string]interface{}) {
		if subject == "subscription.expired" {
			expired++
		}
	})
	play.now = func() time.Time { return now }
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", token); err != nil {
		t.Fatal(err)
	}
	paid := activePurchase("acc-1", "premium_monthly", now.Add(30*24*time.Hour), "SUBSCRIPTION_STATE_ACTIVE")
	paid.LatestOrderID = paidOrder
	paid.LineItems[0].LatestSuccessfulOrderID = paidOrder
	paid.LineItems[0].HasPrice = true
	paid.LineItems[0].PriceCents = 300
	paid.LineItems[0].Currency = "usd"
	client.purchase = paid
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", token); err != nil {
		t.Fatal(err)
	}

	revoked := activePurchase("acc-1", "premium_monthly", now.Add(-time.Hour), "SUBSCRIPTION_STATE_EXPIRED")
	revoked.LatestOrderID = paidOrder
	revoked.LineItems[0].LatestSuccessfulOrderID = paidOrder
	revoked.LineItems[0].HasPrice = true
	revoked.LineItems[0].PriceCents = 300
	revoked.LineItems[0].Currency = "usd"
	client.purchase = revoked
	body := pubSubBody(t, map[string]any{
		"packageName": "cloud.veritasvpn",
		"voidedPurchaseNotification": map[string]any{
			"purchaseToken": token,
			"orderId":       paidOrder,
			"productType":   1,
		},
	})
	if err := play.ProcessNotification(context.Background(), body, "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if store.subs["acc-1"].Tier != model.TierFree || expired != 1 {
		t.Fatalf("sub=%+v expired=%d", store.subs["acc-1"], expired)
	}
	if store.pays[trialOrder] == nil || store.pays[trialOrder].Status != model.PaymentCompleted || !store.pays[trialOrder].IsTrial {
		t.Fatalf("trial = %+v", store.pays[trialOrder])
	}
	if store.pays[paidOrder] == nil || store.pays[paidOrder].Status != model.PaymentRefunded || store.pays[paidOrder].Amount != 300 {
		t.Fatalf("refund = %+v", store.pays[paidOrder])
	}
	if err := play.ProcessNotification(context.Background(), body, "Bearer test"); err != nil {
		t.Fatalf("redelivered void was not linked: %v", err)
	}
	if store.paymentCount() != 2 {
		t.Fatalf("payments = %d, want 2", store.paymentCount())
	}
}

func TestPlayPauseRemovesPremiumUntilRestart(t *testing.T) {
	now := time.Date(2026, 10, 26, 12, 0, 0, 0, time.UTC)
	expiry := now.Add(20 * 24 * time.Hour)
	resume := now.Add(40 * 24 * time.Hour)
	store := newMemPlayStore()
	active := activePurchase("acc-1", "premium_monthly", expiry, "SUBSCRIPTION_STATE_ACTIVE")
	active.LatestOrderID = "GPA.7777"
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: active}
	var events []string
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(subject string, _ map[string]interface{}) {
		events = append(events, subject)
	})
	play.now = func() time.Time { return now }
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-pause"); err != nil {
		t.Fatal(err)
	}

	paused := activePurchase("acc-1", "premium_monthly", expiry, "SUBSCRIPTION_STATE_PAUSED")
	paused.LatestOrderID = "GPA.7777"
	paused.PauseResumesAt = &resume
	client.purchase = paused
	note := func(kind int) []byte {
		return pubSubBody(t, map[string]any{
			"packageName": "cloud.veritasvpn",
			"subscriptionNotification": map[string]any{
				"notificationType": kind,
				"purchaseToken":    "token-pause",
				"subscriptionId":   "premium_monthly",
			},
		})
	}
	if err := play.ProcessNotification(context.Background(), note(10), "Bearer test"); err != nil {
		t.Fatal(err)
	}
	sub := store.subs["acc-1"]
	premium := sub.Tier == model.TierPremium && sub.Status == model.StatusActive && now.Before(sub.CurrentPeriodEnd)
	if premium || sub.AccessState != model.AccessPaused || sub.PauseResumesAt == nil || !sub.PauseResumesAt.Equal(resume) {
		t.Fatalf("paused sub = %+v premium=%v", sub, premium)
	}
	if store.paymentCount() != 1 || store.pays["GPA.7777"].Status != model.PaymentCompleted {
		t.Fatalf("pause changed payments: %+v", store.pays)
	}
	if len(events) != 2 || events[1] != "subscription.expired" {
		t.Fatalf("events = %v", events)
	}

	later := resume.Add(24 * time.Hour)
	paused.PauseResumesAt = &later
	if err := play.ProcessNotification(context.Background(), note(11), "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if store.subs["acc-1"].AccessState != model.AccessPaused || store.subs["acc-1"].PauseResumesAt == nil || !store.subs["acc-1"].PauseResumesAt.Equal(later) {
		t.Fatalf("resume date = %+v", store.subs["acc-1"])
	}
	if len(events) != 2 {
		t.Fatalf("pause schedule redelivery emitted %v", events)
	}

	// A pause that has not started yet leaves the subscription active.
	fresh := newMemPlayStore()
	freshClient := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase("acc-1", "premium_monthly", expiry, "SUBSCRIPTION_STATE_ACTIVE")}
	freshClient.purchase.LatestOrderID = "GPA.8888"
	freshPlay := NewPlayBilling(testLog(t), fresh, freshClient, allowPush{}, func(string, map[string]interface{}) {})
	freshPlay.now = func() time.Time { return now }
	if _, err := freshPlay.Verify(context.Background(), "acc-1", "premium_monthly", "token-sched"); err != nil {
		t.Fatal(err)
	}
	if err := freshPlay.ProcessNotification(context.Background(), pubSubBody(t, map[string]any{
		"packageName": "cloud.veritasvpn",
		"subscriptionNotification": map[string]any{
			"notificationType": 11,
			"purchaseToken":    "token-sched",
			"subscriptionId":   "premium_monthly",
		},
	}), "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if fresh.subs["acc-1"].Tier != model.TierPremium || fresh.subs["acc-1"].Status != model.StatusActive || fresh.subs["acc-1"].AccessState != "" {
		t.Fatalf("scheduled pause removed premium: %+v", fresh.subs["acc-1"])
	}

	restarted := activePurchase("acc-1", "premium_monthly", expiry.Add(10*24*time.Hour), "SUBSCRIPTION_STATE_ACTIVE")
	restarted.LatestOrderID = "GPA.7777"
	client.purchase = restarted
	if err := play.ProcessNotification(context.Background(), note(7), "Bearer test"); err != nil {
		t.Fatal(err)
	}
	resumed := store.subs["acc-1"]
	if resumed.Tier != model.TierPremium || resumed.Status != model.StatusActive || resumed.AccessState != "" || resumed.PauseResumesAt != nil {
		t.Fatalf("restarted sub = %+v", resumed)
	}
	if !resumed.CurrentPeriodEnd.Equal(expiry.Add(10 * 24 * time.Hour)) {
		t.Fatalf("end = %s", resumed.CurrentPeriodEnd)
	}

	held := activePurchase("acc-1", "premium_monthly", expiry, "SUBSCRIPTION_STATE_ON_HOLD")
	held.LatestOrderID = "GPA.7777"
	client.purchase = held
	if err := play.ProcessNotification(context.Background(), note(5), "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if store.subs["acc-1"].Status != model.StatusPastDue || store.subs["acc-1"].AccessState != "" {
		t.Fatalf("hold = %+v", store.subs["acc-1"])
	}
	recovered := activePurchase("acc-1", "premium_monthly", expiry.Add(15*24*time.Hour), "SUBSCRIPTION_STATE_ACTIVE")
	recovered.LatestOrderID = "GPA.7777"
	client.purchase = recovered
	if err := play.ProcessNotification(context.Background(), note(1), "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if store.subs["acc-1"].Tier != model.TierPremium || store.subs["acc-1"].Status != model.StatusActive || store.subs["acc-1"].AccessState != "" {
		t.Fatalf("recovered = %+v", store.subs["acc-1"])
	}
}

func pubSubBody(t *testing.T, note map[string]any) []byte {
	t.Helper()
	raw, err := json.Marshal(note)
	if err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(map[string]any{
		"message": map[string]string{"data": base64.StdEncoding.EncodeToString(raw), "messageId": "1"},
	})
	if err != nil {
		t.Fatal(err)
	}
	return body
}
