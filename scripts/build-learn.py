#!/usr/bin/env python3
"""Generate VeritasVPN Learn hub + article HTML from Markdown sources.

Usage:
  python3 scripts/build-learn.py

Reads:  website/learn/content/*.md
Writes: website/learn/index.html, website/learn/<slug>.html
Updates the LEARN block inside website/sitemap.xml
"""

from __future__ import annotations

import html
import re
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTENT_DIR = ROOT / "website" / "learn" / "content"
OUT_DIR = ROOT / "website" / "learn"
SITEMAP = ROOT / "website" / "sitemap.xml"
SITE = "https://veritasvpn.cloud"

CATEGORIES = [
    ("basics", "Basics", "Core ideas behind private networking."),
    ("threats", "Threats & leaks", "How exposure happens on the open internet."),
    ("compare", "Compare", "How VPNs sit next to proxies, Tor, and other tools."),
    ("protect", "Protect", "Practical steps and product concepts."),
]

CATEGORY_LABEL = {k: label for k, label, _ in CATEGORIES}


def parse_frontmatter(raw: str) -> tuple[dict, str]:
    if not raw.startswith("---"):
        raise ValueError("missing frontmatter")
    end = raw.find("\n---", 3)
    if end < 0:
        raise ValueError("unterminated frontmatter")
    meta_raw = raw[3:end].strip()
    body = raw[end + 4 :].lstrip("\n")
    meta: dict = {}
    for line in meta_raw.splitlines():
        if ":" not in line:
            continue
        key, val = line.split(":", 1)
        key = key.strip()
        val = val.strip()
        if val.startswith("[") and val.endswith("]"):
            inner = val[1:-1].strip()
            meta[key] = [x.strip() for x in inner.split(",") if x.strip()] if inner else []
        else:
            meta[key] = val.strip('"').strip("'")
    return meta, body


