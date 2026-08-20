#!/bin/bash
# BTCPay Server store setup script
# Run this AFTER you've created an admin account at http://localhost:49392
#
# Usage: ./btcpay/setup-store.sh <btcpay_admin_email> <btcpay_admin_password>

set -e

BTCPAY_URL="http://localhost:49392"
ADMIN_EMAIL="${1:-}"
ADMIN_PASSWORD="${2:-}"
BILLING_SVC_WEBHOOK_URL="${3:-http://billing-svc:8080/api/v1/billing/webhook/btcpay}"

if [ -z "$ADMIN_EMAIL" ] || [ -z "$ADMIN_PASSWORD" ]; then
    echo "Usage: $0 <admin_email> <admin_password> [webhook_url]"
    echo ""
    echo "This script:"
    echo "  1. Authenticates as BTCPay admin"
    echo "  2. Creates a 'VeritasVPN' store"
    echo "  3. Creates an API key for the store"
    echo "  4. Creates a webhook for payment notifications"
    echo "  5. Outputs the values for your .env file"
    exit 1
fi

echo "=== BTCPay Server Store Setup ==="
echo ""

# Step 1: Authenticate and get cookie
echo "[1/5] Authenticating..."
LOGIN_RESP=$(curl -s -c /tmp/btcpay_cookies.txt -X POST "$BTCPAY_URL/api/v1/account/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")

if echo "$LOGIN_RESP" | grep -q "error\|invalid\|401\|400"; then
    echo "ERROR: Login failed. Check email/password."
    echo "Response: $LOGIN_RESP"
    exit 1
fi
echo "  -> Authenticated"

# Step 2: Create store
echo "[2/5] Creating store 'VeritasVPN'..."

# Refuse to create another identically named store. Store selection must be
# explicit once a deployment has been initialized, so network migrations do not
# silently split invoices, API keys, and webhooks across duplicates.
EXISTING_STORE_IDS=$(curl -s -b /tmp/btcpay_cookies.txt "$BTCPAY_URL/api/v1/stores" | python3 -c 'import sys,json; print("\n".join(s["id"] for s in json.load(sys.stdin) if s.get("name") == "VeritasVPN"))' 2>/dev/null) || {
    echo "ERROR: Could not safely list existing BTCPay stores."
    exit 1
}
if [ -n "$EXISTING_STORE_IDS" ]; then
    echo "ERROR: A VeritasVPN store already exists. Refusing to create a duplicate."
    echo "Select the intended existing store explicitly and update its API key and webhook configuration instead."
    exit 1
fi

STORE_RESP=$(curl -s -b /tmp/btcpay_cookies.txt -X POST "$BTCPAY_URL/api/v1/stores" \
    -H "Content-Type: application/json" \
    -d '{"name":"VeritasVPN"}')

STORE_ID=$(echo "$STORE_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
if [ -z "$STORE_ID" ]; then
    echo "ERROR: Failed to create store."
    echo "Response: $STORE_RESP"
    echo "Trying to list existing stores..."
    curl -s -b /tmp/btcpay_cookies.txt "$BTCPAY_URL/api/v1/stores" | python3 -m json.tool 2>/dev/null
    exit 1
fi
echo "  -> Store created: $STORE_ID"

# Step 3: Create API key
echo "[3/5] Creating API key..."
API_KEY_RESP=$(curl -s -b /tmp/btcpay_cookies.txt -X POST "$BTCPAY_URL/api/v1/api-keys" \
    -H "Content-Type: application/json" \
    -d "{\"label\":\"VeritasVPN API\",\"permissions\":[\"btcpay.store.cancreateinvoice:$STORE_ID\",\"btcpay.store.canviewinvoices:$STORE_ID\",\"btcpay.store.webhooks.canmodifywebhooks:$STORE_ID\"],\"storeId\":\"$STORE_ID\"}")

API_KEY=$(echo "$API_KEY_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('apiKey',''))" 2>/dev/null)
if [ -z "$API_KEY" ]; then
    echo "ERROR: Failed to create API key."
    echo "Response: $API_KEY_RESP"
    exit 1
fi
echo "  -> API key created: ${API_KEY:0:12}..."

# Step 4: Create webhook
echo "[4/5] Creating webhook..."
WEBHOOK_SECRET="whsec_$(openssl rand -hex 20 2>/dev/null || python3 -c "import secrets; print(secrets.token_hex(20))")"

WEBHOOK_RESP=$(curl -s -b /tmp/btcpay_cookies.txt -X POST "$BTCPAY_URL/api/v1/stores/$STORE_ID/webhooks" \
    -H "Content-Type: application/json" \
    -d "{\"url\":\"$BILLING_SVC_WEBHOOK_URL\",\"secret\":\"$WEBHOOK_SECRET\",\"enabled\":true,\"automaticRedelivery\":true,\"authorizedEvents\":{\"everything\":true}}")

if echo "$WEBHOOK_RESP" | grep -q "error\|invalid\|400\|500"; then
    echo "WARNING: Webhook creation may have failed."
    echo "Response: $WEBHOOK_RESP"
    echo "You can set it up manually in BTCPay Settings > Webhooks."
else
    echo "  -> Webhook created"
fi

# Step 5: Enable Bitcoin payment method
echo "[5/5] Enabling BTC payment method..."
curl -s -b /tmp/btcpay_cookies.txt -X PUT "$BTCPAY_URL/api/v1/stores/$STORE_ID/payment-methods/BTC" \
    -H "Content-Type: application/json" \
    -d '{"enabled":true}' > /dev/null
echo "  -> BTC payment method enabled"

# Cleanup
rm -f /tmp/btcpay_cookies.txt

echo ""
echo "========================================"
echo "  SETUP COMPLETE"
echo "========================================"
echo ""
echo "Add these to your btcpay/.env and services .env:"
echo ""
echo "  BTCPAY_SERVER_URL=http://localhost:49392"
echo "  BTCPAY_STORE_ID=$STORE_ID"
echo "  BTCPAY_API_KEY=$API_KEY"
echo "  BTCPAY_WEBHOOK_SECRET=$WEBHOOK_SECRET"
echo "  BTCPAY_PUBLIC_URL=https://btcpay.veritasvpn.cloud"
echo ""
echo "Test creating an invoice:"
echo "  curl -X POST http://localhost:8083/api/v1/billing/subscribe \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"account_id\":\"test\",\"tier\":\"premium\",\"payment_method\":\"btcpay\"}'"
rm -f /tmp/btcpay_cookies.txt
