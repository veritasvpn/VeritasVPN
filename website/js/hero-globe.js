import { WORLD_LAND_PATH } from '../assets/world-land-path.js';

export function mountHeroGlobe(mount) {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  mount.classList.add('hero-globe-stage');
  mount.setAttribute('aria-label', 'Interactive globe. Drag to rotate the world.');
  mount.removeAttribute('aria-hidden');
  mount.innerHTML = `
    <div class="hero-map-aura hero-map-aura-one"></div>
    <div class="hero-map-aura hero-map-aura-two"></div>
    <div class="hero-globe-shell">
      <svg class="hero-globe" viewBox="0 0 500 500" role="img" aria-label="Rotating world globe">
        <defs>
          <clipPath id="heroGlobeClip"><circle cx="250" cy="250" r="218"/></clipPath>
          <radialGradient id="heroOcean" cx="34%" cy="28%" r="76%">
            <stop offset="0%" stop-color="#123d5c"/>
            <stop offset="58%" stop-color="#071b35"/>
            <stop offset="100%" stop-color="#020814"/>
          </radialGradient>
          <radialGradient id="heroShade" cx="30%" cy="26%" r="72%">
            <stop offset="48%" stop-color="#001022" stop-opacity="0"/>
            <stop offset="82%" stop-color="#000711" stop-opacity=".42"/>
            <stop offset="100%" stop-color="#00030a" stop-opacity=".92"/>
          </radialGradient>
          <linearGradient id="heroLand" x1="0" y1="0" x2="1" y2="1">
            <stop stop-color="#35e3c0"/>
            <stop offset=".5" stop-color="#08aeea"/>
            <stop offset="1" stop-color="#185adb"/>
          </linearGradient>
          <filter id="heroLandGlow"><feGaussianBlur stdDeviation="2.2" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
        </defs>
        <circle class="hero-globe-ocean" cx="250" cy="250" r="218"/>
        <g clip-path="url(#heroGlobeClip)">
          <g class="hero-globe-grid">
            <ellipse cx="250" cy="250" rx="218" ry="62"/>
            <ellipse cx="250" cy="250" rx="218" ry="128"/>
            <ellipse cx="250" cy="250" rx="82" ry="218"/>
            <ellipse cx="250" cy="250" rx="154" ry="218"/>
          </g>
          <g class="hero-globe-world">
            <path d="${WORLD_LAND_PATH}" transform="translate(-1000 0)"/>
            <path d="${WORLD_LAND_PATH}"/>
            <path d="${WORLD_LAND_PATH}" transform="translate(1000 0)"/>
            <circle class="hero-globe-pin-pulse" cx="-661" cy="315" r="12"/><circle class="hero-globe-pin-core" cx="-661" cy="315" r="5"/>
            <circle class="hero-globe-pin-pulse" cx="339" cy="315" r="12"/><circle class="hero-globe-pin-core" cx="339" cy="315" r="5"/>
            <circle class="hero-globe-pin-pulse" cx="1339" cy="315" r="12"/><circle class="hero-globe-pin-core" cx="1339" cy="315" r="5"/>
          </g>
          <circle class="hero-globe-shade" cx="250" cy="250" r="218"/>
          <path class="hero-globe-glint" d="M108 170 C155 74 286 40 370 108"/>
        </g>
        <circle class="hero-globe-atmosphere" cx="250" cy="250" r="222"/>
      </svg>
      <p class="hero-globe-hint"><span aria-hidden="true">Drag</span> to explore</p>
    </div>`;

  const world = mount.querySelector('.hero-globe-world');
  let longitude = 90;
  let latitude = 0;
  let dragging = false;
  let hovering = false;
  let pointerX = 0;
  let pointerY = 0;
  let previousTime = 0;

  const render = () => {
    const wrapped = ((longitude % 1000) + 1000) % 1000;
    world.setAttribute('transform', 'translate(' + (-wrapped) + ' ' + latitude + ')');
  };

  mount.addEventListener('pointerdown', (event) => {
    dragging = true;
    pointerX = event.clientX;
    pointerY = event.clientY;
    mount.classList.add('is-dragging');
    mount.setPointerCapture(event.pointerId);
  });
  mount.addEventListener('pointermove', (event) => {
    if (!dragging) return;
    longitude -= (event.clientX - pointerX) * 1.45;
    latitude = Math.max(-90, Math.min(90, latitude + (event.clientY - pointerY) * 0.7));
    pointerX = event.clientX;
    pointerY = event.clientY;
    render();
  });
  const stopDragging = (event) => {
    dragging = false;
    mount.classList.remove('is-dragging');
    if (mount.hasPointerCapture?.(event.pointerId)) mount.releasePointerCapture(event.pointerId);
  };
  mount.addEventListener('pointerup', stopDragging);
  mount.addEventListener('pointercancel', stopDragging);
  mount.addEventListener('mouseenter', () => { hovering = true; });
  mount.addEventListener('mouseleave', () => { hovering = false; dragging = false; mount.classList.remove('is-dragging'); });

  const animate = (time) => {
    if (!previousTime) previousTime = time;
    const delta = Math.min(time - previousTime, 40);
    previousTime = time;
    if (!reduceMotion && !dragging && !hovering) render();
    requestAnimationFrame(animate);
  };

  render();
  requestAnimationFrame(animate);
}
