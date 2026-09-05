"""Canonical marketing site nav for VeritasVPN.

Used by scripts/build-learn.py and scripts/inject-site-nav.py so every
public marketing path ships the same header markup and items.
"""

from __future__ import annotations


NAV_LINKS: list[tuple[str, str, str]] = [
    ("/learn/", "Learn", "learn"),
    ("/check/", "Check", "check"),
    ("/#network", "Network", "network"),
    ("/#product", "Product", "product"),
    ("/#dns", "Shield", "dns"),
    ("/#pricing", "Pricing", "pricing"),
    ("/downloads.html", "Download", "download"),
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
    if p == "downloads.html" or p.startswith("install/") or p.startswith("install-staging/"):
        return "download"
    if p in {"terms.html", "privacy.html", "cookies.html", "contact.html"}:
        return ""
    if p in {"", "index.html"}:
        return ""
    return ""
