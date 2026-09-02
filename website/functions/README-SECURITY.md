/**
 * Ops note: Cloudflare Pages must set TURNSTILE_SECRET_KEY (and optionally
 * ENVIRONMENT=production) for /api/check/breach. Also attach a WAF rate-limit
 * rule on /api/check/* as belt-and-suspenders alongside in-function Cache API limits.
 */
