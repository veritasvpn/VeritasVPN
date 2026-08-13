# Entitlement limits (wg-manager)

## For humans

Plan gates applied in `CreatePeer` before IP allocation:

| Plan | Devices (peers) | Regions |
|------|-----------------|---------|
| Free | 1 | Any online region, unless `FREE_ALLOWED_REGIONS` is set |
| Premium | 5 | Any online region |

Data caps are not metered yet — do not advertise GB limits until usage tracking exists.

JWT `tier` claim (from auth-svc) is the input. Billing must sync that tier via NATS (`subscription.renewed` / `subscription.expired`).

## For AI

- Package: `services/wg-manager/internal/entitlement`
- Errors: `*PlanError` with `Code` (`plan_device_limit_free`, `plan_device_limit`, `plan_region_denied`) → HTTP 403
- Env: `FREE_ALLOWED_REGIONS` optional comma list (e.g. `local`)
- Keep website pricing copy aligned with these numbers
- Do not re-enable unauthenticated gRPC CreatePeer without the same checks
