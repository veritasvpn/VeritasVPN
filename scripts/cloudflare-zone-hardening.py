#!/usr/bin/env python3
"""Apply veritasvpn.cloud zone settings when CLOUDFLARE_API_TOKEN can edit the zone.

The Pages deploy token is only known to work for the Pages project API. If it
cannot list this zone, the script prints ZONE_PERMISSION_MISSING and exits 0.
It never prints the token, mailbox destinations, or IP addresses.

Dashboard steps, if the token cannot edit the zone
--------------------------------------------------
Zone: veritasvpn.cloud (Cloudflare dashboard).

Alignment already checked from public DNS before p=quarantine is safe:
- Apex SPF is "v=spf1 include:_spf.mx.cloudflare.net ~all" (Email Routing only).
  Do not add amazonses.com there. Resend's return-path is the subdomain.
- send.veritasvpn.cloud SPF is "v=spf1 include:amazonses.com ~all".
  Relaxed SPF (aspf=r) aligns send.veritasvpn.cloud with veritasvpn.cloud.
- resend._domainkey.veritasvpn.cloud has a DKIM TXT key (d=veritasvpn.cloud).
- cf2024-1._domainkey.veritasvpn.cloud has a DKIM TXT key (Email Routing).
- MX is route1/route2/route3.mx.cloudflare.net, so Email Routing is on.

1. DNS → TXT record _dmarc (name _dmarc.veritasvpn.cloud):
   v=DMARC1; p=quarantine; adkim=r; aspf=r; rua=mailto:dmarc@veritasvpn.cloud
2. Email → Email Routing → Routing rules → Create address
   dmarc@veritasvpn.cloud and send it to the same verified destination that
   already receives contact@veritasvpn.cloud. Do not forward dmarc@ to
   contact@ on this same domain (that loops). Destination addresses stay off
   this page and out of git.
3. DNS → Add CAA records on the apex (name veritasvpn.cloud, flags 0), both
   issue and issuewild, for each of: pki.goog, letsencrypt.org, ssl.com.
   The live certificates are Google Trust Services (pki.goog), and the API
   certificate includes *.veritasvpn.cloud, so issuewild is required.
   Do not add a CAA set that omits pki.goog.
4. Scrape Shield → Email Address Obfuscation → Off.
   Page HTML already contains mailto:contact@veritasvpn.cloud. The edge is
   what rewrites it. After this is off, the address is plain text.
5. SSL/TLS → Edge Certificates → Always Use HTTPS → On.
   This was already on (HTTP 301 to https). Leave it on.
6. Rules → Redirect Rules → Create rule:
   If hostname equals www.veritasvpn.cloud, dynamic redirect 301 to
   concat("https://veritasvpn.cloud", http.request.uri.path)
   with "Preserve query string" on.
   Pages Functions also redirect www (website/functions/_middleware.js). This
   rule is the edge backup if a www request never reaches Pages.
7. Do not submit veritasvpn.cloud to the HSTS preload list.

The script skips the DMARC change if those DNS checks fail at runtime.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

ZONE_NAME = "veritasvpn.cloud"
API = "https://api.cloudflare.com/client/v4"
DMARC = "v=DMARC1; p=quarantine; adkim=r; aspf=r; rua=mailto:dmarc@veritasvpn.cloud"
CAA_CAS = ("pki.goog", "letsencrypt.org", "ssl.com")
CAA_TAGS = ("issue", "issuewild")
WWW_EXPRESSION = '(http.host eq "www.veritasvpn.cloud")'
WWW_TARGET = 'concat("https://veritasvpn.cloud", http.request.uri.path)'


class ZonePermissionMissing(RuntimeError):
    pass


def redact(text: str) -> str:
    text = re.sub(r"(?i)bearer\s+\S+", "bearer [redacted]", text)
    text = re.sub(r"(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", "[redacted-email]", text)
    text = re.sub(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", "[ip]", text)
    return text


def alignment_problems(snapshot: dict) -> list[str]:
    """snapshot maps record name to a list of TXT/MX strings."""
    problems = []
    apex = " ".join(snapshot.get(ZONE_NAME, []))
    send = " ".join(snapshot.get(f"send.{ZONE_NAME}", []))
    resend = " ".join(snapshot.get(f"resend._domainkey.{ZONE_NAME}", []))
    cf_dkim = " ".join(snapshot.get(f"cf2024-1._domainkey.{ZONE_NAME}", []))
    mx = " ".join(snapshot.get(f"MX {ZONE_NAME}", []))
    if "_spf.mx.cloudflare.net" not in apex:
        problems.append("apex SPF does not include Cloudflare Email Routing")
    if "amazonses.com" not in send:
        problems.append("send.veritasvpn.cloud SPF does not include amazonses.com")
    if "v=DKIM1" not in resend and "p=" not in resend:
        problems.append("Resend DKIM TXT is missing")
    if "v=DKIM1" not in cf_dkim:
        problems.append("Cloudflare Email Routing DKIM TXT is missing")
    if "mx.cloudflare.net" not in mx:
        problems.append("MX is not Cloudflare Email Routing")
    return problems


def caa_wanted() -> list[tuple[str, str]]:
    return [(tag, ca) for tag in CAA_TAGS for ca in CAA_CAS]


def _doh(name: str, rtype: str) -> list[str]:
    query = urllib.parse.urlencode({"name": name, "type": rtype})
    request = urllib.request.Request(
        f"https://cloudflare-dns.com/dns-query?{query}",
        headers={"accept": "application/dns-json"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        payload = json.load(response)
    values = []
    for answer in payload.get("Answer") or []:
        data = str(answer.get("data") or "").strip().strip('"')
        if data:
            values.append(data)
    return values


def public_alignment_snapshot() -> dict:
    return {
        ZONE_NAME: _doh(ZONE_NAME, "TXT"),
        f"send.{ZONE_NAME}": _doh(f"send.{ZONE_NAME}", "TXT"),
        f"resend._domainkey.{ZONE_NAME}": _doh(f"resend._domainkey.{ZONE_NAME}", "TXT"),
        f"cf2024-1._domainkey.{ZONE_NAME}": _doh(f"cf2024-1._domainkey.{ZONE_NAME}", "TXT"),
        f"MX {ZONE_NAME}": _doh(ZONE_NAME, "MX"),
    }


class Client:
    def __init__(self, token: str):
        self.token = token

    def call(self, method: str, path: str, body: dict | None = None, allow_404: bool = False):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(
            API + path,
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.load(response)
                status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", "replace")
            if error.code in {401, 403}:
                raise ZonePermissionMissing(redact(raw)[:300]) from None
            if allow_404 and error.code == 404:
                return None
            try:
                parsed = json.loads(raw)
            except json.JSONDecodeError:
                parsed = {}
            codes = [item.get("code") for item in parsed.get("errors") or []]
            if 10000 in codes or 9109 in codes:
                raise ZonePermissionMissing(redact(raw)[:300]) from None
            raise RuntimeError(f"{method} {path} -> {error.code} {redact(raw)[:300]}") from None
        if not payload.get("success", True):
            raise RuntimeError(redact(json.dumps(payload))[:300])
        return payload.get("result")


def ensure_dmarc(client: Client, zone_id: str) -> None:
    name = f"_dmarc.{ZONE_NAME}"
    found = client.call("GET", f"/zones/{zone_id}/dns_records?type=TXT&name={name}")
    records = found or []
    current = next((item for item in records if "v=DMARC1" in item.get("content", "")), None)
    body = {"type": "TXT", "name": name, "content": DMARC, "ttl": 1}
    if current and current.get("content") == DMARC:
        print("DMARC already set")
        return
    if current:
        client.call("PUT", f"/zones/{zone_id}/dns_records/{current['id']}", body)
        print("DMARC updated to p=quarantine")
        return
    client.call("POST", f"/zones/{zone_id}/dns_records", body)
    print("DMARC created")


def ensure_caa(client: Client, zone_id: str) -> None:
    found = client.call("GET", f"/zones/{zone_id}/dns_records?type=CAA&name={ZONE_NAME}")
    existing = set()
    for item in found or []:
        data = item.get("data") or {}
        tag = data.get("tag") or ""
        value = (data.get("value") or "").strip('"')
        existing.add((tag, value))
    for tag, ca in caa_wanted():
        if (tag, ca) in existing:
            continue
        client.call(
            "POST",
            f"/zones/{zone_id}/dns_records",
            {
                "type": "CAA",
                "name": ZONE_NAME,
                "ttl": 1,
                "data": {"flags": 0, "tag": tag, "value": ca},
            },
        )
        print(f"CAA added {tag} {ca}")
    print("CAA set covers pki.goog, letsencrypt.org, ssl.com")


def ensure_setting(client: Client, zone_id: str, setting: str, value: str) -> None:
    result = client.call("PATCH", f"/zones/{zone_id}/settings/{setting}", {"value": value})
    print(f"{setting}={(result or {}).get('value', value)}")


def ensure_www_redirect(client: Client, zone_id: str) -> None:
    rule = {
        "action": "redirect",
        "expression": WWW_EXPRESSION,
        "description": "Redirect www to apex",
        "enabled": True,
        "action_parameters": {
            "from_value": {
                "status_code": 301,
                "target_url": {"expression": WWW_TARGET},
                "preserve_query_string": True,
            }
        },
    }
    current = client.call(
        "GET",
        f"/zones/{zone_id}/rulesets/phases/http_request_dynamic_redirect/entrypoint",
        allow_404=True,
    )
    if current is None:
        client.call(
            "PUT",
            f"/zones/{zone_id}/rulesets/phases/http_request_dynamic_redirect/entrypoint",
            {"rules": [rule]},
        )
        print("www redirect rule created")
        return
    rules = current.get("rules") or []
    if any(WWW_EXPRESSION in (item.get("expression") or "") for item in rules):
        print("www redirect rule already present")
        return
    client.call("POST", f"/zones/{zone_id}/rulesets/{current['id']}/rules", rule)
    print("www redirect rule added")


def ensure_dmarc_route(client: Client, zone_id: str) -> None:
    rules = client.call("GET", f"/zones/{zone_id}/email/routing/rules") or []
    contact_actions = None
    for rule in rules:
        for matcher in rule.get("matchers") or []:
            value = (matcher.get("value") or "").lower()
            if value == f"dmarc@{ZONE_NAME}":
                print("dmarc routing rule already present")
                return
            if value == f"contact@{ZONE_NAME}":
                contact_actions = rule.get("actions")
    if not contact_actions:
        print("dmarc routing rule NOT applied: no contact@ routing rule to copy. Add it in the dashboard.")
        return
    client.call(
        "POST",
        f"/zones/{zone_id}/email/routing/rules",
        {
            "name": "DMARC reports",
            "enabled": True,
            "matchers": [{"type": "literal", "field": "to", "value": f"dmarc@{ZONE_NAME}"}],
            "actions": contact_actions,
        },
    )
    print("dmarc routing rule created toward the contact destination")


def apply() -> int:
    token = os.environ.get("CLOUDFLARE_API_TOKEN") or ""
    if not token:
        print("ZONE_PERMISSION_MISSING no CLOUDFLARE_API_TOKEN")
        return 0
    client = Client(token)
    try:
        zones = client.call("GET", f"/zones?name={ZONE_NAME}")
    except ZonePermissionMissing as error:
        print("ZONE_PERMISSION_MISSING", error)
        return 0
    if not zones:
        print("ZONE_PERMISSION_MISSING token cannot see the zone")
        return 0
    zone_id = zones[0]["id"]
    problems = alignment_problems(public_alignment_snapshot())
    if problems:
        print("DMARC NOT changed:", "; ".join(problems))
    else:
        try:
            ensure_dmarc(client, zone_id)
        except ZonePermissionMissing as error:
            print("ZONE_PERMISSION_MISSING", error)
            return 0
    for step in (
        lambda: ensure_caa(client, zone_id),
        lambda: ensure_setting(client, zone_id, "email_obfuscation", "off"),
        lambda: ensure_setting(client, zone_id, "always_use_https", "on"),
        lambda: ensure_www_redirect(client, zone_id),
        lambda: ensure_dmarc_route(client, zone_id),
    ):
        try:
            step()
        except ZonePermissionMissing as error:
            print("ZONE_PERMISSION_MISSING", error)
            return 0
        except Exception as error:  # noqa: BLE001 — keep the Pages deploy alive and say what failed
            print("ZONE_STEP_FAILED", redact(str(error)))
    print("zone hardening finished")
    return 0


def self_test() -> int:
    good = {
        ZONE_NAME: ["v=spf1 include:_spf.mx.cloudflare.net ~all"],
        f"send.{ZONE_NAME}": ["v=spf1 include:amazonses.com ~all"],
        f"resend._domainkey.{ZONE_NAME}": ["v=DKIM1; p=abc"],
        f"cf2024-1._domainkey.{ZONE_NAME}": ["v=DKIM1; p=def"],
        f"MX {ZONE_NAME}": ["10 route1.mx.cloudflare.net."],
    }
    assert alignment_problems(good) == [], alignment_problems(good)
    broken = dict(good)
    broken[f"send.{ZONE_NAME}"] = ["v=spf1 -all"]
    assert any("amazonses" in item for item in alignment_problems(broken))
    wanted = set(caa_wanted())
    assert ("issue", "pki.goog") in wanted
    assert ("issuewild", "letsencrypt.org") in wanted
    assert ("issuewild", "ssl.com") in wanted
    assert "p=quarantine" in DMARC and "adkim=r" in DMARC and "aspf=r" in DMARC
    assert "dmarc@veritasvpn.cloud" in DMARC
    assert "www.veritasvpn.cloud" in WWW_EXPRESSION
    assert "http.request.uri.path" in WWW_TARGET
    redacted = redact("fail bearer secret-token and jpgtv19@example.com from 203.0.113.10")
    assert "secret-token" not in redacted
    assert "jpgtv19" not in redacted
    assert "203.0.113.10" not in redacted
    print("cloudflare zone self-test ok")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(self_test())
    try:
        raise SystemExit(apply())
    except Exception as error:  # noqa: BLE001
        print("ZONE_APPLY_FAILED", redact(str(error)))
        raise SystemExit(0)
