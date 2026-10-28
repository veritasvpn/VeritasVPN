package service

import (
	"context"
	"testing"
	"time"

	"github.com/veritasvpn/services/billing-svc/internal/model"
	"github.com/veritasvpn/services/billing-svc/internal/provider"
)

const (
	voidTestToken    = "token-void-orders"
	voidInitialOrder = "GPA.3305-8757-7634-59491"
	voidRenewalOrder = "GPA.3305-8757-7634-59491..2"
)

type voidListClient struct {
	stubPlayClient
	voids   []provider.VoidedPurchase
	starts  []time.Time
	listErr error
}

func (c *voidListClient) ListVoidedPurchases(ctx context.Context, start time.Time) ([]provider.VoidedPurchase, error) {
	c.starts = append(c.starts, start)
	if c.listErr != nil {
		return nil, c.listErr
	}
	out := make([]provider.VoidedPurchase, len(c.voids))
	copy(out, c.voids)
	return out, nil
}

func seedVoidedOrders(t *testing.T, now time.Time, onEvent func(string)) (*PlayBilling, *memPlayStore, *voidListClient) {
	t.Helper()
	store := newMemPlayStore()
	client := &voidListClient{stubPlayClient: stubPlayClient{pkg: "cloud.veritasvpn"}}
	if onEvent == nil {
		onEvent = func(string) {}
	}
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(subject string, _ map[string]interface{}) {
		onEvent(subject)
	})
	play.now = func() time.Time { return now }
	for _, order := range []string{voidInitialOrder, voidRenewalOrder} {
		purchase := activePurchase("acc-1", "premium_monthly", now.Add(30*24*time.Hour), "SUBSCRIPTION_STATE_ACTIVE")
		purchase.LatestOrderID = order
		purchase.LineItems[0].LatestSuccessfulOrderID = order
		purchase.LineItems[0].HasPrice = true
		purchase.LineItems[0].PriceCents = 300
		purchase.LineItems[0].Currency = "pyg"
		client.purchase = purchase
		if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", voidTestToken); err != nil {
			t.Fatal(err)
		}
	}
	return play, store, client
}

func setVoidLookup(client *voidListClient, order, state string, expiry time.Time) {
	purchase := activePurchase("acc-1", "premium_monthly", expiry, state)
	purchase.LatestOrderID = order
	purchase.LineItems[0].LatestSuccessfulOrderID = order
	purchase.LineItems[0].HasPrice = true
	purchase.LineItems[0].PriceCents = 300
	purchase.LineItems[0].Currency = "pyg"
	client.purchase = purchase
	client.err = nil
}

func TestVoidedNotificationRefundsInitialOrderAndRenewal(t *testing.T) {
	now := time.Date(2026, 10, 27, 23, 48, 0, 0, time.UTC)
	cases := []struct {
		name        string
		order       string
		state       string
		expiry      time.Time
		productType any
		wantPremium bool
	}{
		{
			name:        "initial order without revoke",
			order:       voidInitialOrder,
			state:       "SUBSCRIPTION_STATE_ACTIVE",
			expiry:      now.Add(30 * 24 * time.Hour),
			productType: 1,
			wantPremium: true,
		},
		{
			name:        "renewal suffix without revoke",
			order:       voidRenewalOrder,
			state:       "SUBSCRIPTION_STATE_ACTIVE",
			expiry:      now.Add(30 * 24 * time.Hour),
			productType: 1,
			wantPremium: true,
		},
		{
			name:        "initial order with revoke",
			order:       voidInitialOrder,
			state:       "SUBSCRIPTION_STATE_EXPIRED",
			expiry:      now.Add(-time.Hour),
			productType: 1,
			wantPremium: false,
		},
		{
			name:        "renewal suffix with revoke",
			order:       voidRenewalOrder,
			state:       "SUBSCRIPTION_STATE_EXPIRED",
			expiry:      now.Add(-time.Hour),
			productType: 1,
			wantPremium: false,
		},
		{
			name:        "renewal suffix product type omitted",
			order:       voidRenewalOrder,
			state:       "SUBSCRIPTION_STATE_ACTIVE",
			expiry:      now.Add(30 * 24 * time.Hour),
			wantPremium: true,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var expired int
			play, store, client := seedVoidedOrders(t, now, func(subject string) {
				if subject == "subscription.expired" {
					expired++
				}
			})
			setVoidLookup(client, voidRenewalOrder, tc.state, tc.expiry)
			note := map[string]any{
				"purchaseToken": voidTestToken,
				"orderId":       tc.order,
			}
			if tc.productType != nil {
				note["productType"] = tc.productType
			}
			body := pubSubBody(t, map[string]any{
				"packageName":                "cloud.veritasvpn",
				"voidedPurchaseNotification": note,
			})
			if err := play.ProcessNotification(context.Background(), body, "Bearer test"); err != nil {
				t.Fatal(err)
			}
			assertVoidedOrder(t, store, now, tc.order, tc.wantPremium, expired)
			if err := play.ProcessNotification(context.Background(), body, "Bearer test"); err != nil {
				t.Fatal(err)
			}
			if store.paymentCount() != 2 {
				t.Fatalf("payments = %d, want 2", store.paymentCount())
			}
			assertVoidedOrder(t, store, now, tc.order, tc.wantPremium, expired)
		})
	}
}

