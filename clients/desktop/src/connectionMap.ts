/** Equirectangular fit of world-map.svg (viewBox 0 0 1200 600), matching Android ConnectionMap.kt. */

export const WORLD_MAP_WIDTH = 1200;
export const WORLD_MAP_HEIGHT = 600;

/** Asunción. Same constants as Android ConnectionMap.kt. */
export const PARAGUAY_NODE = { lat: -25.2867, lng: -57.3333 };

/**
 * Interior of the drawn Paraguay shape, used when geolocation is denied.
 * A few degrees east of Asunción so the device pin stays on land and does not
 * sit on top of the node.
 */
export const DEVICE_LOCATION_FALLBACK = { lat: -24.0, lng: -56.1 };

export type MapPoint = { x: number; y: number };
export type LatLng = { lat: number; lng: number };

export function plausibleLatLng(
  lat: number | null | undefined,
  lng: number | null | undefined,
): LatLng | null {
  if (lat == null || lng == null) return null;
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
  return { lat, lng };
}

/** x = (lng+180)/360*width, y = (90-lat)/180*height. */
export function projectLatLng(lat: number, lng: number): MapPoint {
  return {
    x: ((lng + 180) / 360) * WORLD_MAP_WIDTH,
    y: ((90 - lat) / 180) * WORLD_MAP_HEIGHT,
  };
}

/** Cubic lift matching Android routePath so the dash stays visible for short hops. */
export function routeCurve(start: MapPoint, end: MapPoint): string {
  const lift = Math.max(36, Math.abs(end.x - start.x) * 0.22);
  const n = (value: number) => value.toFixed(1);
  return `M ${n(start.x)} ${n(start.y)} C ${n(start.x)} ${n(start.y - lift)}, ${n(end.x)} ${n(end.y - lift)}, ${n(end.x)} ${n(end.y)}`;
}

export type LabelPlace = "above" | "below" | "left" | "right";

function sideWithRoom(point: MapPoint, prefer: "left" | "right"): LabelPlace {
  const xPct = point.x / WORLD_MAP_WIDTH;
  if (prefer === "left" && xPct < 0.36) return "above";
  if (prefer === "right" && xPct > 0.64) return "above";
  return prefer;
}

function clearOfChrome(point: MapPoint, place: LabelPlace): LabelPlace {
  const yPct = point.y / WORLD_MAP_HEIGHT;
  if (place === "above" && yPct < 0.3) return "below";
  if (place === "below" && yPct > 0.76) return "above";
  return place;
}

/** Anchor each label beside its pin without covering the other pin or the map chrome. */
export function labelPlaces(device: MapPoint, server: MapPoint): { device: LabelPlace; server: LabelPlace } {
  const close = Math.hypot(device.x - server.x, device.y - server.y) < 90;
  if (close) {
    const low = device.y > WORLD_MAP_HEIGHT * 0.76 || server.y > WORLD_MAP_HEIGHT * 0.76;
    return low
      ? { device: clearOfChrome(device, "above"), server: clearOfChrome(server, "right") }
      : { device: clearOfChrome(device, "above"), server: clearOfChrome(server, "below") };
  }
  if (device.x <= server.x) {
    return {
      device: clearOfChrome(device, sideWithRoom(device, "left")),
      server: clearOfChrome(server, sideWithRoom(server, "right")),
    };
  }
  return {
    device: clearOfChrome(device, sideWithRoom(device, "right")),
    server: clearOfChrome(server, sideWithRoom(server, "left")),
  };
}
