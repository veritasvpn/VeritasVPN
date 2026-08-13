# Network map module

## Useful information (humans)

Renders the homepage world map and hero backdrop. Live nodes are listed in
`NETWORK_LOCATIONS` — today that is Paraguay (Asunción metro). Add more
entries when nodes actually go online; do not invent locations.

## Useful information (AI)

- File: `website/js/network-map.js`
- Export: `NETWORK_LOCATIONS`, `project()`, `mountNetworkMap(el, { variant, locations })`
- Projection: equirectangular on viewBox `0 0 1000 500`
- Keep marketing copy aligned with this list (single live region until expanded)
- Land paths are decorative silhouettes, not geographic accuracy
- Land geometry: `../assets/world-land-path.js` (Natural Earth 110m)
