import type { DeviceId } from "../takes";
import { LAMP, NIGHT, SHOT } from "./edit";
import { onGlass, toWorld, type Pose } from "./Hardware";
import type { Panes, TimeOfDay } from "./Room";

/**
 * Where everything is and where the camera looks, frame by frame: the phone's one place on the stone through the day,
 * the props around it, the three devices laid out for the lineup, and a camera path per run of the film between hard
 * cuts (a run can cross a shot boundary where the camera carries a move through it).
 */

export type Framing = "wide" | "tall";
export type V3 = [number, number, number];

/** The phone where it's put down in the morning; it never moves until the lineup. */
export const P0: Pose = { device: "phone", x: 0, y: 0, turn: 0.36 };

/** The lineup: the three on one heading, stepped back in depth, spaced for the Fold8 Ultra and Tab S10 Ultra. */
export const LINEUP: Record<DeviceId, Pose> = {
  phone: { device: "phone", x: -20, y: -14, turn: 0.32 },
  foldable: { device: "foldable", x: -6, y: 1, turn: 0.32 },
  tablet: { device: "tablet", x: 16, y: 18, turn: 0.32 },
};

/** The middle of each device's diff card at the locked take frame, as fractions across its screen. */
const DIFF: Record<DeviceId, [number, number]> = { phone: [0.5, 0.41], foldable: [0.25, 0.45], tablet: [0.41, 0.45] };
/** The diff card's width in centimetres on each device's glass over the phone's, so the cuts can hold it at one size. */
const CARD: Record<DeviceId, number> = { phone: 1, foldable: 0.925, tablet: 1.71 };

const G = (u: number, v: number, above = 0) => onGlass(P0, u, v, above);
const deg = (r: number) => (r * 180) / Math.PI;
/** A world azimuth from one measured round from [pose]'s bottom edge (0) toward its right side (+90). */
const rel = (pose: Pose, az: number) => deg(pose.turn) - 90 + az;

/**
 * A camera: orbiting [at] at azimuth [az] and elevation [el] (degrees) and [dist] centimetres, [fov] vertical, with
 * [at] placed at [frame] (fractions of the frame, by shifting the lens, so the subject sits beside the type rather than
 * dead centre). The lens is focused on [focus] (or [at]) with [depth] centimetres sharp about it and [bokeh] blur.
 */
export type Cam = {
  at: V3;
  az: number;
  el: number;
  dist: number;
  fov: number;
  frame: [number, number];
  focus?: V3;
  depth: number;
  bokeh: number;
  roll?: number;
};
export type Ease = "inOut" | "linear" | "in" | "out";
export type Key = Cam & { f: number; ease?: Ease };
type Run = { from: number; to: number; wide: Key[]; tall: Key[] };

/** The tall cut's camera from the wide one's: its own lens and frame position, the rest overridden per key. */
const tall = (k: Key, o: Partial<Key> = {}): Key => ({ ...k, fov: k.fov * 1.45, frame: [0.5, 0.6], ...o });

const mic = G(0.9, 0.42);
const prompt = G(0.42, 0.335);
const card = (v: number, above = 0) => G(0.5, v, above);
const composer = G(0.42, 0.89);
const centre = G(0.5, 0.5);
const notice = G(0.5, 0.34);
const LIFTED = 0.45;

const wake: Key[] = [
  { f: SHOT.wake.from, at: toWorld(P0, [2.6, 0.8, 0.5]), az: rel(P0, 24), el: 15, dist: 21, fov: 24, frame: [0.5, 0.55], focus: toWorld(P0, [3.56, 0.95, 0.42]), depth: 8, bokeh: 2.2 },
  { f: SHOT.wake.to - 1, at: toWorld(P0, [2.6, 1.2, 0.5]), az: rel(P0, 19), el: 16, dist: 17, fov: 24, frame: [0.5, 0.55], focus: toWorld(P0, [3.56, 1.3, 0.42]), depth: 7.5, bokeh: 2.2, ease: "linear" },
];

const titleWide: Cam = { at: centre, az: rel(P0, -14), el: 58, dist: 82, fov: 22, frame: [0.5, 0.78], depth: 22, bokeh: 1.6 };
const sayStart: Cam = { at: mic, az: rel(P0, -26), el: 36, dist: 21, fov: 24, frame: [0.68, 0.56], focus: prompt, depth: 12, bokeh: 2 };
const say: Key[] = [
  { f: SHOT.title.from, ...titleWide },
  { f: SHOT.title.to - 1, ...titleWide, dist: 78, ease: "linear" },
  { f: SHOT.say.from, ...sayStart },
  { f: SHOT.say.from + 36, ...sayStart },
  { f: SHOT.say.to - 1, ...sayStart, az: rel(P0, -8), el: 31, dist: 16.5, frame: [0.66, 0.56] },
];

