#!/bin/bash
# Check for raw .message assignments to UI state in Android and Desktop code.
# This prevents leaking raw exception messages to users.
#
# Allowed patterns:
# - preflight.message, lockdownCheck.message (from our own Result types)
# - userMessage (from UserVisibleError interface)
# - VerificationRequiredError, AccountAlreadyExistsError (specific error types with safe messages)

set -e

ERRORS=0

echo "Checking for raw .message assignments to UI state..."

# Check Android UI code
if grep -rn "error = .*\.message\|statusMsg = .*\.message\|billingError = .*\.message\|shieldError = .*\.message\|deleteAccountError = .*\.message" \
    android/app/src/main/java/cloud/veritasvpn/ui/ \
    android/app/src/main/java/cloud/veritasvpn/MainActivity.kt \
    2>/dev/null | grep -v "preflight\.message\|lockdownCheck\.message\|userMessage"; then
    echo "ERROR: Found raw .message assignment in Android UI code"
    ERRORS=$((ERRORS + 1))
fi

# Check Direct StoreBilling for raw e.message
if grep -n "callbacks\.onError(e\.message" \
    android/app/src/direct/java/cloud/veritasvpn/billing/StoreBilling.kt \
    2>/dev/null; then
    echo "ERROR: Found raw e.message in Direct StoreBilling"
    ERRORS=$((ERRORS + 1))
fi

if [ $ERRORS -gt 0 ]; then
    echo ""
    echo "Found $ERRORS violation(s). Use UserFacingError.toUserMessage() or UserVisibleError.userMessage instead."
    exit 1
fi

echo "OK: No raw .message assignments found."
exit 0
