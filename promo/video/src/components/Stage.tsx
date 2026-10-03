import type React from "react";
import { CARRIES, CARRY, carryFrom, SWEEPS } from "../edit";
import { easeOut, lerp, progress } from "../math";
import { COLOR } from "../theme";

/** Frames a sweep takes to cross. */
const SWEEP = 46;

/** How wide the band of light is, as a fraction of the frame. */
const BAND = 0.55;

/** The band's skew off the upright, in degrees. */
const SKEW = -14;

/** The cut the light at [f] is carrying, or null when none is. */
export function carryAt(f: number): number | null {
  for (const at of CARRIES) if (f >= carryFrom(at) && f < carryFrom(at) + CARRY.frames) return at;
  return null;
}

/** Where the beam down the light's middle is at [f], as a fraction of the frame's width from its left edge, or null. */
function beamFraction(f: number): number | null {
  const at = carryAt(f);
  // At an even pace: it sets out and comes to rest off the frame, so nothing on it starts or stops.
  if (at !== null) return lerp(-0.3, 1.3, progress(f, carryFrom(at), carryFrom(at) + CARRY.frames));
  for (const s of SWEEPS) if (f >= s && f < s + SWEEP) return -BAND / 2 + (1 + 2 * BAND) * easeOut(progress(f, s, s + SWEEP));
  return null;
}

/** How far across the frame the light is at [f], 0 off its left edge to 1 off its right, or null when there is none. */
export function sweepAt(f: number): number | null {
  const beam = beamFraction(f);
  return beam === null ? null : (beam + BAND / 2) / (1 + 2 * BAND);
}

/** Where the beam down the light's middle is at [f], in pixels from the frame's left edge, or null when there is none. */
export function beamAt(f: number, width: number): number | null {
  const beam = beamFraction(f);
  return beam === null ? null : beam * width;
}

/** How far the beam leans off the upright, its top to the right, in degrees; and how far right per pixel up the frame. */
export const LEAN_DEG = -SKEW;
export const LEAN = Math.tan((LEAN_DEG * Math.PI) / 180);

/** A point on the beam at [f], in the pixels of a [width] by [height] frame: where it crosses the frame's middle row. */
export function lightAt(f: number, width: number, height: number): { x: number; y: number } | null {
  const x = beamAt(f, width);
  return x === null ? null : { x, y: height / 2 };
}

/**
 * The wipe the light carries at [f]: masks for the layer going out and the layer coming in, in the frame of a wrapper
 * skewed like the band (see [Wiped]), so the seam runs down the beam. The one going out stands solid until the beam
 * reaches it and burns out under its core; the one coming in comes up out of the stage in the beam's wake. The two
 * never both show at a point: between them, only the stage and the light. Null when no light is carrying a cut.
 */
export function wipeAt(f: number, width: number): { at: number; out: string; in: string } | null {
  const at = carryAt(f);
  if (at === null) return null;
  const beam = beamAt(f, width)!;
  const px = (v: number) => `${v.toFixed(0)}px`;
  // A gap of bare stage under the light between the two, so where they overlap they never read as one exposed on the other.
  const gap = width * 0.12;
  return {
    at,
    out: `linear-gradient(90deg, rgba(0,0,0,0) ${px(beam)}, #000 ${px(beam + width * 0.08)})`,
    in: `linear-gradient(90deg, #000 ${px(beam - gap - width * 0.14)}, rgba(0,0,0,0) ${px(beam - gap)})`,
  };
}

/**
 * A layer under a [wipeAt] mask: the mask is laid on a wrapper skewed like the band of light, about the frame's middle,
 * and the layer inside skewed back, so the seam leans with the beam while the layer stands as it did.
 */
export const Wiped: React.FC<{ mask: string | undefined; children: React.ReactNode }> = ({ mask, children }) =>
  mask === undefined ? (
    <>{children}</>
  ) : (
    <div style={{ position: "absolute", inset: 0, transform: `skewX(${SKEW}deg)`, WebkitMaskImage: mask, maskImage: mask }}>
      <div style={{ position: "absolute", inset: 0, transform: `skewX(${-SKEW}deg)` }}>{children}</div>
    </div>
  );

/** A tile of fine, fixed grain, so the stage's gradients read as a surface rather than a fill. */
export const GRAIN = `url("data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240"><filter id="g"><feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" stitchTiles="stitch" seed="7"/><feColorMatrix values="0 0 0 0 1  0 0 0 0 0.97  0 0 0 0 0.92  0 0 0 0.9 0"/></filter><rect width="240" height="240" filter="url(#g)"/></svg>`,
)}")`;

/**
 * The stage: warm near-black, lit from a little above its middle and falling off to the edges, with a fixed grain over
 * it, and a soft band of light that sweeps across it on each act's first beat.
 */
export const Stage: React.FC<{ f: number; width: number; height: number }> = ({ f, width, height }) => {
  const sweep = sweepAt(f);
  const band = width * BAND;
  // A light carrying a cut burns brighter than a sweep, so the seam reads as light, not as an edge.
  const glow = carryAt(f) !== null ? 2.4 : 1;
  const light = (a: number) => `rgba(255,243,226,${(a * glow).toFixed(3)})`;
  return (
    <div style={{ position: "absolute", inset: 0, background: COLOR.stage, overflow: "hidden" }}>
      <div
        style={{
          position: "absolute",
          inset: 0,
          background: `radial-gradient(ellipse 62% 56% at 50% 42%, ${COLOR.stageLit} 0%, ${COLOR.stage} 100%)`,
        }}
      />
      <div style={{ position: "absolute", inset: 0, backgroundImage: GRAIN, opacity: 0.045, mixBlendMode: "screen" }} />
      {sweep !== null ? (
        <div
          style={{
            position: "absolute",
            top: -height * 0.4,
            height: height * 1.8,
            width: band,
            left: -band + (width + 2 * band) * sweep,
            transform: `skewX(${SKEW}deg)`,
            // A soft wash with a narrow, brighter core down its middle, so the light reads as a beam rather than a fog.
            background: [
              ...(glow > 1 ? [`linear-gradient(90deg, ${light(0)} 46%, rgba(255,247,236,0.22) 50%, ${light(0)} 54%)`] : []),
              `linear-gradient(90deg, ${light(0)} 42%, ${light(0.07)} 47%, ${light(0.12)} 50%, ${light(0.07)} 53%, ${light(0)} 58%)`,
              `linear-gradient(90deg, ${light(0)} 0%, ${light(0.06)} 35%, ${light(0.12)} 50%, ${light(0.06)} 65%, ${light(0)} 100%)`,
            ].join(", "),
          }}
        />
      ) : null}
      <div
        style={{
          position: "absolute",
          inset: 0,
          background: "radial-gradient(ellipse 90% 85% at 50% 50%, rgba(0,0,0,0) 55%, rgba(0,0,0,0.45) 100%)",
        }}
      />
    </div>
  );
};