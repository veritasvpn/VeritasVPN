# Google Play Billing

The Play App Bundle sells Premium only through Google Play Billing. The website APK, Linux app, and website keep Bitcoin checkout through BTCPay. Play Billing is verified on the server. Nothing in this document changes Play Console by itself; the owner creates the products and credentials below.

## Which build is which

| Build | Gradle flavor | Payment | Artifact |
| --- | --- | --- | --- |
| Google Play | `play` | Google Play Billing only | `app-play-release.aab` |
| Website / sideload | `direct` | BTCPay, same as before | `app-direct-release.apk` |

`release.yml` publishes the direct APK as `veritasvpn-android.apk` and the play bundle as `veritasvpn-android.aab`. The website pin stays on the APK. The site’s primary Android button links to the Play listing (`https://play.google.com/store/apps/details?id=cloud.veritasvpn`). The direct APK remains the sideload link on the Android install page. Do not upload the direct APK to Play, and do not offer the Play bundle as the website download.

Both flavors use application id `cloud.veritasvpn`. They are not side-by-side installs.

### Build the Play App Bundle

From `android/`, with the existing `VERITAS_RELEASE_*` signing properties:

```bash
./gradlew :app:bundlePlayRelease
```

The bundle is `android/app/build/outputs/bundle/playRelease/app-play-release.aab`.

The website APK is:

```bash
./gradlew :app:assembleDirectRelease
```

Output: `android/app/build/outputs/apk/direct/release/app-direct-release.apk`.

Debug builds of both flavors:

```bash
./gradlew test assemblePlayDebug assembleDirectDebug
```

The play flavor never compiles the BTCPay checkout screen or the Play Billing client into the other flavor. `com.android.vending.BILLING` is merged only into the play manifest. The Play Billing Library is `com.android.billingclient:billing:9.1.0` (latest stable). The Kotlin extensions artifact is built with a newer Kotlin metadata version than this app's compiler, so the play flavor calls the Java library directly.

## Subscription products to create

Create these in Play Console → Monetize → Products → Subscriptions. Do not create one-time products. Prices match the plans already in `services/billing-svc/internal/model/plans.go` and `android/app/src/main/java/cloud/veritasvpn/billing/PlayCatalog.kt`.

Play bills a calendar month or year. BTCPay still uses 30 days and 365 days. That difference is intentional.

| Subscription product ID | Base plan ID | Billing period | Price | Veritas plan id |
| --- | --- | --- | --- | --- |
| `premium_monthly` | `monthly` | 1 month, auto-renewing | USD 3.00 | `premium_monthly` |
| `premium_annual` | `yearly` | 1 year, auto-renewing | USD 30.00 | `premium_annual` |

For each base plan:

1. Type: auto-renewing. No prepaid base plan is required.
2. Activate the base plan. An inactive base plan makes the app report that the subscription is not available.
3. A free trial or intro offer is optional. The app uses the base-plan offer (the offer with no offer id) when one exists.
4. Turn on a grace period and account hold if you want those states. The server already updates entitlements when Google reports them.

The app sets `obfuscatedAccountId` to a 64-character HMAC-SHA256 of the Account ID (domain separator `veritasvpn-play-account-v1`, shared with billing-svc). That value is not the Account ID and cannot be used to sign in. The server accepts that hash and, for purchases already stored by older app versions, the raw Account ID. It rejects a token bound to a different account. Real-time notifications for a hashed purchase resolve the account from the stored purchase token; the hash is not used as an account id. billing-svc does not cancel or revoke the Play subscription when the account is deleted.

## Free trials

Google Play supports free trial periods for subscriptions. Veritas uses a 7-day free trial offer to attract new subscribers.

### Setting up the trial offer

In Play Console → Monetize → Products → Subscriptions → `premium_monthly` (or `premium_annual`):

1. Create an **offer** with offer ID `free-trial-7d`.
2. Add a pricing phase:
   - Duration: 7 days
   - Price: Free (0)
   - Type: Free trial
3. Add the tag `trial` to the offer (used for detection).
4. Activate the offer.

The app detects the trial by checking for the `trial` tag or the presence of `trial` in the offer ID. Users who haven't previously used a trial on any Veritas subscription are eligible.

### Trial eligibility

- One trial per Google account (enforced by Google Play)
- One trial per Veritas account (enforced by the server)
- A user who has already used a trial, even on a different plan, is not eligible again

The server checks eligibility by looking for any `payment_records` row with `is_trial=true` for that account. If found, the app hides the trial offer and shows the regular subscription button.

### Trial lifecycle

