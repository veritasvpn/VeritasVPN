/**
 * Shared OpenStreetMap (Leaflet) helper for /check IP views.
 * Expects Leaflet globals from /assets/leaflet/leaflet.js.
 */

let activeMap = null;

function buildLabel({ ip, city, region, country } = {}) {
  const place = [city, region, country].filter(Boolean).join(", ");
  if (ip && place) return `${ip} · ${place}`;
  if (ip) return String(ip);
  return place || "Approximate location";
}

/**
 * @param {HTMLElement | null} container
 * @param {{ latitude?: number|null, longitude?: number|null, ip?: string|null, city?: string|null, region?: string|null, country?: string|null }} data
 * @param {{ unavailableEl?: HTMLElement | null }} [opts]
 * @returns {boolean} whether a map was rendered
 */
export function renderIpMap(container, data = {}, opts = {}) {
  const unavailableEl = opts.unavailableEl || null;
  const lat = Number(data.latitude);
  const lng = Number(data.longitude);
  const hasCoords = Number.isFinite(lat) && Number.isFinite(lng);

  if (!container) return false;

  if (!hasCoords || typeof window.L === "undefined") {
    container.hidden = true;
    container.replaceChildren();
    if (unavailableEl) {
      unavailableEl.textContent = hasCoords
        ? "Map library failed to load."
        : "Map unavailable for this request.";
      unavailableEl.classList.add("is-visible");
    }
    if (activeMap) {
      activeMap.remove();
      activeMap = null;
    }
    return false;
  }

  if (unavailableEl) {
    unavailableEl.classList.remove("is-visible");
    unavailableEl.textContent = "";
  }
  container.hidden = false;

  if (activeMap) {
    activeMap.remove();
    activeMap = null;
  }
  container.replaceChildren();

  // Leaflet default icon paths resolve relative to the CSS location.
  const Icon = window.L.Icon.Default;
  Icon.mergeOptions({
    iconUrl: "/assets/leaflet/images/marker-icon.png",
    iconRetinaUrl: "/assets/leaflet/images/marker-icon-2x.png",
    shadowUrl: "/assets/leaflet/images/marker-shadow.png",
  });

  const map = window.L.map(container, {
    zoomControl: true,
    attributionControl: true,
    scrollWheelZoom: false,
  }).setView([lat, lng], 10);

  window.L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 18,
    attribution:
      '&copy; <a href="https://www.openstreetmap.org/copyright" rel="noopener noreferrer" target="_blank">OpenStreetMap</a>',
  }).addTo(map);

  const label = buildLabel(data);
  window.L.marker([lat, lng]).addTo(map).bindPopup(label);

  // Ensure correct sizing after becoming visible.
  window.setTimeout(() => map.invalidateSize(), 0);
  activeMap = map;
  return true;
}