func TestVoidedPurchaseSyncRefundsInitialOrderAndRenewal(t *testing.T) {
	now := time.Date(2026, 10, 27, 23, 48, 0, 0, time.UTC)
	cases := []struct {
		name        string
		order       string
		state       string
		expiry      time.Time
		wantPremium bool
	}{
		{"initial order without revoke", voidInitialOrder, "SUBSCRIPTION_STATE_ACTIVE", now.Add(30 * 24 * time.Hour), true},
		{"renewal suffix without revoke", voidRenewalOrder, "SUBSCRIPTION_STATE_ACTIVE", now.Add(30 * 24 * time.Hour), true},
		{"initial order with revoke", voidInitialOrder, "SUBSCRIPTION_STATE_EXPIRED", now.Add(-time.Hour), false},
		{"renewal suffix with revoke", voidRenewalOrder, "SUBSCRIPTION_STATE_EXPIRED", now.Add(-time.Hour), false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var expired int
			play, store, client := seedVoidedOrders(t, now, func(subject string) {
				if subject == "subscription.expired" {
					expired++
				}
			})
			store.pays["btcpay-invoice"] = &model.PaymentRecord{
				ID:                    "pay-btc",
				AccountID:             "acc-1",
				Provider:              model.PaymentBTCPay,
				ProviderTransactionID: "btcpay-invoice",
				Status:                model.PaymentCompleted,
				Amount:                3000,
				Currency:              "usd",
			}
			setVoidLookup(client, voidRenewalOrder, tc.state, tc.expiry)
			client.voids = []provider.VoidedPurchase{{
				OrderID:       tc.order,
				PurchaseToken: voidTestToken,
			}}
			beforeCalls := client.calls
			n, err := play.SyncVoidedPurchases(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			if n != 1 {
				t.Fatalf("recorded = %d, want 1", n)
			}
			if len(client.starts) != 1 || !client.starts[0].Equal(now.Add(-provider.VoidedPurchaseLookback)) {
				t.Fatalf("start = %v", client.starts)
			}
			assertVoidedOrder(t, store, now, tc.order, tc.wantPremium, expired)
			if btc := store.pays["btcpay-invoice"]; btc == nil || btc.Status != model.PaymentCompleted || btc.Amount != 3000 {
				t.Fatalf("bitcoin payment changed: %+v", btc)
			}

			n, err = play.SyncVoidedPurchases(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			if n != 0 {
				t.Fatalf("second sync recorded %d", n)
			}
			if client.calls != beforeCalls+1 {
				t.Fatalf("subscription lookups = %d, want %d", client.calls, beforeCalls+1)
			}
			if store.paymentCount() != 3 {
				t.Fatalf("payments = %d, want 3", store.paymentCount())
			}
			assertVoidedOrder(t, store, now, tc.order, tc.wantPremium, expired)
		})
	}
}

func TestVoidedPurchaseSyncLeavesAnActiveBitcoinSubscription(t *testing.T) {
	now := time.Date(2026, 10, 27, 23, 48, 0, 0, time.UTC)
	play, store, client := seedVoidedOrders(t, now, nil)
	bitcoinEnd := now.Add(90 * 24 * time.Hour)
	sub := store.subs["acc-1"]
	sub.Tier = model.TierPremium
	sub.Status = model.StatusActive
	sub.PaymentMethod = model.PaymentBTCPay
	sub.PlanID = "annual"
	sub.ExternalRef = ""
	sub.CurrentPeriodEnd = bitcoinEnd
	client.err = provider.ErrPlayPurchaseNotFound
	client.voids = []provider.VoidedPurchase{{
		OrderID:       voidRenewalOrder,
		PurchaseToken: voidTestToken,
	}}
	n, err := play.SyncVoidedPurchases(context.Background())
	if err != nil || n != 1 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	got := store.subs["acc-1"]
	if got.PaymentMethod != model.PaymentBTCPay || got.Tier != model.TierPremium || !got.CurrentPeriodEnd.Equal(bitcoinEnd) {
		t.Fatalf("bitcoin subscription changed: %+v", got)
	}
	if store.pays[voidRenewalOrder].Status != model.PaymentRefunded || store.pays[voidInitialOrder].Status != model.PaymentCompleted {
		t.Fatalf("play rows = initial %+v renewal %+v", store.pays[voidInitialOrder], store.pays[voidRenewalOrder])
	}
}

func TestVoidedPurchaseSyncRevokesWhenPlayNoLongerReturnsThePurchase(t *testing.T) {
	now := time.Date(2026, 10, 27, 23, 48, 0, 0, time.UTC)
	play, store, client := seedVoidedOrders(t, now, nil)
	client.err = provider.ErrPlayPurchaseNotFound
	client.voids = []provider.VoidedPurchase{{
		OrderID:       voidRenewalOrder,
		PurchaseToken: voidTestToken,
	}}
	n, err := play.SyncVoidedPurchases(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("recorded = %d, want 1", n)
	}
	if store.subs["acc-1"].Tier != model.TierFree {
		t.Fatalf("sub = %+v", store.subs["acc-1"])
	}
	if store.pays[voidRenewalOrder].Status != model.PaymentRefunded || store.pays[voidRenewalOrder].Amount != 300 || store.pays[voidRenewalOrder].Currency != "pyg" {
		t.Fatalf("renewal = %+v", store.pays[voidRenewalOrder])
	}
	if store.pays[voidInitialOrder].Status != model.PaymentCompleted {
		t.Fatalf("initial = %+v", store.pays[voidInitialOrder])
	}
}

func TestVoidedPurchaseSyncIgnoresUnknownTokensAndListErrors(t *testing.T) {
	now := time.Date(2026, 10, 28, 0, 0, 0, 0, time.UTC)
	store := newMemPlayStore()
	client := &voidListClient{
		stubPlayClient: stubPlayClient{pkg: "cloud.veritasvpn", err: provider.ErrPlayPurchaseNotFound},
		voids: []provider.VoidedPurchase{{
			OrderID:       "GPA.9999-0000-0000-00000..4",
			PurchaseToken: "not-our-token",
		}},
	}
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(string, map[string]interface{}) {})
	play.now = func() time.Time { return now }
	n, err := play.SyncVoidedPurchases(context.Background())
	if err != nil || n != 0 || store.paymentCount() != 0 {
		t.Fatalf("n=%d err=%v payments=%d", n, err, store.paymentCount())
	}
	if _, err := play.SyncVoidedPurchases(context.Background()); err != nil {
		t.Fatal(err)
	}
	if client.calls != 1 {
		t.Fatalf("lookups = %d, want 1", client.calls)
	}

	client.listErr = errString("google play voided purchases returned 503")
	client.voidedReset(play)
	if _, err := play.SyncVoidedPurchases(context.Background()); err == nil {
		t.Fatal("expected list failure")
	}
	if store.paymentCount() != 0 {
		t.Fatalf("list failure wrote payments: %d", store.paymentCount())
	}
}

