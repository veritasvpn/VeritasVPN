"""Canonical marketing site nav for VeritasVPN.

Used by scripts/build-learn.py and scripts/inject-site-nav.py so every
public marketing path ships the same header markup and items.
"""

from __future__ import annotations


NAV_LINKS: list[tuple[str, str, str]] = [
    ("/learn/", "Learn", "learn"),
    ("/check/", "Check", "check"),
    ("/#pricing", "Pricing", "pricing"),
    ("/downloads", "Download", "download"),
    ("/#faq", "FAQ", "faq"),
]


def nav_html(active: str = "") -> str:
    """Return the shared marketing navbar HTML."""

    def item(href: str, label: str, key: str) -> str:
        cur = ' aria-current="page"' if active == key else ""
        return f'<li><a href="{href}"{cur}>{label}</a></li>'

    links = "\n                ".join(item(h, label, key) for h, label, key in NAV_LINKS)
    return f"""  <nav class="navbar" id="navbar">
    <div class="container">
      <a href="/" class="logo">
        <img src="/assets/logo-mark.png" alt="" class="logo-mark" width="36" height="36">
        <span class="logo-text">Veritas<span class="logo-highlight">VPN</span></span>
      </a>
      <ul class="nav-links">
                {links}
      </ul>
      <div class="nav-auth">
        <div id="navAuthLoggedOut" class="nav-auth-logged-out">
          <a href="/?signin=1" class="btn btn-primary nav-login">Log in</a>
        </div>
        <div id="navAuthLoggedIn" class="nav-auth-logged-in is-hidden">
          <span id="navPlanBadge" class="nav-plan-badge" hidden>Inactive</span>
          <button type="button" class="nav-user-btn" id="navUserMenuBtn" aria-haspopup="true" aria-expanded="false">
            <span class="nav-user-avatar" aria-hidden="true"></span>
            <span id="navUserEmail" class="nav-user-email"></span>
          </button>
          <div class="nav-user-menu" id="navUserMenu" role="menu">
            <a href="/account/" class="nav-user-menu-item" role="menuitem">Open dashboard</a>
            <button type="button" class="nav-user-menu-item nav-user-menu-item--signout" id="authSignOutAll" role="menuitem">Sign out from all devices</button>
            <button type="button" class="nav-user-menu-item nav-user-menu-item--signout" id="authSignOut" role="menuitem">Sign out from this device</button>
          </div>
        </div>
      </div>
      <button class="mobile-toggle" id="mobileToggle" aria-label="Toggle menu">
        <span></span><span></span><span></span>
      </button>
    </div>
  </nav>"""


def active_key_for_path(path: str) -> str:
    """Best-effort active nav key from a site-relative path."""
    p = path.replace("\\", "/").lstrip("/")
    if p.startswith("learn"):
        return "learn"
    if p.startswith("check"):
        return "check"
    if p in {"downloads.html", "downloads"} or p.startswith("install/") or p.startswith("install-staging/"):
        return "download"
    if p in {"terms.html", "privacy.html", "transparency.html", "cookies.html", "contact.html"}:
        return ""
    if p in {"", "index.html"}:
        return ""
    return ""


SITE = "https://veritasvpn.cloud"

# Final URL for stub pages that only exist to redirect.
REDIRECT_CANONICAL = {
    "install/index.html": f"{SITE}/downloads",
    "downloads-v2.html": f"{SITE}/downloads",
    "install/windows-v2.html": f"{SITE}/install/windows",
}

# Utility and verification files. Do not invent a marketing canonical for them.
SKIP_CANONICAL = {
    "404.html",
    "reset-password.html",
    "verify-email.html",
    "googleac84ed7d76983bca.html",
    "turnstile-mobile.html",
    "turnstile-mobile-v2.html",
}

SKIP_FOOTER = {
    "googleac84ed7d76983bca.html",
    "turnstile-mobile.html",
    "turnstile-mobile-v2.html",
}

_PATH_ALIAS = {
    "/downloads-v2": "/downloads",
    "/install/windows-v2": "/install/windows",
}


