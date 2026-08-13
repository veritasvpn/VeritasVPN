/**
 * Interactive world map for VeritasVPN network locations.
 * Equirectangular projection on a 1000×500 viewBox (Natural Earth 110m land).
 */

import { WORLD_LAND_PATH } from '../assets/world-land-path.js';

/** @typedef {{ id: string, name: string, city: string, country: string, lat: number, lng: number, status: 'live' | 'soon' }} NetworkLocation */

/** @type {NetworkLocation[]} */
export const NETWORK_LOCATIONS = [
  {
    id: 'py-asu',
    name: 'Paraguay',
    city: 'Asunción metro',
    country: 'PY',
    lat: -25.3,
    lng: -57.6,
    status: 'live',
  },
];

/**
 * Match d3.geoEquirectangular().fitSize([1000, 500], Sphere).
 * @param {number} lat
 * @param {number} lng
 * @param {number} [width=1000]
 * @param {number} [height=500]
 */
export function project(lat, lng, width = 1000, height = 500) {
  // Equirectangular fitted to sphere: x = (lng+180)/360*w, y = (90-lat)/180*h
  const x = ((lng + 180) / 360) * width;
  const y = ((90 - lat) / 180) * height;
  return { x, y };
}

/**
 * @param {HTMLElement} mount
 * @param {{ variant?: 'hero' | 'panel', locations?: NetworkLocation[] }} [opts]
 */
export function mountNetworkMap(mount, opts = {}) {
  const variant = opts.variant || 'panel';
  const locations = opts.locations || NETWORK_LOCATIONS;
  const live = locations.filter((l) => l.status === 'live');

  const markers = locations
    .map((loc) => {
      const { x, y } = project(loc.lat, loc.lng);
      if (loc.status !== 'live') {
        return `<g class="marker soon" transform="translate(${x.toFixed(1)},${y.toFixed(1)})">
          <circle class="marker-soon" r="4"/>
        </g>`;
      }
      const labelX = Math.min(x + 14, 880);
      const labelY = Math.max(y - 16, 24);
      return `<g class="marker live" data-location="${loc.id}">
        <circle class="marker-ring" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="7"/>
        <circle class="marker-ring" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="7" style="animation-delay:0.9s"/>
        <circle class="marker-core" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="5.5"/>
        <rect class="marker-label-bg" x="${(labelX - 6).toFixed(1)}" y="${(labelY - 14).toFixed(1)}" width="118" height="32" rx="4"/>
        <text class="marker-label" x="${labelX.toFixed(1)}" y="${labelY.toFixed(1)}">${loc.name}</text>
        <text class="marker-sub" x="${labelX.toFixed(1)}" y="${(labelY + 12).toFixed(1)}">${loc.city} · live</text>
      </g>`;
    })
    .join('');

  const grid = [0.2, 0.4, 0.6, 0.8]
    .map(
      (t) =>
        `<line class="grid-line" x1="0" y1="${(500 * t).toFixed(0)}" x2="1000" y2="${(500 * t).toFixed(0)}"/>
         <line class="grid-line" x1="${(1000 * t).toFixed(0)}" y1="0" x2="${(1000 * t).toFixed(0)}" y2="500"/>`
    )
    .join('');

  const heroDecor = variant === 'hero' ? `
      <g class="hero-route-layer">
        <path class="hero-route-shadow" d="M860 112 C710 86 540 160 340 320"/>
        <path class="hero-route" d="M860 112 C710 86 540 160 340 320"/>
        <circle class="hero-route-packet" r="3.2">
          <animateMotion dur="4.8s" repeatCount="indefinite" path="M860 112 C710 86 540 160 340 320"/>
        </circle>
        <circle class="hero-scan" cx="340" cy="320" r="18"/>
      </g>` : '';

  mount.insertAdjacentHTML('afterbegin', `
    <svg class="network-map network-map--${variant}" viewBox="0 0 1000 500" role="img"
      aria-label="World map showing ${live.length} live VeritasVPN location${live.length === 1 ? '' : 's'}: ${live.map((l) => l.name).join(', ')}">
      <defs>
        <radialGradient id="mapGlow-${variant}" cx="34%" cy="64%" r="28%">
          <stop offset="0%" stop-color="#00d2ff" stop-opacity="0.32"/>
          <stop offset="100%" stop-color="#0047ff" stop-opacity="0"/>
        </radialGradient>
        <linearGradient id="heroRouteGradient" x1="860" y1="112" x2="340" y2="320" gradientUnits="userSpaceOnUse">
          <stop stop-color="#0047ff" stop-opacity="0"/>
          <stop offset="0.48" stop-color="#00d2ff"/>
          <stop offset="1" stop-color="#5fffe0"/>
        </linearGradient>
      </defs>
      <rect class="ocean" width="1000" height="500"/>
      <rect class="map-glow" width="1000" height="500" fill="url(#mapGlow-${variant})"/>
      ${grid}
      <path class="land" d="${WORLD_LAND_PATH}"/>
      ${heroDecor}
      <g class="markers">${markers}</g>
    </svg>
  `);

  if (variant === 'hero') {
    mount.setAttribute('aria-hidden', 'true');
  }
}
