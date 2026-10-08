# Google Play Billing

The Play App Bundle sells Premium only through Google Play Billing. The website APK, Linux app, and website keep Bitcoin checkout through BTCPay. Play Billing is verified on the server. Nothing in this document changes Play Console by itself; the owner creates the products and credentials below.

## Which build is which

| Build | Gradle flavor | Payment | Artifact |
| --- | --- | --- | --- |
| Google Play | `play` | Google Play Billing only | `app-play-release.aab` |
| Website / sideload | `direct` | BTCPay, same as before | `app-direct-release.apk` |

`release.yml` publishes the direct APK as `veritasvpn-android.apk` and the play bundle as `veritasvpn-android.aab`. The website pin stays on the APK. Do not upload the direct APK to Play, and do not offer the Play bundle as the website download.

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

The app sets `obfuscatedAccountId` to the Veritas account id (32 hex characters) when the purchase starts. The server rejects a token that is bound to a different account.

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
| On hold, paused | Premium is removed until Google reports a recovery. |
| Expired, revoked, voided | Premium from that Play token ends, and the payment is failed or refunded. |
| Pending | No grant. The purchase stays unacknowledged. |

A repeated call with the same purchase token updates that one `payment_records` row. It does not add another period on top of Google's expiry. A longer active Bitcoin period is not shortened by an earlier Play expiry.

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
