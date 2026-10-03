import type React from "react";
import { LENS, type Framing } from "../camera";
import { AT, beat, DIP, LINEUP, lineupReel, takeFrame } from "../edit";
import { clamp01, easeOut, easeSmooth, lerp, progress } from "../math";
import { DP_WIDTH, takeOf, type DeviceId, type TakeId, type Theme } from "../takes";
import { COLOR } from "../theme";
import { Device, deviceMargin, screenHeight } from "./Device";
import { Headline } from "./Headline";
import { Screen } from "./Screen";
import { GRAIN, lightAt } from "./Stage";

type Slot = { device: DeviceId; take: TakeId; x: number; y: number; width: number };
type Box = { left: number; top: number; right: number; bottom: number };

/**
 * Where each device stands once all three have landed, every screen at the same size a dp so they read as one app at
 * three sizes, back to front: a row standing on one line in a wide frame; in a tall one, the foldable and the phone
 * standing in front of the tablet.
 */
function slots(framing: Framing, theme: Theme, width: number, height: number): Slot[] {
  const size = (device: DeviceId, dpPx: number) => {
    const take = takeOf(device, theme);
    const w = DP_WIDTH[device] * dpPx;
    return { take, w, h: screenHeight(take, w), m: deviceMargin(take, w) };
  };
  if (framing === "wide") {
    const dpPx = 0.6;
    const gap = 64;
    const floor = height * 0.9;
    const sizes = LINEUP.map((device) => size(device, dpPx));
    let left = (width - sizes.reduce((sum, s) => sum + s.w + 2 * s.m, 0) - gap * (LINEUP.length - 1)) / 2;
    return LINEUP.map((device, i) => {
      const s = sizes[i]!;
      const slot = { device, take: s.take, x: left + s.m, y: floor - s.h, width: s.w };
      left += s.w + 2 * s.m + gap;
      return slot;
    });
  }
  const dpPx = 0.74;
  const gap = 40;
  /** How far the phone's top stands over the tablet's bottom. */
  const overlap = 150;
  const tablet = size("tablet", dpPx);
  const foldable = size("foldable", dpPx);
  const phone = size("phone", dpPx);
  const top = height * 0.28;
  const floor = top + tablet.h + 2 * tablet.m - overlap + phone.h + phone.m;
  const row = foldable.w + 2 * foldable.m + gap + phone.w + 2 * phone.m;
  const rowLeft = (width - row) / 2;
  return [
    { device: "tablet", take: tablet.take, x: (width - tablet.w) / 2, y: top + tablet.m, width: tablet.w },
    { device: "foldable", take: foldable.take, x: rowLeft + foldable.m, y: floor - foldable.h, width: foldable.w },
    { device: "phone", take: phone.take, x: rowLeft + foldable.w + 2 * foldable.m + gap + phone.m, y: floor - phone.h, width: phone.w },
  ];
}

/** The box around [slots], glass and all. */
function boxOf(slots: Slot[]): Box {
  const box = { left: Infinity, top: Infinity, right: -Infinity, bottom: -Infinity };
  for (const s of slots) {
    const m = deviceMargin(s.take, s.width);
    box.left = Math.min(box.left, s.x - m);
    box.top = Math.min(box.top, s.y - m);
    box.right = Math.max(box.right, s.x + s.width + m);
    box.bottom = Math.max(box.bottom, s.y + screenHeight(s.take, s.width) + m);
  }
  return box;
}

/**
 * Where the camera frames the first device to land: fractions of the frame to fit it inside, under the headline, and
 * the closest it comes.
 */
const ROOM: Record<Framing, { top: number; bottom: number; side: number; zoom: number }> = {
  wide: { top: 0.25, bottom: 0.96, side: 0.08, zoom: 1.4 },
  tall: { top: 0.27, bottom: 0.97, side: 0.06, zoom: 1.9 },
};

/** The camera on the lineup: its point [cx, cy] as it stands, shown at [x, y] of the frame at [zoom] times its size. */
type View = { zoom: number; cx: number; cy: number; x: number; y: number };

/**
 * The view once the first [n] devices to land have: in on those, centred in the room at the size that fits them there
 * (up to [maxZoom]); or, once they would fit no larger than they stand, as all three do, the lineup as it stands.
 */
function shot(all: Slot[], n: number, room: Box, maxZoom: number): View {
  const box = boxOf(all.filter((s) => LINEUP.indexOf(s.device) < n));
  const cx = (box.left + box.right) / 2;
  const cy = (box.top + box.bottom) / 2;
  const fit = Math.min((room.right - room.left) / (box.right - box.left), (room.bottom - room.top) / (box.bottom - box.top));
  if (n >= LINEUP.length || fit <= 1) return { zoom: 1, cx, cy, x: cx, y: cy };
  return { zoom: Math.min(maxZoom, fit), cx, cy, x: (room.left + room.right) / 2, y: (room.top + room.bottom) / 2 };
}

const landAt = (i: number) => AT.lineup + beat(i);

/** Frames a device takes to land, and how far it rises as it does. */
const LAND = 14;
const LAND_RISE = 60;