1. **Trial start**: User subscribes with the trial offer. Google charges nothing for 7 days. The server receives a subscription update with `InTrial=true`. It creates a `payment_records` row with `amount=0`, `is_trial=true`, and publishes a `subscription.renewed` event with `trial:true` in the payload. The user gets Premium immediately.

2. **Trial conversion**: Google charges the regular price on the next order (`GPA.xxxx..0`), including a license-tester renewal that happens in minutes while the trial offer is still on the line item. The server inserts a second completed `payment_records` row for that order id, with Google's price and currency, leaves the trial row at amount 0, and publishes `subscription.renewed` with `converted:true`. The same order id is updated in place if verify and RTDN both see it.

3. **Cancellation during trial**: If the user cancels before the trial ends, Google stops the subscription at the trial expiry. The server receives a cancellation notification and sets `cancel_at_period_end=true`. Premium continues until the trial ends, then expires.

4. **Expired trial**: If the user cancels during the trial, or if the payment fails or is declined when the trial ends, Google ends the subscription. The server removes Premium. A successful charge stays completed. A voided purchase or `SUBSCRIPTION_REVOKED` marks that order refunded.

### Configuration

The trial duration is configurable via the `PLAY_TRIAL_DAYS` environment variable (default: 7). This is used to detect whether a subscription is still in the trial phase. For license testers with shortened renewal cycles, trials last only minutes, and conversion is detected from the next order regardless. Set `PLAY_TRIAL_DAYS=0` to disable trial detection entirely.

### Telegram notifications

The telegram-notifier service sends different messages based on the payload flags:

- `trial:true` → "Free trial started (Google Play); subscription activated"
- `converted:true` → "Trial converted to paid (Google Play); subscription activated"
- Neither flag → "Premium payment received (Google Play); subscription activated"

## License testers

Play Console → Settings → License testing (or Setup → License testing):

1. Add the Gmail addresses that will install the Play build.
2. License testers can subscribe without a real charge. The Play purchase UI says the order is a test.
3. Test subscriptions renew on Google's shortened schedule (a monthly plan renews in about five minutes, a yearly plan in about thirty). Cancel from the Play subscription management page, or use the in-app Manage subscription button.
4. Install the play build from an internal, closed, or open testing track, or from Play Console's internal app sharing. A sideloaded direct APK will not show Play Billing.
5. After a test purchase, the Account screen should show Premium and a Google Play row in purchase history. Restore is automatic on launch and sign-in.

## Service account and API access

The billing server calls `purchases.subscriptionsv2.get`:

`GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/cloud.veritasvpn/purchases/subscriptionsv2/tokens/{token}`

1. In Google Cloud Console, create or choose a project and enable **Google Play Android Developer API**.
2. Create a service account. Create a JSON key. Store that file only on the billing server. Do not commit it.
3. Play Console → Users and permissions → Invite new users. Invite the service account email.
4. Grant these permissions, as named by Google's Play Developer API setup:
   - View financial data, orders, and cancellation survey responses
   - Manage orders and subscriptions
5. Scope used by the server: `https://www.googleapis.com/auth/androidpublisher`.

The app acknowledges a purchase only after the server accepts it. Pending Play purchases are not acknowledged and do not grant Premium.

## Real-time Developer Notifications

Renewals, cancellations, expirations, refunds, grace, and account hold are applied when Pub/Sub pushes a notification. The app also re-checks purchases on launch, which covers a missed push.

1. In the same Google Cloud project, create a Pub/Sub topic, for example `play-rtdn`.
2. Grant `google-play-developer-notifications@system.gserviceaccount.com` the **Pub/Sub Publisher** role on that topic.
3. Create a push subscription on the topic.
   - Endpoint: `https://api.veritasvpn.cloud/api/v1/billing/webhook/google-play`
   - Enable authentication. Choose a push service account (it can be the same API service account or a dedicated one).
   - Audience: the same URL, `https://api.veritasvpn.cloud/api/v1/billing/webhook/google-play`
4. Play Console → Monetize → Monetization setup → Real-time developer notifications. Set the topic name (`projects/PROJECT_ID/topics/play-rtdn`) and send a test notification. The server answers 200 for a test notification once RTDN auth is configured.
5. The server checks the push bearer token against Google's OIDC certificates. `email` must match `GOOGLE_PLAY_RTDN_PUSH_SERVICE_ACCOUNT` when that variable is set, and `aud` must match `GOOGLE_PLAY_RTDN_AUDIENCE`.

Notification handling, after `purchases.subscriptionsv2.get`:

