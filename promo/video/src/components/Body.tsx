import type React from "react";
import type { CSSProperties } from "react";
import { edgeFill, type Metal, type ToCamera, type V3 } from "../light";

/** Corner radii, top left, top right, bottom right, bottom left, as seen from the front. */
export type Radii = [number, number, number, number];

const RAD = Math.PI / 180;

/**
 * The key light hangs above the devices, so less of it reaches an edge the further down the device it runs: without
 * this, a wall turned into the light lights up evenly end to end, a flat bar rather than metal.
 */
const FALLOFF = 0.34;
const falloff = (y: number, h: number) => `rgba(0, 0, 0, ${(FALLOFF * Math.min(1, Math.max(0, y / h + 0.5))).toFixed(3)})`;

/** A piece of the rim: its middle on the outline, its length along it, the way its outside faces (degrees in x, y). */
type Strip = { x: number; y: number; len: number; phi: number };

/** The outline of a [w] x [h] rounded rectangle centred on the origin, as straight walls and short chords round the corners. */
function rim(w: number, h: number, [tl, tr, br, bl]: Radii): Strip[] {
  const hw = w / 2;
  const hh = h / 2;
  const out: Strip[] = [];
  const wall = (x0: number, y0: number, x1: number, y1: number, phi: number) => {
    const len = Math.hypot(x1 - x0, y1 - y0);
    // A hair longer than the gap it spans, so the pieces meet without a seam of the room showing through.
    if (len > 0.01) out.push({ x: (x0 + x1) / 2, y: (y0 + y1) / 2, len: len + 0.25, phi });
  };
  const corner = (cx: number, cy: number, r: number, from: number) => {
    if (r <= 0.05) return;
    const n = Math.max(2, Math.ceil(r / 1.3));
    for (let i = 0; i < n; i++) {
      const phi = from + ((i + 0.5) * 90) / n;
      out.push({
        x: cx + r * Math.cos(phi * RAD),
        y: cy + r * Math.sin(phi * RAD),
        len: 2 * r * Math.sin((45 / n) * RAD) + 0.25,
        phi,
      });
    }
  };
  wall(-hw + tl, -hh, hw - tr, -hh, -90);
  corner(hw - tr, -hh + tr, tr, -90);
  wall(hw, -hh + tr, hw, hh - br, 0);
  corner(hw - br, hh - br, br, 0);
  wall(hw - br, hh, -hw + bl, hh, 90);
  corner(-hw + bl, hh - bl, bl, 90);
  wall(-hw, hh - bl, -hw, -hh + tl, 180);
  corner(-hw + tl, -hh + tl, tl, 180);
  return out;
}

/**
 * A device's slab, centred on its parent's origin: the front face at z = 0, the back [depth] behind it and the metal rim
 * between, lit through [camera]. Sizes are in the device's units, [unit] DOM pixels each; the faces hold 2D content laid
 * out from their top left in those pixels.
 */
export const Body: React.FC<{
  w: number;
  h: number;
  depth: number;
  radii: Radii;
  unit: number;
  metal: Metal;
  camera: ToCamera;
  front?: React.ReactNode;
  back?: React.ReactNode;
  frontColor?: string;
  backColor?: string;
}> = ({ w, h, depth, radii, unit, metal, camera, front, back, frontColor = "#050505", backColor = "#161618" }) => {
  const px = (v: number) => v * unit;
  const corners = (r: Radii) => r.map((v) => `${px(v)}px`).join(" ");
  const face: CSSProperties = {
    position: "absolute",
    left: px(-w / 2),
    top: px(-h / 2),
    width: px(w),
    height: px(h),
    overflow: "hidden",
    backfaceVisibility: "hidden",
  };
  return (
    <>
      <div
        style={{
          ...face,
          // Seen from behind, the front's right corners are on the left.
          borderRadius: corners([radii[1], radii[0], radii[3], radii[2]]),
          background: backColor,
          transform: `translateZ(${px(-depth)}px) rotateY(180deg)`,
        }}
      >
        {back}
      </div>
      {rim(w, h, radii).map((s, i) => {
        const outward: V3 = [Math.cos(s.phi * RAD), Math.sin(s.phi * RAD), 0];
        // Once turned, the strip's length runs along (-sin, cos) of its facing: its right-hand end sits this far below its middle.
        const along = Math.cos(s.phi * RAD) * (s.len / 2);
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: px(-s.len / 2),
              top: px(-depth / 2),
              width: px(s.len),
              height: px(depth),
              backfaceVisibility: "hidden",
              background: `linear-gradient(to right, ${falloff(s.y - along, h)}, ${falloff(s.y + along, h)}), ${edgeFill(metal, camera(outward))}`,
              // Stood on edge (its top toward the back), turned to face outward, and set on the outline.
              transform: `translate3d(${px(s.x)}px, ${px(s.y)}px, ${px(-depth / 2)}px) rotateZ(${s.phi + 90}deg) rotateX(90deg)`,
            }}
          />
        );
      })}
      <div style={{ ...face, borderRadius: corners(radii), background: frontColor, transform: "translateZ(0px)" }}>
        {front}
      </div>
    </>
  );
};

/** A face's sheen: a soft band of reflected light that slides across the glass as the camera turns. */
export const Sheen: React.FC<{ turn: { rx: number; ry: number }; strength?: number }> = ({ turn, strength = 1 }) => {
  const at = 42 - turn.ry * 1.6 + turn.rx * 0.8;
  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        pointerEvents: "none",
        background: `linear-gradient(118deg, rgba(255,255,255,0) ${at - 22}%, rgba(255,255,255,${0.045 * strength}) ${at}%, rgba(255,255,255,${
          0.012 * strength
        }) ${at + 9}%, rgba(255,255,255,0) ${at + 20}%)`,
      }}
    />
  );
};