/**
 * The camera's turn round the lineup, at an even pace from cut to cut: from the first [turn] to the second about the
 * upright and from the first [tilt] to the second about the crosswise axis, in degrees, and from the first [scale] to the
 * second, drifting back as it turns so the row, which stands near the frame's edges, stays inside it; all about the
 * middle of the frame.
 */
const ORBIT: Record<Framing, { turn: [number, number]; tilt: [number, number]; scale: [number, number] }> = {
  wide: { turn: [-10, 12], tilt: [6, 0], scale: [1.02, 0.94] },
  tall: { turn: [9, -11], tilt: [5, 0], scale: [1.02, 0.95] },
};

/**
 * The view at frame [f]: in on the first device as it lands, pulling back in one move, from rest to rest, to all of
 * them by the time the last lands; even in scale, so the pull reads at one pace.
 */
function viewAt(framing: Framing, all: Slot[], width: number, height: number, f: number): View {
  const r = ROOM[framing];
  const room = { left: width * r.side, right: width * (1 - r.side), top: height * r.top, bottom: height * r.bottom };
  const from = shot(all, 1, room, r.zoom);
  const to = shot(all, LINEUP.length, room, r.zoom);
  const p = easeSmooth(progress(f, landAt(0), landAt(LINEUP.length - 1)));
  return {
    zoom: from.zoom * (to.zoom / from.zoom) ** p,
    cx: lerp(from.cx, to.cx, p),
    cy: lerp(from.cy, to.cy, p),
    x: lerp(from.x, to.x, p),
    y: lerp(from.y, to.y, p),
  };
}

/**
 * The three devices landing one a beat, each named as it lands, all playing the same moment of the run in step: the
 * camera in on the phone, pulling back in one long move as the foldable and the tablet land beside it, and turning
 * round all three; then the stage closes over them into the end card.
 */
export const Lineup: React.FC<{ framing: Framing; theme: Theme; f: number; width: number; height: number }> = ({
  framing,
  theme,
  f,
  width,
  height,
}) => {
  const size = framing === "wide" ? 104 : 112;
  const lines = framing === "wide" ? ["Phone. Foldable. Tablet."] : ["Phone.", "Foldable.", "Tablet."];
  const all = slots(framing, theme, width, height);
  const view = viewAt(framing, all, width, height, f);
  const orbit = ORBIT[framing];
  const p = progress(f, AT.lineup, AT.end);
  const origin = `${width / 2}px ${height / 2}px`;
  const light = lightAt(f, width, height);
  const dim = easeSmooth(progress(f, AT.end - DIP - 1, AT.end - 1));
  return (
    <>
      <div style={{ position: "absolute", inset: 0 }}>
        <div style={{ position: "absolute", inset: 0, perspective: LENS[framing].perspective, perspectiveOrigin: origin }}>
          <div
            style={{
              position: "absolute",
              inset: 0,
              transformOrigin: origin,
              transform: `rotateX(${lerp(...orbit.tilt, p)}deg) rotateY(${lerp(...orbit.turn, p)}deg) scale(${lerp(...orbit.scale, p)})`,
            }}
          >
            {all.map((slot) => {
              const i = LINEUP.indexOf(slot.device);
              const landed = easeOut(progress(f, landAt(i), landAt(i) + LAND));
              const x = view.x + (slot.x - view.cx) * view.zoom;
              const y = view.y + (slot.y - view.cy) * view.zoom;
              const w = slot.width * view.zoom;
              return (
                <div
                  key={slot.device}
                  style={{
                    position: "absolute",
                    inset: 0,
                    // The phone is there on the cut; the others come up out of the stage as they land.
                    opacity: i === 0 ? 1 : clamp01((f - landAt(i) + 1) / 3),
                    transform: `translateY(${(1 - landed) * LAND_RISE}px) scale(${0.95 + 0.05 * landed})`,
                    transformOrigin: `${x + w / 2}px ${y + screenHeight(slot.take, w)}px`,
                  }}
                >
                  <Device take={slot.take} x={x} y={y} width={w} light={light}>
                    <Screen take={slot.take} frame={takeFrame(lineupReel(slot.take), f)} width={w} />
                  </Device>
                </div>
              );
            })}
          </div>
        </div>
        <Headline
          lines={lines}
          f={f}
          at={AT.lineup}
          size={size}
          x={0}
          y={framing === "wide" ? height * 0.11 : height * 0.075}
          align="center"
          wordAt={LINEUP.map((_, i) => landAt(i))}
        />
      </div>
      {dim > 0 ? (
        <div style={{ position: "absolute", inset: 0, opacity: dim }}>
          <div style={{ position: "absolute", inset: 0, background: COLOR.stage }} />
          {/* The stage's grain over the stage it closes to, so the fall to near-black dithers instead of banding. */}
          <div style={{ position: "absolute", inset: 0, backgroundImage: GRAIN, opacity: 0.045, mixBlendMode: "screen" }} />
        </div>
      ) : null}
    </>
  );
};