/** One unbroken slow move: down the glass with the card as it lifts, then back off it, racking to the composer. */
const code: Key[] = [
  { f: SHOT.code.from, at: card(0.3), az: rel(P0, -18), el: 48, dist: 19, fov: 26, frame: [0.62, 0.5], focus: G(0.42, 0.3), depth: 14, bokeh: 2 },
  { f: 700, at: card(0.46, LIFTED), az: rel(P0, -12), el: 48, dist: 19, fov: 26, frame: [0.62, 0.5], focus: G(0.42, 0.46, LIFTED), depth: 14, bokeh: 2, ease: "linear" },
  { f: SHOT.code.to - 1, at: G(0.5, 0.62), az: rel(P0, -6), el: 45, dist: 21.5, fov: 26, frame: [0.62, 0.52], focus: composer, depth: 10, bokeh: 2.2, ease: "linear" },
];

const above: Cam = { at: centre, az: rel(P0, 0), el: 89.9, dist: 47, fov: 26, frame: [0.6, 0.5], focus: centre, depth: 32, bokeh: 1 };
const lastLive: Cam = { ...above, at: notice, dist: 37, frame: [0.62, 0.36] };
const steerLive: Key[] = [
  { f: SHOT.steer.from, at: G(0.56, 0.86), az: rel(P0, 28), el: 22, dist: 11.5, fov: 24, frame: [0.62, 0.6], focus: composer, depth: 9, bokeh: 2 },
  { f: SHOT.steer.to - 1, at: G(0.56, 0.82), az: rel(P0, 16), el: 24, dist: 10.5, fov: 24, frame: [0.62, 0.6], focus: G(0.42, 0.8), depth: 9, bokeh: 2, ease: "linear" },
  { f: SHOT.live.from, ...above },
  { f: NIGHT, ...above, dist: 45, ease: "linear" },
  { f: SHOT.live.to - 1, ...lastLive, ease: "linear" },
];

/** The time cut into the next morning: the night's last framing held, the sun back on the stone and the PR on the glass. */
const shipStart: Cam = lastLive;
const ship: Key[] = [
  { f: SHOT.ship.from, ...shipStart },
  { f: 1500, at: G(0.62, 0.735), az: rel(P0, -6), el: 74, dist: 29, fov: 26, frame: [0.62, 0.56], focus: G(0.56, 0.735), depth: 16, bokeh: 1.4 },
  { f: SHOT.ship.to - 1, at: G(0.6, 0.72), az: rel(P0, -14), el: 64, dist: 25, fov: 26, frame: [0.62, 0.56], focus: G(0.56, 0.735), depth: 14, bokeh: 1.5, ease: "linear" },
];

/** One of the three hero cuts: the diff card held at one place and size in the frame as the device around it grows. */
function hero(device: DeviceId, from: number, to: number, t: boolean): Key[] {
  const pose = LINEUP[device];
  const [u, v] = DIFF[device];
  const at = onGlass(pose, u, v);
  const k = CARD[device];
  const base: Cam = { at, az: rel(pose, -30), el: 30, dist: (t ? 25 : 22) * k, fov: t ? 34 : 24, frame: t ? [0.5, 0.6] : [0.6, 0.55], depth: 12 * k, bokeh: 2 };
  return [
    { f: from, ...base },
    { f: to - 1, ...base, az: rel(pose, -20), el: 33, dist: (t ? 23.3 : 20.5) * k, ease: "linear" },
  ];
}

const tableau: Cam = {
  at: [-10, -1, 0],
  az: -118,
  el: 22,
  dist: 92,
  fov: 21,
  frame: [0.5, 0.58],
  focus: onGlass(LINEUP.foldable, 0.5, 0.5),
  depth: 60,
  bokeh: 1.2,
};

/** Where the end card lies: an empty stretch of the stone, well away from the lineup, in its own band of sun. */
export const END_AT: V3 = [-70, -64, 0];
const end: Cam = { at: END_AT, az: -90, el: 89.9, dist: 70, fov: 30, frame: [0.5, 0.5], depth: 80, bokeh: 0.7 };

