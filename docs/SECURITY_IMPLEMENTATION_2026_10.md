# October security fixes: implementation and verification plan

Baseline: master 96f875e, including the owner's October 4 Linux desktop changes.
Work branch: codex/security-audit-fixes-20261004. Existing dirty checkouts must
remain untouched. No OS upgrade/reboot, password rotation or provider purchase.

## 1. Network isolation (V-01/V-02)

- Apply VPN source/destination isolation before established-flow and CNI accepts;
  restrict CNI exceptions to non-VPN traffic. Preserve Internet return traffic.
- Share an outbound public-address policy between proxy and phishing checker.
  Deny private, loopback, CGNAT/Tailscale, documentation, benchmarking, multicast,
  link-local, reserved and IPv4-embedded transition destinations. Pin connections
  to validated resolved addresses and reject mixed public/private DNS results.
- Align proxy/scanner NetworkPolicies with that policy without changing DNS and
  authentication exceptions. Do not introduce an untested broad kube-system deny.
- Acceptance: reserved-address and ordering regression tests, public DNS/TLS tests,
  then live rules/policy inspection and VPN health checks. Keep prior image digests
  and firewall recovery access available for rollback.

## 2. Account recovery (V-03)

- Retain native-client compatibility: enforce bounded IP/email quotas on all reset
  requests; do not invalidate an existing unexpired reset link on repeated requests.
- Issue tokens with a conditional database update. Atomically consume a valid token,
  update the password, delete refresh sessions and revoke access sessions with a
  fail-closed transaction boundary. Losing concurrent requests must have no effect.
- Clear only a newly issued token on email-delivery failure so recovery can retry;
  never clear a newer token. Send a password-changed notification after success.
- Acceptance: disposable PostgreSQL/Redis integration tests for concurrent issuance,
  concurrent redemption, expiration, replay, session revocation and failure rollback.
  No tests against customer accounts or real email addresses.

## 3. Public check tools (V-04)

- Replace cache read/modify/write limits with a signed server-to-server request to
  an atomic Redis limiter in auth-svc. Accept only fixed tool buckets and fixed
  quotas; do not accept caller-chosen limits. Authenticate forwarded visitor IPs.
- Use a dedicated shared secret generated on the Dell, scoped into Kubernetes and
  the existing Pages deployment, never included in source or terminal output.
- Fail closed when the limiter/challenge secret is missing or unreachable. Bound
  JSON request bodies and provider deadlines. Keep existing API responses compatible.
- Acceptance: concurrency, forged/missing signature, malformed IP, missing secret,
  timeout and challenge rejection tests. Deploy backend before switching Pages.

## 4. Proxy session lifecycle (V-07)

- Add per-account connection admission, bounded tunnel lifetime and idle deadlines.
  Periodically revalidate tokens and current entitlement; close both directions on
  revocation, expiry, validation outage or shutdown. Do not log bearer tokens/URLs.
- Acceptance: loopback-only tests for admission/release, revocation, idle timeout,
  maximum lifetime and cleanup, plus existing authentication/forwarding tests.

## 5. Signing trust boundary (V-05)

- Restrict signing to master/trusted version tags, verify tag ancestry and successful
  CI at the source revision before obtaining signing credentials. Move credentials
  from repository-wide secrets to an Android-signing environment with selected refs.
- Protect release tags and preserve automatic deployment (no manual approval gate).
  Validate a signed build with environment credentials before removing old copies.
- Acceptance: untrusted-ref tests and actual environment/tag policy inspection;
  expected signing certificate fingerprint, APK verification and AAB verification.

## 6. Release consistency (V-06)

Update during implementation: master de7ff44 (#176) now publishes signed Android
and Linux 0.2.82. Preserve this concurrent release, verify the APK checksum and
signature, and enforce a minimum secure public Android version in CI instead of
publishing a redundant Android version. The original release steps below apply
only if verification finds that this existing release does not contain the fix.

- Release a new Android version containing current master and security fixes. Keep
  the existing signing identity. Do not silently replace an existing tag/artifact.
- Update website tag/digest pins only after the signed immutable asset is available.
  Add a check tying Android version and download release selection together; avoid
  claiming device-tested lockdown behavior without physical-device validation.
- Acceptance: signed APK/AAB, exact public checksum, direct download health and
  correct website pin. Android OEM handover/reboot testing remains owner-assisted.

## 7. Integration, merge and rollout

- Run affected suites, concurrency/race tests and required CI; review the final diff.
- Push the separate branch, merge through the protected master workflow, and verify
  remote master contains all commits. No force push or unrelated dirty-file commits.
- Roll out auth/proxy/scanner/agent and policy changes on the Dell with bounded
  readiness waits. Run public website/auth/billing negative tests and inspect live
  rules. Update image digest manifests to match deployed builds.
- Publish Pages and the new APK only after dependencies are ready. Record commits,
  image digests, tests and remaining manual acceptance checks in the completion log.
