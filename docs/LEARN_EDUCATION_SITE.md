# VeritasVPN Learn

Educational section at `https://veritasvpn.cloud/learn/`.

## Add an article

1. Create `website/learn/content/<slug>.md` with YAML frontmatter:

```yaml
---
title: Example title
description: One-line SEO/summary.
category: basics   # basics | threats | compare | protect
slug: example-title
related: [what-is-a-vpn]
updated: 2026-09-04
lede: Optional short intro under the H1.
---

## Section

Body in a small Markdown subset: `##` / `###`, paragraphs, lists, `**bold**`, `[links](/path)`, `` `code` ``.
```

2. Regenerate HTML + sitemap Learn block:

```bash
python3 scripts/build-learn.py
```

3. Commit both the Markdown source and generated `website/learn/*.html`.

CI runs the generator on Pages deploy so HTML cannot silently drift from content.