def clean_site_url(url: str) -> str:
    """Map a same-site .html URL to the final pretty URL Pages already serves.

    Directory indexes keep their trailing slash (/account/, /check/, /learn/).
    File pages drop .html (/privacy.html -> /privacy). External URLs are unchanged.
    """
    if not url:
        return url
    raw = url.strip()
    lower = raw.lower()
    if lower.startswith(("#", "mailto:", "tel:", "javascript:", "data:")):
        return url
    prefix = ""
    path = raw
    if lower.startswith(SITE):
        prefix = SITE
        path = raw[len(SITE):]
    elif lower.startswith("http://") or lower.startswith("https://") or lower.startswith("//"):
        return url
    cut = len(path)
    for mark in ("#", "?"):
        i = path.find(mark)
        if i != -1:
            cut = min(cut, i)
    suffix = path[cut:]
    path_only = path[:cut]
    if not path_only.endswith(".html"):
        return url
    base = path_only.rsplit("/", 1)[-1]
    if base.startswith("google") or base.startswith("turnstile"):
        return url
    path_only = path_only[:-5]
    if path_only in {"/index", "index"}:
        path_only = "/"
    elif path_only.endswith("/index"):
        path_only = path_only[: -len("index")]
    if path_only == "":
        path_only = "/"
    if not path_only.startswith("/"):
        if path_only.startswith("."):
            return url
        path_only = "/" + path_only
    path_only = _PATH_ALIAS.get(path_only, path_only)
    if not path_only.startswith("/"):
        path_only = "/" + path_only
    return f"{prefix}{path_only}{suffix}"


def canonical_for(rel: str) -> str | None:
    """Absolute canonical URL for a website-relative HTML path, if it should have one."""
    rel = rel.replace("\\", "/").lstrip("/")
    if rel in REDIRECT_CANONICAL:
        return REDIRECT_CANONICAL[rel]
    if rel in SKIP_CANONICAL or rel.startswith("billing/") or rel.startswith("turnstile"):
        return None
    if not rel.endswith(".html"):
        return None
    if rel == "index.html":
        path = "/"
    elif rel.endswith("/index.html"):
        path = "/" + rel[: -len("index.html")]
    else:
        path = "/" + rel[:-5]
    return f"{SITE}{path}"


ICON_LINKS = """<link rel="icon" href="/favicon.ico" sizes="any">
<link rel="icon" type="image/png" href="/assets/favicon.png">"""


def footer_html() -> str:
    """Shared public footer. Every link is a final pretty URL."""
    return """<footer class="footer">
    <div class="container">
        <div class="footer-grid">
            <div class="footer-brand">
                <a href="/" class="logo">
                    <img src="/assets/logo-mark.png" alt="" class="logo-mark" width="40" height="40">
                    <span class="logo-text">Veritas<span class="logo-highlight">VPN</span></span>
                </a>
                <p>The truth about online privacy. WireGuard from Paraguay plus Veritas Shield. Source-available under BSL.</p>
                <div class="footer-social">
                    <a href="https://github.com/veritasvpn/VeritasVPN" target="_blank" rel="noopener" aria-label="GitHub">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/></svg>
                    </a>
                </div>
            </div>
            <div class="footer-links">
                <h4>Product</h4>
                <ul>
                    <li><a href="/learn/">Learn</a></li>
                    <li><a href="/check/">Privacy check</a></li>
                    <li><a href="/#network">Network</a></li>
                    <li><a href="/#dns">Veritas Shield</a></li>
                    <li><a href="/#pricing">Pricing</a></li>
                    <li><a href="/downloads">Download</a></li>
                    <li><a href="/downloads">Install guides</a></li>
                </ul>
            </div>
            <div class="footer-links">
                <h4>Trust</h4>
                <ul>
                    <li><a href="/transparency">Transparency</a></li>
                    <li><a href="/canary.txt">Warrant Canary</a></li>
                    <li><a href="https://github.com/veritasvpn/VeritasVPN" target="_blank" rel="noopener">Source (BSL)</a></li>
                    <li><a href="/contact">Contact</a></li>
                    <li><a href="/support">Support</a></li>
                </ul>
            </div>
            <div class="footer-links">
                <h4>Legal</h4>
                <ul>
                    <li><a href="/privacy">Privacy</a></li>
                    <li><a href="/terms">Terms</a></li>
                    <li><a href="/transparency">Transparency</a></li>
                    <li><a href="/cookies">Cookies</a></li>
                    <li><a href="/contact">Contact</a></li>
                </ul>
            </div>
        </div>
        <div class="footer-bottom">
            <p>&copy; 2026 VeritasVPN. All rights reserved.</p>
            <p class="footer-tagline">Paraguay · WireGuard · BSL on GitHub</p>
        </div>
    </div>
</footer>"""