func (c *voidListClient) voidedReset(play *PlayBilling) {
	play.voidedMu.Lock()
	play.voidedApplied = nil
	play.voidedMu.Unlock()
}

type errString string

func (e errString) Error() string { return string(e) }

type orderRefundClient struct {
	voidListClient
	refunded map[string]bool
	checked  []string
}

func (c *orderRefundClient) OrderRefunded(ctx context.Context, orderID string) (provider.PlayOrderStatus, error) {
	c.checked = append(c.checked, orderID)
	return provider.PlayOrderStatus{Refunded: c.refunded[orderID]}, nil
}

func TestDecodeVoidedNoticeDoesNotDropASubscriptionRefund(t *testing.T) {
	body := pubSubBody(t, map[string]any{
		"packageName": "cloud.veritasvpn",
		"voidedPurchaseNotification": map[string]any{
			"purchaseToken": "token-1",
			"orderId":       voidRenewalOrder,
			"productType":   1,
			"refundType":    1,
		},
	})
	note, err := decodeDeveloperNotification(body)
	if err != nil {
		t.Fatal(err)
	}
	if note.Test || note.IgnoreReason != "" || note.PurchaseToken != "token-1" || note.RefundOrderID != voidRenewalOrder || note.ProductType != 1 || note.RefundType != 1 {
		t.Fatalf("note = %+v", note)
	}
	if note.ForceRevoke {
		t.Fatal("a voided notice is not a revoke by itself")
	}
}

