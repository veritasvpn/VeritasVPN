# World land path asset

## Useful information (humans)

`world-land-path.js` is Natural Earth 1:110m land, projected equirectangular
into a 1000×500 viewBox. Used by the homepage network map.

Regenerate with (from repo tooling /tmp script or similar):

```bash
# uses world-atlas + d3-geo + topojson-client
node --input-type=module scripts/generate-world-land.mjs
```

## Useful information (AI)

- Do not hand-edit `world-land-path.js` (huge generated string)
- `world-land.svg` is a static preview of the same path
- `world-land-path.txt` is the raw `d` attribute
- Marker projection in `network-map.js` must stay equirectangular fit to Sphere