def inline_md(text: str) -> str:
    text = html.escape(text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', text)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    return text


def markdown_to_html(md: str) -> str:
    lines = md.splitlines()
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        if line.startswith("### "):
            out.append(f"<h3>{inline_md(line[4:].strip())}</h3>")
            i += 1
            continue
        if line.startswith("## "):
            out.append(f"<h2>{inline_md(line[3:].strip())}</h2>")
            i += 1
            continue
        if line.startswith("# "):
            # Title lives in frontmatter / template; skip duplicate H1 in body.
            i += 1
            continue
        if re.match(r"^[-*] ", line):
            items = []
            while i < len(lines) and re.match(r"^[-*] ", lines[i]):
                items.append(f"<li>{inline_md(lines[i][2:].strip())}</li>")
                i += 1
            out.append("<ul>" + "".join(items) + "</ul>")
            continue
        if re.match(r"^\d+\. ", line):
            items = []
            while i < len(lines) and re.match(r"^\d+\. ", lines[i]):
                items.append(f"<li>{inline_md(re.sub(r'^\d+\.\s+', '', lines[i]))}</li>")
                i += 1
            out.append("<ol>" + "".join(items) + "</ol>")
            continue
        para = [line]
        i += 1
        while i < len(lines) and lines[i].strip() and not lines[i].startswith("#") and not re.match(r"^[-*] ", lines[i]) and not re.match(r"^\d+\. ", lines[i]):
            para.append(lines[i])
            i += 1
        out.append(f"<p>{inline_md(' '.join(p.strip() for p in para))}</p>")
    return "\n".join(out)


def nav_html(active: str = "") -> str:
    def item(href: str, label: str, key: str) -> str:
        cur = ' aria-current="page"' if active == key else ""
        return f'<li><a href="{href}"{cur}>{label}</a></li>'

    return f"""  <nav class="navbar">
    <div class="container">
      <a href="/" class="logo">
        <img src="/assets/logo-mark.png" alt="" class="logo-mark" width="36" height="36">
        <span class="logo-text">Veritas<span class="logo-highlight">VPN</span></span>
      </a>
      <ul class="nav-links">
        {item("/learn/", "Learn", "learn")}
        {item("/check/", "Check", "check")}
        {item("/#dns", "DNS", "dns")}
        {item("/#pricing", "Pricing", "pricing")}
        {item("/downloads.html", "Download", "download")}
        {item("/account/", "Account", "account")}
      </ul>
      <a class="btn btn-primary" href="/downloads.html">Get Veritas</a>
    </div>
  </nav>"""


def footer_html() -> str:
    return """  <footer class="footer">
    <div class="container">
      <div class="footer-bottom">
        <p>&copy; 2026 VeritasVPN. <a href="/learn/">Learn</a> · <a href="/check/">Privacy check</a> · <a href="/privacy.html">Privacy</a> · <a href="/terms.html">Terms</a></p>
      </div>
    </div>
  </footer>"""


def page_shell(title: str, description: str, canonical: str, body: str, active: str = "learn") -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>{html.escape(title)} — VeritasVPN Learn</title>
  <meta name="description" content="{html.escape(description)}">
  <link rel="canonical" href="{html.escape(canonical)}">
  <link rel="stylesheet" href="/css/style-typography1.css">
  <link rel="stylesheet" href="/css/learn.css">
  <link rel="icon" type="image/png" href="/assets/favicon.png">
</head>
<body>
  <a class="skip-link" href="#main">Skip to content</a>
{nav_html(active)}
{body}
{footer_html()}
</body>
</html>
"""


def load_articles() -> list[dict]:
    if not CONTENT_DIR.is_dir():
        raise SystemExit(f"missing content dir: {CONTENT_DIR}")
    articles = []
    for path in sorted(CONTENT_DIR.glob("*.md")):
        meta, body = parse_frontmatter(path.read_text(encoding="utf-8"))
        slug = meta.get("slug") or path.stem
        cat = meta.get("category", "basics")
        if cat not in CATEGORY_LABEL:
            raise SystemExit(f"{path.name}: unknown category {cat!r}")
        articles.append(
            {
                "slug": slug,
                "title": meta["title"],
                "description": meta["description"],
                "category": cat,
                "related": meta.get("related") or [],
                "updated": meta.get("updated", date.today().isoformat()),
                "lede": meta.get("lede", meta["description"]),
                "body_html": markdown_to_html(body),
                "path": path,
            }
        )
    by_slug = {a["slug"]: a for a in articles}
    for a in articles:
        a["related_resolved"] = [by_slug[s] for s in a["related"] if s in by_slug]
    return articles


def render_hub(articles: list[dict]) -> str:
    by_cat: dict[str, list] = defaultdict(list)
    for a in articles:
        by_cat[a["category"]].append(a)

    sections = []
    for key, label, blurb in CATEGORIES:
        items = by_cat.get(key) or []
        if not items:
            continue
        links = []
        for a in items:
            links.append(
                f"""        <li>
          <a href="/learn/{html.escape(a['slug'])}.html">
            <span class="learn-list-title">{html.escape(a['title'])}</span>
            <span class="learn-list-desc">{html.escape(a['description'])}</span>
            <span class="learn-list-meta">Read</span>
          </a>
        </li>"""
            )
        sections.append(
            f"""      <section class="learn-section" id="{html.escape(key)}" aria-labelledby="learn-{html.escape(key)}">
        <div class="learn-section-head">
          <h2 id="learn-{html.escape(key)}">{html.escape(label)}</h2>
          <p>{html.escape(blurb)}</p>
        </div>
        <ul class="learn-list">
{chr(10).join(links)}
        </ul>
      </section>"""
        )

    body = f"""  <main id="main" class="learn-page">
    <div class="container">
      <header class="learn-hero">
        <p class="learn-brand">Veritas<span class="logo-highlight">VPN</span> Learn</p>
        <h1>Cybersecurity, explained without the spin</h1>
        <p class="learn-lede">Clear guides on VPNs, DNS, leaks, and everyday privacy—so you can understand the risks before you buy protection.</p>
        <div class="learn-hero-actions">
          <a class="btn btn-primary" href="#basics">Browse topics</a>
          <a class="btn btn-outline" href="/downloads.html">Get VeritasVPN</a>
        </div>
      </header>
{chr(10).join(sections)}
      <aside class="learn-cta" aria-label="Try VeritasVPN">
        <h2>Need protection on the next hop?</h2>
        <p>VeritasVPN is WireGuard from Paraguay with always-on malware and phishing DNS filtering while connected. Honest docs. Source on GitHub.</p>
        <div class="cta-row">
          <a class="btn btn-primary" href="/downloads.html">Download VeritasVPN</a>
          <a class="btn btn-outline" href="/check/">Privacy check</a>
          <a class="btn btn-outline" href="/#pricing">Pricing</a>
        </div>
      </aside>
    </div>
  </main>"""
    return page_shell(
        "Cybersecurity education",
        "Free guides on VPNs, DNS, leaks, and privacy from VeritasVPN Learn—plain language, no marketing spin.",
        f"{SITE}/learn/",
        body,
    )


def render_article(a: dict) -> str:
    related = ""
    if a["related_resolved"]:
        links = "\n".join(
            f'        <li><a href="/learn/{html.escape(r["slug"])}.html">{html.escape(r["title"])}</a></li>'
            for r in a["related_resolved"]
        )
        related = f"""      <nav class="learn-related" aria-label="Related articles">
        <h2>Keep learning</h2>
        <ul>
{links}
        </ul>
      </nav>"""

    cat_label = CATEGORY_LABEL[a["category"]]
    body = f"""  <main id="main" class="learn-page">
    <div class="container">
      <article class="learn-article">
        <a class="learn-back" href="/learn/">← All topics</a>
        <span class="learn-kicker">{html.escape(cat_label)}</span>
        <h1>{html.escape(a['title'])}</h1>
        <p class="learn-meta">Updated {html.escape(a['updated'])}</p>
        <p class="learn-lede">{html.escape(a['lede'])}</p>
        <div class="learn-prose">
{a['body_html']}
        </div>
{related}
        <aside class="learn-cta" aria-label="Need protection">
          <h2>Need protection? Try VeritasVPN</h2>
          <p>When you want the concepts on this page applied on your device: WireGuard tunnel, protected DNS, and clear limits—not inflated claims.</p>
          <div class="cta-row">
            <a class="btn btn-primary" href="/downloads.html">Download VeritasVPN</a>
            <a class="btn btn-outline" href="/#pricing">Pricing</a>
            <a class="btn btn-outline" href="/check/">Run a privacy check</a>
          </div>
        </aside>
        <p class="learn-footer-note">Part of <a href="/learn/">VeritasVPN Learn</a>. Educational only—not legal advice.</p>
      </article>
    </div>
  </main>"""
    return page_shell(a["title"], a["description"], f"{SITE}/learn/{a['slug']}.html", body)


LEARN_SITEMAP_START = "  <!-- LEARN_SITEMAP_START -->"
LEARN_SITEMAP_END = "  <!-- LEARN_SITEMAP_END -->"


def update_sitemap(articles: list[dict]) -> None:
    entries = [
        "  <url>",
        f"    <loc>{SITE}/learn/</loc>",
        "    <changefreq>weekly</changefreq>",
        "    <priority>0.9</priority>",
        "  </url>",
    ]
    for a in articles:
        entries.extend(
            [
                "  <url>",
                f"    <loc>{SITE}/learn/{a['slug']}.html</loc>",
                "    <changefreq>monthly</changefreq>",
                "    <priority>0.7</priority>",
                "  </url>",
            ]
        )
    block = LEARN_SITEMAP_START + "\n" + "\n".join(entries) + "\n" + LEARN_SITEMAP_END

    text = SITEMAP.read_text(encoding="utf-8")
    if LEARN_SITEMAP_START in text and LEARN_SITEMAP_END in text:
        text = re.sub(
            re.escape(LEARN_SITEMAP_START) + r".*?" + re.escape(LEARN_SITEMAP_END),
            block,
            text,
            flags=re.S,
        )
    else:
        text = text.replace("</urlset>", block + "\n</urlset>")
    SITEMAP.write_text(text, encoding="utf-8")


def main() -> int:
    articles = load_articles()
    if len(articles) < 1:
        print("no articles found", file=sys.stderr)
        return 1

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    # Remove previously generated article HTML (keep content/)
    for old in OUT_DIR.glob("*.html"):
        old.unlink()

    (OUT_DIR / "index.html").write_text(render_hub(articles), encoding="utf-8")
    for a in articles:
        (OUT_DIR / f"{a['slug']}.html").write_text(render_article(a), encoding="utf-8")

    update_sitemap(articles)
    print(f"generated {len(articles)} articles + hub → {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
