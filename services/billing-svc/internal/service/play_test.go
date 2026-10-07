package service

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
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
	if payment := m.pays[token]; payment != nil {
		return payment.AccountID, nil
	}
	return "", nil
}

func (m *memPlayStore) UpdateGooglePlay(ctx context.Context, accountID, token string, apply func(sub *model.Subscription, existing *model.PaymentRecord) (*model.PaymentRecord, string, error)) (string, error) {
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
	existing := m.pays[token]
	if existing != nil && existing.AccountID != "" && existing.AccountID != accountID {
		return "", repository.ErrPurchaseAccountConflict
	}
	var existingCopy *model.PaymentRecord
	if existing != nil {
		cp := *existing
		existingCopy = &cp
	}
	payment, event, err := apply(sub, existingCopy)
	if err != nil {
		return "", err
	}
	if existing != nil {
		payment.ID = existing.ID
		payment.CreatedAt = existing.CreatedAt
	} else {
		payment.ID = "pay-" + token
		if payment.CreatedAt.IsZero() {
			payment.CreatedAt = time.Now().UTC()
		}
	}
	payment.ProviderTransactionID = token
	payment.AccountID = accountID
	payment.Provider = model.PaymentGooglePlay
	m.pays[token] = payment
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

func TestGooglePlayRenewalMovesExpiryWithoutASecondPayment(t *testing.T) {
	now := time.Date(2026, 10, 7, 12, 0, 0, 0, time.UTC)
	firstEnd := now.Add(30 * 24 * time.Hour)
	store := newMemPlayStore()
	client := &stubPlayClient{pkg: "cloud.veritasvpn", purchase: activePurchase("acc-1", "premium_monthly", firstEnd, "SUBSCRIPTION_STATE_ACTIVE")}
	play := NewPlayBilling(testLog(t), store, client, nil, func(string, map[string]interface{}) {})
	play.now = func() time.Time { return now }
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-1"); err != nil {
		t.Fatal(err)
	}
	renewedEnd := firstEnd.Add(30 * 24 * time.Hour)
	client.purchase = activePurchase("acc-1", "premium_monthly", renewedEnd, "SUBSCRIPTION_STATE_ACTIVE")
	if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", "token-1"); err != nil {
		t.Fatal(err)
	}
	if !store.subs["acc-1"].CurrentPeriodEnd.Equal(renewedEnd) {
		t.Fatalf("end = %s, want %s", store.subs["acc-1"].CurrentPeriodEnd, renewedEnd)
	}
	if store.paymentCount() != 1 {
		t.Fatalf("payments = %d, want 1", store.paymentCount())
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
		{name: "expired", state: "SUBSCRIPTION_STATE_EXPIRED", wantTier: model.TierFree, wantState: model.StatusActive, wantPay: model.PaymentFailed},
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
			_, err := play.apply(context.Background(), "acc-1", "premium_annual", "token-1", client.purchase, tc.revoke)
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
