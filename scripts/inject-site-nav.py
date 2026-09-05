#!/usr/bin/env python3
"""Rewrite marketing page navbars to the shared canonical header."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEBSITE = ROOT / "website"
sys.path.insert(0, str(ROOT / "scripts"))

from site_nav import active_key_for_path, nav_html  # noqa: E402

# Account / auth / billing utility pages keep their own chrome.
SKIP_FILES = {
    "404.html",
    "reset-password.html",
    "verify-email.html",
    "status.html",
    "turnstile-mobile.html",
}

NAV_RE = re.compile(
    r"<nav\b[^>]*\bclass=\"[^\"]*\bnavbar\b[^\"]*\"[^>]*>.*?</nav>",
    re.IGNORECASE | re.DOTALL,
)

SITE_NAV_SCRIPT = '<script type="module" src="/js/site-nav.js"></script>'


def should_process(path: Path) -> bool:
    rel = path.relative_to(WEBSITE).as_posix()
    if path.name in SKIP_FILES:
        return False
    if rel.startswith("learn/content/"):
        return False
    top = rel.split("/", 1)[0]
    if top in {"account", "billing", "functions", "css", "js", "js-v2", "assets"}:
        return False
    # Artifact directory under website/downloads/ (not downloads.html)
    if rel.startswith("downloads/") and not rel.endswith(".html"):
        return False
    return path.suffix == ".html"


def ensure_site_nav_script(html: str) -> str:
    if "/js/site-nav.js" in html:
        return html
    if re.search(r"</body\s*>", html, re.IGNORECASE):
        return re.sub(r"</body\s*>", f"  {SITE_NAV_SCRIPT}\n</body>", html, count=1, flags=re.IGNORECASE)
    return html + "\n" + SITE_NAV_SCRIPT + "\n"


def rewrite_file(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    if not NAV_RE.search(original):
        return False
    rel = path.relative_to(WEBSITE).as_posix()
    active = active_key_for_path(rel)
    replacement = nav_html(active)
    updated = NAV_RE.sub(replacement, original, count=1)
    updated = ensure_site_nav_script(updated)
    if updated == original:
        return False
    path.write_text(updated, encoding="utf-8")
    return True


def main() -> int:
    changed = []
    for path in sorted(WEBSITE.rglob("*.html")):
        if not should_process(path):
            continue
        if rewrite_file(path):
            changed.append(path.relative_to(WEBSITE).as_posix())
    for rel in changed:
        print(f"updated {rel}")
    print(f"{len(changed)} pages updated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