func TestDecodeEarlyReturnsNameTheBranch(t *testing.T) {
	cases := []struct {
		name   string
		note   map[string]any
		reason string
		order  string
		ptype  int
	}{
		{
			name: "one-time product type",
			note: map[string]any{"voidedPurchaseNotification": map[string]any{
				"purchaseToken": "token-1", "orderId": voidInitialOrder, "productType": 2, "refundType": 1,
			}},
			reason: "unsupported_product_type",
			order:  voidInitialOrder,
			ptype:  2,
		},
		{
			name: "voided notice without a token",
			note: map[string]any{"voidedPurchaseNotification": map[string]any{
				"orderId": voidRenewalOrder, "productType": 1,
			}},
			reason: "missing_purchase_token",
			order:  voidRenewalOrder,
			ptype:  1,
		},
		{
			name:   "one-time product notice",
			note:   map[string]any{"oneTimeProductNotification": map[string]any{"notificationType": 2, "purchaseToken": "token-1"}},
			reason: "one_time_product",
		},
		{
			name:   "pending refund review",
			note:   map[string]any{"pendingRefundReviewNotification": map[string]any{"orderId": voidRenewalOrder, "pendingRefundToken": "secret-token"}},
			reason: "pending_refund_review",
			order:  voidRenewalOrder,
		},
		{
			name:   "empty developer notification",
			note:   map[string]any{"packageName": "cloud.veritasvpn"},
			reason: "unrecognized_notification",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			raw := tc.note
			raw["packageName"] = "cloud.veritasvpn"
			note, err := decodeDeveloperNotification(pubSubBody(t, raw))
			if err != nil {
				t.Fatal(err)
			}
			if note.Test || note.IgnoreReason != tc.reason || note.RefundOrderID != tc.order || note.ProductType != tc.ptype {
				t.Fatalf("note = %+v", note)
			}
			if note.PurchaseToken != "" {
				t.Fatal("ignored notice kept a purchase token")
			}
		})
	}
}

func TestUnsupportedVoidedProductTypeDoesNotChangeThePayment(t *testing.T) {
	now := time.Date(2026, 10, 27, 23, 48, 0, 0, time.UTC)
	play, store, client := seedVoidedOrders(t, now, nil)
	setVoidLookup(client, voidRenewalOrder, "SUBSCRIPTION_STATE_ACTIVE", now.Add(30*24*time.Hour))
	before := client.calls
	body := pubSubBody(t, map[string]any{
		"packageName": "cloud.veritasvpn",
		"voidedPurchaseNotification": map[string]any{
			"purchaseToken": voidTestToken,
			"orderId":       voidRenewalOrder,
			"productType":   2,
			"refundType":    1,
		},
	})
	if err := play.ProcessNotification(context.Background(), body, "Bearer test"); err != nil {
		t.Fatal(err)
	}
	if client.calls != before {
		t.Fatal("unsupported product type still looked up the subscription")
	}
	if store.pays[voidRenewalOrder].Status != model.PaymentCompleted || store.pays[voidInitialOrder].Status != model.PaymentCompleted {
		t.Fatalf("rows changed: initial %+v renewal %+v", store.pays[voidInitialOrder], store.pays[voidRenewalOrder])
	}
}