export const RUNS: Run[] = [
  { from: SHOT.wake.from, to: SHOT.wake.to, wide: wake, tall: wake.map((k) => tall(k, { az: k.az - 16, el: k.el + 8, fov: 32, frame: [0.5, 0.58] })) },
  {
    from: SHOT.title.from,
    to: SHOT.say.to,
    wide: say,
    tall: say.map((k, i) => (i < 2 ? tall(k, { dist: 110, frame: [0.5, 0.7] }) : tall(k, { frame: [0.6, 0.62], dist: k.dist * 1.15 }))),
  },
  { from: SHOT.code.from, to: SHOT.code.to, wide: code, tall: code.map((k) => tall(k, { frame: [0.55, 0.58], dist: k.dist * 1.15 })) },
  {
    from: SHOT.steer.from,
    to: SHOT.live.to,
    wide: steerLive,
    tall: steerLive.map((k, i) => (i < 2 ? tall(k, { at: G(0.44, 0.85), frame: [0.5, 0.6], dist: k.dist * 1.55, el: k.el + 4 }) : tall(k, { fov: 32, frame: [0.5, i === 4 ? 0.48 : 0.6] }))),
  },
  { from: SHOT.ship.from, to: SHOT.ship.to, wide: ship, tall: [] },
  { from: SHOT.phone.from, to: SHOT.phone.to, wide: hero("phone", SHOT.phone.from, SHOT.phone.to, false), tall: hero("phone", SHOT.phone.from, SHOT.phone.to, true) },
  { from: SHOT.foldable.from, to: SHOT.foldable.to, wide: hero("foldable", SHOT.foldable.from, SHOT.foldable.to, false), tall: hero("foldable", SHOT.foldable.from, SHOT.foldable.to, true) },
  { from: SHOT.tablet.from, to: SHOT.tablet.to, wide: hero("tablet", SHOT.tablet.from, SHOT.tablet.to, false), tall: hero("tablet", SHOT.tablet.from, SHOT.tablet.to, true) },
  {
    from: SHOT.tableau.from,
    to: SHOT.tableau.to,
    wide: [{ f: SHOT.tableau.from, ...tableau }, { f: SHOT.tableau.to - 1, ...tableau, az: -113, dist: 55, ease: "linear" }],
    tall: [
      { f: SHOT.tableau.from, ...tableau, fov: 34, dist: 124, el: 20, frame: [0.5, 0.6] },
      { f: SHOT.tableau.to - 1, ...tableau, fov: 34, dist: 116, el: 20, az: -115, frame: [0.5, 0.6], ease: "linear" },
    ],
  },
  { from: SHOT.end.from, to: SHOT.end.to, wide: [{ f: SHOT.end.from, ...end }, { f: SHOT.end.to - 1, ...end, dist: 66, ease: "linear" }], tall: [] },
];

// The tall ship keeps the time cut: its first frame is the tall live's last.
{
  const live = RUNS[3]!.tall;
  const last = live[live.length - 1]!;
  const start: Key = { ...last, f: SHOT.ship.from };
  RUNS[4]!.tall = [start, ...ship.slice(1).map((k) => tall(k, { frame: [0.55, 0.6], dist: k.dist * 1.2 }))];
  RUNS[9]!.tall = RUNS[9]!.wide.map((k) => tall(k, { fov: 30 * 1.5, frame: [0.5, 0.5] }));
}

const ease = (t: number, e: Ease = "inOut") => {
  if (e === "linear") return t;
  if (e === "in") return t * t * t;
  if (e === "out") return 1 - (1 - t) ** 3;
  return t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2;
};
const mix = (a: number, b: number, t: number) => a + (b - a) * t;
const mix3 = (a: V3, b: V3, t: number): V3 => [mix(a[0], b[0], t), mix(a[1], b[1], t), mix(a[2], b[2], t)];

