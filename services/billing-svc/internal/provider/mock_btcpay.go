package provider

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
)

// MockBTCPayProvider simulates BTCPay invoices for local development.
type MockBTCPayProvider struct {
	publicBaseURL string
	mu            sync.Mutex
	invoices      map[string]MockInvoice
}

type MockInvoice struct {
	ID        string
	AccountID string
	Tier      string
	AmountUSD float64
	Settled   bool
}

func NewMockBTCPayProvider(publicBaseURL string) *MockBTCPayProvider {
	return &MockBTCPayProvider{
		publicBaseURL: publicBaseURL,
		invoices:      make(map[string]MockInvoice),
	}
}

func (m *MockBTCPayProvider) CreateInvoice(accountID, tier, paymentMethod, planID string, amountUSD float64) (string, string, error) {
	id, err := randomID(16)
	if err != nil {
		return "", "", err
	}
	m.mu.Lock()
	m.invoices[id] = MockInvoice{
		ID:        id,
		AccountID: accountID,
		Tier:      tier,
		AmountUSD: amountUSD,
	}
	m.mu.Unlock()

	checkout := fmt.Sprintf("%s/api/v1/billing/mock-checkout?invoice_id=%s", m.publicBaseURL, id)
	return id, checkout, nil
}

func (m *MockBTCPayProvider) Get(invoiceID string) (MockInvoice, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.invoices[invoiceID]
	return inv, ok
}

func (m *MockBTCPayProvider) MarkSettled(invoiceID string) (MockInvoice, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.invoices[invoiceID]
	if !ok {
		return MockInvoice{}, fmt.Errorf("invoice not found")
	}
	inv.Settled = true
	m.invoices[invoiceID] = inv
	return inv, nil
}

func randomID(nBytes int) (string, error) {
	b := make([]byte, nBytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// InvoiceCreator is implemented by real and mock BTCPay providers.
type InvoiceCreator interface {
	CreateInvoice(accountID, tier, paymentMethod, planID string, amountUSD float64) (invoiceID, checkoutURL string, err error)
}
