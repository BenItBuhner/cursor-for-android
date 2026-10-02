/**
 * Shading for the devices' metal edges. CSS has no lights, so each strip of an edge is coloured for where its normal
 * points once the device's parts and the camera have turned it: the same rotations the stage applies, in its axes
 * (x right, y down, z toward the viewer).
 */

export type V3 = [number, number, number];
export type Turn = { rx: number; ry: number; rz: number };

const RAD = Math.PI / 180;

export const rotX = ([x, y, z]: V3, a: number): V3 => {
  const c = Math.cos(a * RAD);
  const s = Math.sin(a * RAD);
  return [x, c * y - s * z, s * y + c * z];
};
export const rotY = ([x, y, z]: V3, a: number): V3 => {
  const c = Math.cos(a * RAD);
  const s = Math.sin(a * RAD);
  return [c * x + s * z, y, -s * x + c * z];
};
export const rotZ = ([x, y, z]: V3, a: number): V3 => {
  const c = Math.cos(a * RAD);
  const s = Math.sin(a * RAD);
  return [c * x - s * y, s * x + c * y, z];
};

/** Takes a direction on a part of the device to the camera's axes: the part's own turn first, then the camera's. */
export type ToCamera = (n: V3) => V3;

/** The camera's turn as the stage applies it (`rotateX rotateY rotateZ`), after [local]. */
export const toCamera =
  (turn: Turn, local: ToCamera = (n) => n): ToCamera =>
  (n) =>
    rotX(rotY(rotZ(local(n), turn.rz), turn.ry), turn.rx);

const norm = (v: V3): V3 => {
  const l = Math.hypot(v[0], v[1], v[2]);
  return [v[0] / l, v[1] / l, v[2] / l];
};
const dot = (a: V3, b: V3) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

/** A key light above and a little right of the camera, a dim fill from the left, the viewer straight ahead. */
const KEY = norm([0.35, -0.85, 0.5]);
const FILL = norm([-0.75, 0.1, 0.45]);
const HALF = norm([KEY[0], KEY[1], KEY[2] + 1]);

export type Metal = { base: V3; ambient: number; key: number; fill: number; spec: number; shine: number };

/** Anodised aluminium in the dark finishes the devices come in. */
export const GRAPHITE: Metal = { base: [66, 67, 72], ambient: 0.42, key: 0.78, fill: 0.3, spec: 72, shine: 30 };

const channel = (v: number) => Math.round(Math.max(0, Math.min(255, v)));
const rgb = (c: V3, k = 1, add = 0) => `rgb(${channel(c[0] * k + add)}, ${channel(c[1] * k + add)}, ${channel(c[2] * k + add)})`;

function lit(m: Metal, n: V3): { color: V3; glint: number } {
  const light = m.ambient + m.key * Math.max(0, dot(n, KEY)) + m.fill * Math.max(0, dot(n, FILL));
  const glint = m.spec * Math.pow(Math.max(0, dot(n, HALF)), m.shine);
  return { color: [m.base[0] * light + glint, m.base[1] * light + glint, m.base[2] * light + glint], glint };
}

/**
 * An edge strip's fill from its back (the top of the strip's box) to its front: the band rounding off behind, the flat
 * of the frame, the chamfer catching the light where it meets the glass, and the glass's own dark rim.
 */
export function edgeFill(m: Metal, n: V3): string {
  const { color, glint } = lit(m, n);
  return `linear-gradient(to bottom, ${rgb(color, 0.5)} 0%, ${rgb(color, 0.92)} 38%, ${rgb(color, 1.04)} 76%, ${rgb(
    color,
    1.28,
    16 + glint * 0.35,
  )} 89%, ${rgb(color, 0.62)} 95%, rgb(8, 8, 9) 100%)`;
}
