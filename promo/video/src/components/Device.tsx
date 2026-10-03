import type React from "react";
import { takes, type DeviceId, type TakeId } from "../takes";
import { LEAN, LEAN_DEG } from "./Stage";

/** A device's glass around its screen, as fractions of the screen's shorter side. */
const GLASS: Record<DeviceId, { bezel: number; radius: number }> = {
  phone: { bezel: 0.021, radius: 0.105 },
  foldable: { bezel: 0.017, radius: 0.05 },
  tablet: { bezel: 0.034, radius: 0.05 },
};

const glassOf = (take: TakeId) => GLASS[takes[take].device];

/** The screen's height for [width] pixels across, as the take has it. */
export const screenHeight = (take: TakeId, width: number) => (width * takes[take].height) / takes[take].width;

/** How far the device reaches past its screen on each side, in pixels, for a screen [width] across. */
export function deviceMargin(take: TakeId, width: number): number {
  const short = Math.min(width, screenHeight(take, width));
  return short * glassOf(take).bezel + rimOf(short);
}

const rimOf = (short: number) => Math.max(1.5, short * 0.0045);

/**
 * [take]'s device, its screen [width] pixels across with its top left at [x, y] of the parent: thin black glass in a
 * graphite frame, the camera where that device has it, and [children] (the screen) inside.
 */
export const Device: React.FC<{
  take: TakeId;
  x: number;
  y: number;
  width: number;
  /** A point on the stage's beam of light ([lightAt]) in the parent's pixels, or null when there is none. */
  light?: { x: number; y: number } | null;
  children: React.ReactNode;
}> = ({ take, x, y, width, light = null, children }) => {
  const device = takes[take].device;
  const height = screenHeight(take, width);
  const short = Math.min(width, height);
  const bezel = short * glassOf(take).bezel;
  const radius = short * glassOf(take).radius;
  const rim = rimOf(short);
  const edge = bezel + rim;
  const outer = width + 2 * edge;
  // Where the beam crosses the device's middle row, from the device's left edge: the glint rides the beam itself.
  const beam = light === null ? null : light.x + LEAN * (light.y - (y + height / 2)) - (x - edge);
  const band = (alpha: number, blur: number) =>
    `linear-gradient(${90 + LEAN_DEG}deg, rgba(255,246,232,0) 0%, rgba(255,246,232,${alpha * 0.45}) ${50 - blur}%, rgba(255,246,232,${alpha}) 50%, rgba(255,246,232,${alpha * 0.45}) ${50 + blur}%, rgba(255,246,232,0) 100%)`;
  const glint = (alpha: number, blur: number, inset: number) =>
    beam === null ? null : (
      <div
        style={{
          position: "absolute",
          top: -outer * 0.3,
          bottom: -outer * 0.3,
          width: outer * 1.2,
          left: beam - inset - outer * 0.6,
          background: band(alpha, blur),
          pointerEvents: "none",
        }}
      />
    );
  return (
    <div
      style={{
        position: "absolute",
        left: x - edge,
        top: y - edge,
        width: outer,
        height: height + 2 * edge,
        borderRadius: radius + edge,
        // The frame, graphite, caught by the stage's light at its top left and bottom right, with a hair of rim light
        // all round so it stands off the dark stage.
        background: "linear-gradient(150deg, #a8a8b0 0%, #3c3c42 16%, #26262b 50%, #3a3a40 84%, #b0b0b8 100%)",
        boxShadow: `0 0 0 1px rgba(255,246,232,0.09), 0 ${short * 0.1}px ${short * 0.26}px -${short * 0.04}px rgba(0,0,0,0.7), 0 ${short * 0.02}px ${short * 0.05}px rgba(0,0,0,0.5), 0 0 ${short * 0.5}px rgba(255,236,210,0.06)`,
      }}
    >
      <div style={{ position: "absolute", inset: 0, borderRadius: radius + edge, overflow: "hidden" }}>{glint(0.85, 22, 0)}</div>
      {device === "phone" ? <PhoneButtons width={width} height={height} edge={edge} rim={rim} /> : null}
      <div style={{ position: "absolute", inset: rim, borderRadius: radius + bezel, background: "#050506" }} />
      <div style={{ position: "absolute", left: edge, top: edge, width, height, borderRadius: radius, overflow: "hidden", isolation: "isolate" }}>
        {children}
        {device === "foldable" ? <Crease width={width} height={height} /> : null}
        {device !== "tablet" ? <PunchHole take={take} width={width} height={height} /> : null}
        {/* The stage's light on the glass: a faint, fixed sheen from the top left, so the screen reads as a surface. */}
        <div
          style={{
            position: "absolute",
            inset: 0,
            background: "linear-gradient(118deg, rgba(255,246,232,0.045) 0%, rgba(255,246,232,0.014) 30%, rgba(255,246,232,0) 50%)",
            pointerEvents: "none",
          }}
        />
        {glint(0.1, 30, edge)}
      </div>
      {device === "tablet" ? (
        <div
          style={{
            position: "absolute",
            left: edge + width / 2 - short * 0.007,
            top: rim + bezel / 2 - short * 0.007,
            width: short * 0.014,
            height: short * 0.014,
            borderRadius: "50%",
            background: "radial-gradient(circle at 35% 35%, #2a3140 0%, #0b0d12 55%, #000 100%)",
          }}
        />
      ) : null}
    </div>
  );
};

