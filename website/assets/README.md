# Website assets

## Useful information (humans)

Brand image files for the VeritasVPN marketing site:

| File | Use |
|------|-----|
| `logo.png` | Full lockup (shield + wordmark + tagline). Used in the hero. |
| `logo-mark.png` | Shield-only mark. Used in the navbar, footer, and favicon. |

Source art is the official company logo (dark background, cyan→blue shield gradient). Backgrounds were made transparent so the marks sit cleanly on the dark site theme.

## Useful information (AI)

- Keep transparent PNGs; do not reintroduce the original solid near-black plate behind the mark.
- Site palette is derived from this logo: `--bg-primary: #05070a`, `--gradient-start: #00d2ff`, `--gradient-end: #0066ff`, accents around `#00a8ff` / `#00d2ff`.
- Prefer `logo-mark.png` for compact UI chrome; prefer `logo.png` where brand should be hero-level.
- When replacing assets, regenerate crops with transparent background and update `index.html` paths if filenames change.