/** The camera at frame [f] in [framing]. */
export function cameraAt(f: number, framing: Framing): Cam {
  const run = RUNS.find((r) => f >= r.from && f < r.to) ?? RUNS[0]!;
  const keys = run[framing];
  if (f <= keys[0]!.f) return keys[0]!;
  for (let i = 1; i < keys.length; i++) {
    const b = keys[i]!;
    if (f > b.f) continue;
    const a = keys[i - 1]!;
    const t = ease((f - a.f) / (b.f - a.f), b.ease);
    let daz = b.az - a.az;
    daz -= 360 * Math.round(daz / 360);
    return {
      at: mix3(a.at, b.at, t),
      az: a.az + daz * t,
      el: mix(a.el, b.el, t),
      dist: Math.exp(mix(Math.log(a.dist), Math.log(b.dist), t)),
      fov: mix(a.fov, b.fov, t),
      frame: [mix(a.frame[0], b.frame[0], t), mix(a.frame[1], b.frame[1], t)],
      focus: mix3(a.focus ?? a.at, b.focus ?? b.at, t),
      depth: mix(a.depth, b.depth, t),
      bokeh: mix(a.bokeh, b.bokeh, t),
      roll: mix(a.roll ?? 0, b.roll ?? 0, t),
    };
  }
  return keys[keys.length - 1]!;
}

/** The window for a stretch of the film: the sun's bands fall through [through], from a wall [distance] back. */
export type Glazing = { through: V3; distance: number; panes: Panes; roll: number; plant?: boolean };

const WIDE_PANES: Panes = { w: 26, h: 54, mullion: 4.5 };

/**
 * The set at frame [f]: the time of day, the shadow's extent, the window, and which devices are on the table.
 */
export type Set = {
  time: TimeOfDay;
  center: [number, number];
  span: number;
  glazing: Glazing;
  layout: "story" | "lineup" | "end";
  /** In the lineup, the devices on the table: each hero cut has only its own, the tableau all three. */
  devices: DeviceId[];
  lamp: number;
  /** Whether the motes in the beam are worth drawing: only in close, where a lens would pick them out. */
  dust: boolean;
};

export function setAt(f: number): Set {
  const story = (time: TimeOfDay, span: number, glazing: Glazing): Set => ({
    time,
    center: [0, 0],
    span,
    glazing,
    layout: "story",
    devices: [],
    lamp: time === "night" ? Math.max(0, Math.min(1, (f - LAMP) / 6)) : 0,
    dust: time !== "night" && !(f >= SHOT.live.from && f < SHOT.live.to),
  });
  const morning: Glazing = { through: [1, 1, 0], distance: 70, panes: WIDE_PANES, roll: 0 };
  if (f < SHOT.title.from) return story("morning", 40, morning);
  if (f < SHOT.say.from + 20) return story("morning", 110, morning);
  if (f < SHOT.code.from) return story("morning", 60, morning);
  if (f < SHOT.steer.from) return story("morning", 60, morning);
  if (f < NIGHT) return story("morning", 60, morning);
  if (f < SHOT.live.to) return story("night", 60, { through: [1, -1, 0], distance: 60, panes: { w: 10, h: 60, mullion: 2.6 }, roll: 0, plant: false });
  if (f < SHOT.ship.to) return story("next", 60, { through: [0.5, 2, 0], distance: 70, panes: WIDE_PANES, roll: 0 });
  const lineup = (time: TimeOfDay, through: V3, span: number, devices: DeviceId[], panes = WIDE_PANES): Set => ({
    time,
    center: [through[0], through[1]],
    span,
    glazing: { through, distance: 80, panes, roll: 0 },
    layout: "lineup",
    devices,
    lamp: 0,
    dust: time === "next",
  });
  const at = (pose: Pose): V3 => [pose.x, pose.y, 0];
  if (f < SHOT.foldable.from) return lineup("next", at(LINEUP.phone), 50, ["phone"]);
  if (f < SHOT.tablet.from) return lineup("next", at(LINEUP.foldable), 60, ["foldable"]);
  if (f < SHOT.tableau.from) return lineup("next", at(LINEUP.tablet), 90, ["tablet"]);
  if (f < SHOT.end.from) return lineup("next", [-3, 5, 0], 190, ["phone", "foldable", "tablet"], { w: 44, h: 80, mullion: 5 });
  return {
    time: "noon",
    center: [END_AT[0], END_AT[1]],
    span: 80,
    // The mullion's foot placed so the type lies in the upper right pane's light, the leaves in the other corner.
    glazing: { through: [END_AT[0] - 16.6 + ((f - SHOT.end.from) / (SHOT.end.to - SHOT.end.from)) * 3, END_AT[1] + 42, 0], distance: 90, panes: { w: 34, h: 70, mullion: 5 }, roll: 0 },
    layout: "end",
    devices: [],
    lamp: 0,
    dust: false,
  };
}