/** The front camera's punch hole, centred in the status bar: over the phone's middle, and the foldable's right half. */
const PunchHole: React.FC<{ take: TakeId; width: number; height: number }> = ({ take, width, height }) => {
  const t = takes[take];
  const scale = width / t.width;
  const phone = t.device === "phone";
  const d = Math.min(width, height) * (phone ? 0.028 : 0.019);
  const cx = phone ? width / 2 : width * 0.8;
  const cy = ((t.statusBar[0] ?? 0) * scale) / 2;
  return (
    <div
      style={{
        position: "absolute",
        left: cx - d / 2,
        top: cy - d / 2,
        width: d,
        height: d,
        borderRadius: "50%",
        background: "radial-gradient(circle at 36% 34%, #262c3a 0%, #07080b 50%, #000 72%)",
        boxShadow: `0 0 0 ${d * 0.08}px #000`,
      }}
    />
  );
};

/** Where the foldable's inner screen bends: a shadow and a highlight a few pixels wide down its middle. */
const Crease: React.FC<{ width: number; height: number }> = ({ width, height }) => {
  const w = Math.max(4, width * 0.012);
  return (
    <div
      style={{
        position: "absolute",
        left: width / 2 - w / 2,
        top: 0,
        width: w,
        height,
        background: "linear-gradient(90deg, rgba(255,255,255,0) 0%, rgba(255,255,255,0.035) 30%, rgba(0,0,0,0.16) 55%, rgba(255,255,255,0.025) 75%, rgba(255,255,255,0) 100%)",
        pointerEvents: "none",
      }}
    />
  );
};

/** The power key and the volume rocker on the phone's right edge, standing a hair proud of the frame. */
const PhoneButtons: React.FC<{ width: number; height: number; edge: number; rim: number }> = ({ width, height, edge, rim }) => {
  const depth = Math.max(1.5, rim * 1.1);
  const key = (top: number, length: number) => (
    <div
      style={{
        position: "absolute",
        left: width + 2 * edge - rim * 0.3,
        top: edge + height * top,
        width: depth,
        height: height * length,
        borderRadius: `0 ${depth}px ${depth}px 0`,
        background: "linear-gradient(90deg, #3a3a40, #6a6a71)",
      }}
    />
  );
  return (
    <>
      {key(0.2, 0.065)}
      {key(0.31, 0.12)}
    </>
  );
};