func TestOrderRefundWithoutRevokeMarksOnlyThatRenewal(t *testing.T) {
	now := time.Date(2026, 10, 27, 23, 48, 0, 0, time.UTC)
	store := newMemPlayStore()
	client := &orderRefundClient{voidListClient: voidListClient{stubPlayClient: stubPlayClient{pkg: "cloud.veritasvpn"}}}
	var expired int
	play := NewPlayBilling(testLog(t), store, client, allowPush{}, func(subject string, _ map[string]interface{}) {
		if subject == "subscription.expired" {
			expired++
		}
	})
	play.now = func() time.Time { return now }
	for _, order := range []string{voidInitialOrder, voidRenewalOrder} {
		purchase := activePurchase("acc-1", "premium_monthly", now.Add(30*24*time.Hour), "SUBSCRIPTION_STATE_ACTIVE")
		purchase.LatestOrderID = order
		purchase.LineItems[0].LatestSuccessfulOrderID = order
		purchase.LineItems[0].HasPrice = true
		purchase.LineItems[0].PriceCents = 300
		purchase.LineItems[0].Currency = "pyg"
		client.purchase = purchase
		if _, err := play.Verify(context.Background(), "acc-1", "premium_monthly", voidTestToken); err != nil {
			t.Fatal(err)
		}
	}
	store.pays["btcpay-invoice"] = &model.PaymentRecord{
		ID: "pay-btc", AccountID: "acc-1", Provider: model.PaymentBTCPay,
		ProviderTransactionID: "btcpay-invoice", Status: model.PaymentCompleted, Amount: 3000,
	}
	client.refunded = map[string]bool{voidRenewalOrder: true}
	n, err := play.SyncVoidedPurchases(context.Background())
	if err != nil || n != 1 {
		t.Fatalf("n=%d err=%v checked=%v", n, err, client.checked)
	}
	assertVoidedOrder(t, store, now, voidRenewalOrder, true, expired)
	if store.pays["btcpay-invoice"].Status != model.PaymentCompleted {
		t.Fatalf("bitcoin payment changed: %+v", store.pays["btcpay-invoice"])
	}
	checked := append([]string(nil), client.checked...)
	n, err = play.SyncVoidedPurchases(context.Background())
	if err != nil || n != 0 {
		t.Fatalf("second n=%d err=%v", n, err)
	}
	for _, id := range client.checked[len(checked):] {
		if id == voidRenewalOrder {
			t.Fatal("refunded renewal was checked again")
		}
	}
	assertVoidedOrder(t, store, now, voidRenewalOrder, true, expired)
}

func assertVoidedOrder(t *testing.T, store *memPlayStore, now time.Time, refundedOrder string, wantPremium bool, expired int) {
	t.Helper()
	sub := store.subs["acc-1"]
	premium := sub.Tier == model.TierPremium && sub.Status == model.StatusActive && now.Before(sub.CurrentPeriodEnd)
	if premium != wantPremium {
		t.Fatalf("premium=%v want %v sub=%+v", premium, wantPremium, sub)
	}
	if wantPremium && expired != 0 {
		t.Fatalf("refund removed premium, expired=%d", expired)
	}
	if !wantPremium && (sub.Tier != model.TierFree || expired == 0) {
		t.Fatalf("revoke did not drop premium: sub=%+v expired=%d", sub, expired)
	}
	refunded := store.pays[refundedOrder]
	if refunded == nil || refunded.Status != model.PaymentRefunded || refunded.Amount != 300 || refunded.Currency != "pyg" {
		t.Fatalf("refunded order = %+v", refunded)
	}
	other := voidInitialOrder
	if refundedOrder == voidInitialOrder {
		other = voidRenewalOrder
	}
	if store.pays[other] == nil || store.pays[other].Status != model.PaymentCompleted {
		t.Fatalf("other order changed: %+v", store.pays[other])
	}
}