| Google state or notice | Entitlement |
| --- | --- |
| Active, canceled (still inside the period), in grace | Premium until Google's expiry. Canceled sets cancel-at-period-end. |
| Pause scheduled (`PAUSE_SCHEDULE_CHANGED` while still active) | Premium stays. The pause has not started. |
| Paused | Premium is removed. The account screen says the subscription is paused and when it resumes. `SUBSCRIPTION_RESTARTED` restores Premium. |
| On hold | Premium is removed until `SUBSCRIPTION_RECOVERED`. |
| Expired | Premium from that Play token ends. Completed charges stay completed. |
| `SUBSCRIPTION_REVOKED` | Premium ends, even if the fresh lookup still says active. The latest order row is marked refunded. |
| Voided purchase of the latest order, or a void while the subscription is not active or in grace | Premium ends. That order row is marked refunded. |
| Voided purchase of an older order while Google still reports active or in grace | That order is marked refunded. Premium stays. |
| Pending | No grant. The purchase stays unacknowledged. |

Each successful Play order id (`GPA.xxxx`, then `GPA.xxxx..0`, `GPA.xxxx..1`, …) is one `payment_records` row. A repeated notification for the same order updates that row. It does not add another period on top of Google's expiry. A subscription that already renewed on an older build has that charge on the purchase-token row. The first time the current order id is seen, that row is re-pointed to the order id instead of inserting a second history line, and it is not reported as another trial conversion. A token-keyed trial row is left in place when the paid renewal is a different charge. A longer active Bitcoin period is not shortened by an earlier Play expiry. The purchase token stays on `purchase_token` (and on `subscriptions.external_ref` while that Play subscription is current) so an RTDN can find the account. An RTDN for a token that is not linked yet is not acknowledged, and Pub/Sub retries.

## Environment on the Dell deployment

Leave these unset until the service account and Pub/Sub subscription exist. billing-svc still starts, Bitcoin checkout is unchanged, and the Play endpoints return `503` with `Google Play billing is not configured` or `Google Play real-time developer notifications are not configured`.

The billing pod runs as uid 65532 with a read-only root filesystem. Mount the JSON key; do not bake it into the image.

```yaml
env:
  - name: GOOGLE_PLAY_PACKAGE_NAME
    value: cloud.veritasvpn
  - name: GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
    value: /var/run/google-play/service-account.json
  - name: GOOGLE_PLAY_RTDN_AUDIENCE
    value: https://api.veritasvpn.cloud/api/v1/billing/webhook/google-play
  - name: GOOGLE_PLAY_RTDN_PUSH_SERVICE_ACCOUNT
    value: play-rtdn-push@PROJECT_ID.iam.gserviceaccount.com
volumeMounts:
  - name: google-play
    mountPath: /var/run/google-play
    readOnly: true
volumes:
  - name: google-play
    secret:
      secretName: google-play-billing
      items:
        - key: service-account.json
          path: service-account.json
```

Create the secret from the JSON key:

```bash
kubectl -n veritas create secret generic google-play-billing \
  --from-file=service-account.json=./play-service-account.json
```

`billing-svc` already has egress to public TCP 443, which covers `androidpublisher.googleapis.com` and `oauth2.googleapis.com`. The webhook is under `/api/v1/billing/`, which nginx already proxies. No image rollout is required for the endpoints to exist; they stay disabled until the variables are set and the pod is restarted with the mount.

Migration `008_google_play.sql` adds `google_play` as a subscription payment method, `payment_records.provider`, and `subscriptions.external_ref` (the active purchase token, never returned to the app). Existing rows stay `provider=btcpay`.

## How to test with a license tester

1. Create and activate the two subscriptions and base plans above.
2. Put the tester's Gmail address in license testing.
3. Upload `app-play-release.aab` to an internal testing track and install it from the Play Store opt-in link. Do not sideload the website APK for this test.
4. Sign in to Veritas, open Account, choose Monthly or Annual, and tap **Subscribe with Google Play**.
5. Confirm the Play sheet shows a test order. After it completes, Account shows Premium and purchase history shows a Google Play row (`$3` monthly or `$30` annual).
6. Force-stop and reopen the app, and sign out and back in. Premium should restore without a second charge.
7. Tap **Manage subscription**. It opens Google Play's subscription page for `cloud.veritasvpn`. Cancel there. Premium remains until the test expiry, with cancellation scheduled.
8. Send the RTDN test notification from Play Console. billing-svc should log it and return 200. A renewal on the shortened test clock should extend `current_period_end` without a second payment row for the same token.
9. On a device without Play, or before the products are active, the subscribe button should report that billing or the subscription is unavailable. The rest of the app still runs.
10. Install the direct APK from the website and confirm it still offers Bitcoin checkout and does not show Subscribe with Google Play.
