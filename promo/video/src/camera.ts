import { deviceMargin, screenHeight } from "./components/Device";
import { AT, HEROES, MOMENTS, SHADE, THEME, whenShown, type Reel } from "./edit";
import { mark, stream, type TakeId, type Theme } from "./takes";

export type Framing = "wide" | "tall";

/** Each framing's frame, in pixels. */
export const FRAME: Record<Framing, { width: number; height: number }> = {
  wide: { width: 1920, height: 1080 },
  tall: { width: 1080, height: 1920 },
};

/** Where the tall frame has the top of the phone's screen at rest, the headline standing over it: a fraction of its height. */
export const TALL_TOP = 460 / 1920;

/**
 * The phone's screen width at zoom 1 in each framing, and how far the eye stands from the frame, in pixels: far enough
 * that a turned phone keeps its shape, near enough that its far edge reads as further away.
 */
export const LENS: Record<Framing, { rest: number; perspective: number }> = {
  wide: { rest: 404, perspective: 2600 },
  tall: { rest: 614, perspective: 2800 },
};

/**
 * The camera on the phone: the phone at [zoom] times its resting size, with the point [fx, fy] of its screen (fractions
 * of its width and height) at [x, y] of the frame, and the phone turned [turn] degrees about its upright axis (its right
 * edge going away as the turn grows), tilted [tilt] about its crosswise one (its top going away) and rolled [roll]
 * clockwise in the frame, all about that point, which the eye looks straight at.
 */
export type Camera = { zoom: number; fx: number; fy: number; x: number; y: number; turn: number; tilt: number; roll: number };

/** Where the camera is at frame [at]. */
type Key = Camera & { at: number };

/**
 * Keys the camera runs through without a cut, from the first's frame until the next shot's, coming into its first key
 * at [enter] times its pace over the stretch after it and leaving its last at [leave] times its pace over the one
 * before: 0 from or to rest, up to 3 for a cubic ease out of or into the key. Over the frames from [tight]'s first to
 * its last, the camera keeps close to its keys instead of rounding them off (see [SMOOTH]).
 */
type Shot = { keys: Key[]; enter: number; leave: number; tight?: [number, number] };

const CHANNELS = ["zoom", "fx", "fy", "x", "y", "turn", "tilt", "roll"] as const;
type Channel = (typeof CHANNELS)[number];

/**
 * A channel of a shot: a cubic through its keys with the slopes Fritsch and Butland give it, which never carries it
 * past a key it is moving between, and brings it to rest only on a key it turns back at.
 */
type Curve = { t: number[]; y: number[]; m: number[] };

function curve(t: number[], y: number[], enter: number, leave: number): Curve {
  const n = t.length;
  const h = (i: number) => t[i + 1]! - t[i]!;
  const d = (i: number) => (y[i + 1]! - y[i]!) / h(i);
  const m = y.map(() => 0);
  for (let i = 1; i < n - 1; i++) {
    const a = d(i - 1);
    const b = d(i);
    if (a * b > 0) m[i] = (3 * (h(i - 1) + h(i))) / ((2 * h(i) + h(i - 1)) / a + (h(i) + 2 * h(i - 1)) / b);
  }
  if (n > 1) {
    m[0] = Math.min(3, enter) * d(0);
    m[n - 1] = Math.min(3, leave) * d(n - 2);
  }
  return { t, y, m };
}

function valueAt(c: Curve, f: number): number {
  const { t, y, m } = c;
  const n = t.length;
  if (n === 1 || f <= t[0]!) return y[0]!;
  if (f >= t[n - 1]!) return y[n - 1]!;
  let i = 0;
  while (f >= t[i + 1]!) i++;
  const h = t[i + 1]! - t[i]!;
  const s = (f - t[i]!) / h;
  const s2 = s * s;
  const s3 = s2 * s;
  return (2 * s3 - 3 * s2 + 1) * y[i]! + (s3 - 2 * s2 + s) * h * m[i]! + (3 * s2 - 2 * s3) * y[i + 1]! + (s3 - s2) * h * m[i + 1]!;
}

/**
 * How wide, in frames, the Gaussian is that each shot's path is low-passed with: wide enough to round every key into
 * one long arc, and, weighing nothing below zero, unable to ring or carry the camera past where its keys go. Over a
 * shot's [tight] frames it narrows to [TIGHT], over [EASE] frames either side.
 */
const SMOOTH = 18;
const TIGHT = 3;
const EASE = 30;

/** The Gaussian's width at frame [f] of a shot kept close to its keys over [tight]. */
function widthAt(f: number, tight?: [number, number]): number {
  if (!tight) return SMOOTH;
  const away = Math.max(tight[0] - f, f - tight[1], 0);
  return TIGHT + (SMOOTH - TIGHT) * (0.5 - 0.5 * Math.cos(Math.PI * Math.min(1, away / EASE)));
}

/**
 * [y] from frame [from] up to [until], one value a frame, low-passed: by one Gaussian [SMOOTH] frames wide on a clock
 * that runs [SMOOTH] / [widthAt] times as fast as the video's, so it narrows where the width does and, the clock
 * running one way, still adds no turn. Mirrored oddly about both ends, so it keeps each end and its pace into it.
 */
function lowPass(y: (f: number) => number, from: number, until: number, tight?: [number, number]): number[] {
  const step = 0.25;
  const fine: number[] = [];
  const clock: number[] = [];
  let tau = 0;
  for (let f = from; f <= until - 1; f += step) {
    fine.push(f);
    clock.push(tau);
    tau += (step * SMOOTH) / widthAt(f + step / 2, tight);
  }
  const end = clock[clock.length - 1]!;
  const n = Math.max(2, Math.round(end) + 1);
  const dt = end / (n - 1);
  const samples: number[] = [];
  for (let j = 0, k = 0; j < n; j++) {
    const at = j * dt;
    while (k < clock.length - 2 && clock[k + 1]! < at) k++;
    const p = Math.min(1, Math.max(0, (at - clock[k]!) / (clock[k + 1]! - clock[k]!)));
    samples.push(y(fine[k]! + p * step));
  }
  const sigma = SMOOTH / dt;
  const reach = Math.min(n - 1, Math.ceil(3 * sigma));
  const weights: number[] = [];
  for (let i = -reach; i <= reach; i++) weights.push(Math.exp(-(i * i) / (2 * sigma * sigma)));
  const padded: number[] = [];
  for (let j = -reach; j < n + reach; j++) {
    padded.push(j < 0 ? 2 * samples[0]! - samples[-j]! : j > n - 1 ? 2 * samples[n - 1]! - samples[2 * (n - 1) - j]! : samples[j]!);
  }
  const low: number[] = [];
  for (let j = 0; j < n; j++) {
    let sum = 0;
    let total = 0;
    for (let i = 0; i < weights.length; i++) {
      sum += weights[i]! * padded[j + i]!;
      total += weights[i]!;
    }
    low.push(sum / total);
  }
  const out: number[] = [];
  for (let f = from; f < until; f++) {
    const at = clock[(f - from) / step]! / dt;
    const j = Math.min(n - 2, Math.floor(at));
    out.push(low[j]! + (at - j) * (low[j + 1]! - low[j]!));
  }
  return out;
}

/**
 * A framing's camera through the hero: each shot from the frame it is cut to until the next's, its keys' frames, and
 * each channel's value a frame along it.
 */
export type Path = { from: number; until: number; keys: number[]; values: Record<Channel, number[]> }[];

function pathOf(shots: Shot[]): Path {
  return shots.map(({ keys, enter, leave, tight }, s) => {
    keys.forEach((k, i) => {
      if (i > 0 && k.at <= keys[i - 1]!.at) throw new Error(`The camera's key at ${k.at} comes after the one at ${keys[i - 1]!.at}`);
    });
    const t = keys.map((k) => k.at);
    const from = t[0]!;
    const until = shots[s + 1]?.keys[0]!.at ?? t[t.length - 1]! + 1;
    const values = {} as Record<Channel, number[]>;
    for (const ch of CHANNELS) {
      // Zoom by equal ratios, so a push in reads at one pace however close the camera is.
      const c = curve(t, keys.map((k) => (ch === "zoom" ? Math.log(k.zoom) : k[ch])), enter, leave);
      values[ch] = lowPass((f) => valueAt(c, f), from, until, tight);
    }
    return { from, until, keys: t, values };
  });
}

/** The camera at frame [f] of [path]. */
export function cameraAt(path: Path, f: number): Camera {
  return cameraOn(path, f, f);
}

/** Frames the pace a shot runs past its ends at takes to die away (see [cameraOn]). */
const COAST = 12;

/**
 * The camera at frame [f] on the shot of [path] that holds frame [on], run on past the shot's ends rather than held:
 * after it, going on at the pace it leaves at, and before it, coming at the pace it enters at, the pace dying away
 * over [COAST] frames either way; so a shot a light carries out, or in, never stops dead on the cut.
 */
export function cameraOn(path: Path, on: number, f: number): Camera {
  let shot = path[0]!;
  for (const s of path) if (s.from <= on) shot = s;
  const last = shot.until - shot.from - 1;
  const i = f - shot.from;
  const cam = {} as Camera;
  for (const ch of CHANNELS) {
    const ys = shot.values[ch];
    const coast = (over: number) => COAST * (1 - Math.exp(-over / COAST));
    if (i > last) cam[ch] = ys[last]! + (ys[last]! - ys[last - 1]!) * coast(i - last);
    else if (i < 0) cam[ch] = ys[0]! - (ys[1]! - ys[0]!) * coast(-i);
    else {
      const j = Math.min(last - 1, Math.floor(i));
      cam[ch] = ys[j]! + (i - j) * (ys[j + 1]! - ys[j]!);
    }
  }
  cam.zoom = Math.exp(cam.zoom);
  return cam;
}

/** The phone's screen in the frame's plane before it turns: its top left and its size, in pixels. */
export function screenOf(framing: Framing, take: TakeId, cam: Camera) {
  const { width, height } = FRAME[framing];
  const w = LENS[framing].rest * cam.zoom;
  const h = screenHeight(take, w);
  return { left: cam.x * width - cam.fx * w, top: cam.y * height - cam.fy * h, width: w, height: h };
}

type Point = [number, number];

/**
 * Where the point [p] of the phone's plane lands in the frame once the phone is rolled, turned and tilted about the
 * camera's point and seen from in front of it, as CSS's rotateX(tilt) rotateY(turn) rotateZ(roll) under a perspective
 * set there has it.
 */
export function project(framing: Framing, cam: Camera, p: Point): Point {
  const { width, height } = FRAME[framing];
  const ox = cam.x * width;
  const oy = cam.y * height;
  const rad = Math.PI / 180;
  const [r, t, a] = [cam.roll * rad, cam.turn * rad, cam.tilt * rad];
  let x = p[0] - ox;
  let y = p[1] - oy;
  let z = 0;
  [x, y] = [x * Math.cos(r) - y * Math.sin(r), x * Math.sin(r) + y * Math.cos(r)];
  [x, z] = [x * Math.cos(t) + z * Math.sin(t), -x * Math.sin(t) + z * Math.cos(t)];
  [y, z] = [y * Math.cos(a) - z * Math.sin(a), y * Math.sin(a) + z * Math.cos(a)];
  const s = LENS[framing].perspective / (LENS[framing].perspective - z);
  return [ox + x * s, oy + y * s];
}

/** The device's outline in the frame, glass and all: its four corners, clockwise from the top left. */
function outlineOf(framing: Framing, take: TakeId, cam: Camera): Point[] {
  const s = screenOf(framing, take, cam);
  const m = deviceMargin(take, s.width);
  const [l, t, r, b] = [s.left - m, s.top - m, s.left + s.width + m, s.top + s.height + m];
  return ([[l, t], [r, t], [r, b], [l, b]] as Point[]).map((p) => project(framing, cam, p));
}

/** Whether two convex polygons overlap: no edge of either separates them. */
function overlaps(a: Point[], b: Point[]): boolean {
  for (const poly of [a, b]) {
    for (let i = 0; i < poly.length; i++) {
      const [x1, y1] = poly[i]!;
      const [x2, y2] = poly[(i + 1) % poly.length]!;
      const [nx, ny] = [y2 - y1, x1 - x2];
      const pa = a.map(([x, y]) => x * nx + y * ny);
      const pb = b.map(([x, y]) => x * nx + y * ny);
      if (Math.max(...pa) < Math.min(...pb) || Math.max(...pb) < Math.min(...pa)) return false;
    }
  }
  return true;
}

type Box = [number, number, number, number];
type Ink = { rising: Box; standing: Box; leaving: Box };

/**
 * Each headline's ink as the cut sets it: left, top, right and bottom, in pixels of the frame, measured off a render of
 * the headlines alone (Launch's probe prop) as the union over the frames its glyphs rise in ([RISING] from its first,
 * when they show under their lines), over the frames it stands, and over the frames it wipes out ([LEAVING] before its
 * last, when they show over their lines), less its last [GONE], when it is all but gone.
 */
const RISING = 44;
const LEAVING = 22;
const GONE = 4;
const INK: Record<Framing, Record<string, Ink>> = {
  wide: {
    "Organize projects.": { rising: [154, 404, 755, 711], standing: [154, 406, 753, 707], leaving: [156, 382, 751, 707] },
    "Say what you want.": { rising: [150, 406, 779, 711], standing: [150, 406, 781, 707], leaving: [152, 382, 779, 707] },
    "Watch it code.": { rising: [152, 404, 719, 711], standing: [154, 406, 717, 677], leaving: [154, 382, 717, 677] },
    "Queue it. Steer it.": { rising: [152, 404, 741, 711], standing: [154, 406, 739, 677], leaving: [156, 382, 737, 677] },
    "Follow it live.": { rising: [158, 404, 699, 711], standing: [160, 406, 695, 677], leaving: [160, 382, 695, 677] },
    "Ship it.": { rising: [152, 482, 589, 629], standing: [154, 484, 589, 625], leaving: [156, 460, 587, 625] },
  },
  tall: {
    "Organize projects.": { rising: [288, 96, 789, 349], standing: [292, 96, 789, 343], leaving: [294, 76, 787, 343] },
    "Say what you want.": { rising: [274, 98, 795, 349], standing: [276, 98, 797, 343], leaving: [278, 76, 795, 343] },
    "Watch it code.": { rising: [306, 96, 773, 349], standing: [308, 96, 773, 319], leaving: [310, 76, 773, 319] },
    "Queue it. Steer it.": { rising: [292, 96, 781, 349], standing: [296, 96, 779, 319], leaving: [298, 76, 777, 319] },
    "Follow it live.": { rising: [316, 96, 765, 349], standing: [320, 96, 765, 319], leaving: [322, 76, 763, 319] },
    "Ship it.": { rising: [356, 160, 719, 281], standing: [358, 160, 717, 277], leaving: [360, 142, 715, 277] },
  },
};

/** The headline's ink at frame [f] of a moment from [at] to [until]: the box for how far along it is, or none once it is gone. */
function inkAt(ink: Ink, f: number, at: number, until: number): Box | null {
  if (f >= until - GONE) return null;
  if (f < at + RISING) return ink.rising;
  if (f >= until - LEAVING) return ink.leaving;
  return ink.standing;
}

/** The room the phone keeps from a headline's ink, in pixels. */
const CLEAR: Record<Framing, number> = { wide: 56, tall: 48 };

/** Points over the screen, as fractions of it, whose motion in the frame is the camera's. */
const PROBES: Point[] = [];
for (let i = 0; i <= 4; i++) for (let j = 0; j <= 8; j++) PROBES.push([i / 4, j / 8]);

/**
 * The fastest the camera moves what it shows, the hardest it speeds up or slows down, and the most that changes from one
 * frame to the next, in pixels of the frame a frame, a frame per frame and a frame per frame per frame: brisk, never a
 * whip, never a lurch, and never a jolt.
 */
const PACE: Record<Framing, { speed: number; accel: number; jerk: number }> = {
  wide: { speed: 40, accel: 5, jerk: 1 },
  tall: { speed: 40, accel: 5, jerk: 1 },
};

export type Motion = { f: number; speed: number; accel: number; jerk: number };

/** How fast and how hard [path] moves the screen's visible points from frame [from] to [until], frame by frame. */
export function motionOf(framing: Framing, take: TakeId, path: Path, from: number, until: number): Motion[] {
  const { width, height } = FRAME[framing];
  const inside = ([x, y]: Point) => x >= 0 && x <= width && y >= 0 && y <= height;
  const at = (f: number) => {
    const cam = cameraAt(path, f);
    const s = screenOf(framing, take, cam);
    return PROBES.map(([u, v]) => project(framing, cam, [s.left + u * s.width, s.top + v * s.height]));
  };
  const change = (now: Point[], before: Point[]) => now.map((p, i) => [p[0] - before[i]![0], p[1] - before[i]![1]] as Point);
  const out: Motion[] = [];
  let before = at(from);
  let velocity: Point[] | null = null;
  let acceleration: Point[] | null = null;
  for (let f = from + 1; f < until; f++) {
    const now = at(f);
    const cut = path.some((s) => s.from === f);
    const v = change(now, before);
    const a = velocity && change(v, velocity);
    const j = a && acceleration && change(a, acceleration);
    let speed = 0;
    let accel = 0;
    let jerk = 0;
    if (!cut) {
      now.forEach((p, i) => {
        if (!inside(p) || !inside(before[i]!)) return;
        speed = Math.max(speed, Math.hypot(...v[i]!));
        if (a) accel = Math.max(accel, Math.hypot(...a[i]!));
        if (j) jerk = Math.max(jerk, Math.hypot(...j[i]!));
      });
    }
    out.push({ f, speed, accel, jerk });
    before = now;
    velocity = cut ? null : v;
    acceleration = cut ? null : a;
  }
  return out;
}

/** The frames in [frames] as runs, "12–15, 40". */
function runsOf(frames: number[]): string {
  const runs: [number, number][] = [];
  for (const f of frames) {
    const last = runs[runs.length - 1];
    if (last && f === last[1] + 1) last[1] = f;
    else runs.push([f, f]);
  }
  return runs.map(([a, b]) => (a === b ? `${a}` : `${a}–${b}`)).join(", ");
}

/**
 * What is wrong with [path]: the phone within [CLEAR] of a headline's ink while it shows, the camera's point out of the
 * frame once the phone is in, or the camera outpacing [PACE] once the phone has come in.
 */
function problemsOf(reel: Reel, framing: Framing, path: Path): string[] {
  const problems: string[] = [];
  const landed = path[0]!.keys[1] ?? AT.organize;
  const near: number[] = [];
  const away: number[] = [];
  for (const moment of MOMENTS) {
    const text = moment[framing].join(" ");
    const measured = INK[framing][text];
    if (!measured) throw new Error(`No ink measured for the ${framing} headline "${text}"`);
    const c = CLEAR[framing];
    for (let f = moment.at; f < moment.until; f++) {
      const cam = cameraAt(path, f);
      const ink = inkAt(measured, f, moment.at, moment.until);
      const box: Point[] | null = ink && [
        [ink[0] - c, ink[1] - c],
        [ink[2] + c, ink[1] - c],
        [ink[2] + c, ink[3] + c],
        [ink[0] - c, ink[3] + c],
      ];
      if (box && overlaps(outlineOf(framing, reel.take, cam), box)) near.push(f);
      if (f >= landed && (cam.x < 0.05 || cam.x > 0.95 || cam.y < 0.05 || cam.y > 0.95)) away.push(f);
    }
  }
  if (near.length) problems.push(`the phone comes within ${CLEAR[framing]}px of the headline at ${runsOf(near)}`);
  if (away.length) problems.push(`the camera looks out of the frame at ${runsOf(away)}`);
  const motion = motionOf(framing, reel.take, path, landed, AT.lineup);
  const fast = motion.filter((m) => m.speed > PACE[framing].speed).map((m) => m.f);
  const hard = motion.filter((m) => m.accel > PACE[framing].accel).map((m) => m.f);
  const jolts = motion.filter((m) => m.jerk > PACE[framing].jerk).map((m) => m.f);
  if (fast.length) problems.push(`the camera moves over ${PACE[framing].speed}px a frame at ${runsOf(fast)}`);
  if (hard.length) problems.push(`the camera's pace changes by over ${PACE[framing].accel}px a frame at ${runsOf(hard)}`);
  if (jolts.length) problems.push(`the camera jolts by over ${PACE[framing].jerk}px a frame per frame per frame at ${runsOf(jolts)}`);
  return problems.map((p) => `${reel.take} ${framing}: ${p}`);
}

const key = (at: number, zoom: number, fx: number, fy: number, x: number, y: number, turn: number, tilt: number, roll: number): Key => ({
  at,
  zoom,
  fx,
  fy,
  x,
  y,
  turn,
  tilt,
  roll,
});

/**
 * The camera through [reel], in five shots cut on the beat, each a few long moves that turn seldom and never on a tap
 * or a beat. The phone swoops in turned and tilted and pushes slowly in on the held Project and its menu, drifting
 * after it across the grid. Cut close on the composer and push in along the listening onto the words; then up with the
 * message as it flies into the chat, the one move that answers the screen; then back in one long pull that takes in
 * the whole reply as it streams and the whole diff as it lands, keeps drawing back through the fold and settles on the
 * follow-up field as it is tapped. Cut low on the composer and track along the follow-up to the queue and the steer,
 * then rise with the steer to its answer and ease in on it. Cut wide as the shade comes down and push in on the run's
 * notification. Cut to the answer and drift down the details to the pull request.
 */
function keysOf(reel: Reel): Record<Framing, Shot[]> {
  const v = (name: string, by = 0) => whenShown(reel, mark(reel.take, name)) + by;
  const said = (name: string, end: "from" | "to", by = 0) => whenShown(reel, stream(reel.take, name)[end]) + by;
  const moving = (keys: Key[], tight?: [number, number]): Shot => ({ keys, enter: 1, leave: 1, tight });
  const settling = (keys: Key[], tight?: [number, number]): Shot => ({ keys, enter: 1, leave: 0, tight });
  const entrance = (keys: Key[]): Shot => ({ keys, enter: 3, leave: 1 });
  const flight: [number, number] = [v("the chat", -7), v("the chat", 27)];
  return {
    wide: [
      entrance([
        key(AT.organize, 1.5, 0.72, 0.64, 0.74, 1.62, -30, 26, -5),
        key(AT.organize + 36, 1.75, 0.68, 0.63, 0.74, 0.55, -15, 8, -1.5),
        key(v("the lift"), 2.05, 0.6, 0.63, 0.72, 0.55, -10, 5, 0),
        key(AT.dictate, 2.15, 0.4, 0.63, 0.64, 0.55, -6, 3, 1),
      ]),
      settling(
        [
          key(AT.dictate, 2.05, 0.74, 0.44, 0.8, 0.52, -22, -5, -0.6),
          key(v("the words"), 2.6, 0.6, 0.385, 0.775, 0.52, -12, -1, -0.1),
          key(v("send", -3), 2.6, 0.6, 0.375, 0.775, 0.52, -11, 0, 0),
          key(v("the chat", -7), 2.6, 0.602, 0.37, 0.775, 0.52, -11, 0, 0),
          key(v("the chat", -2), 2.53, 0.611, 0.346, 0.776, 0.509, -11, 0.3, 0.02),
          key(v("the chat", 3), 2.4, 0.629, 0.293, 0.778, 0.487, -11, 0.9, 0.05),
          key(v("the chat", 8), 2.3, 0.654, 0.223, 0.78, 0.46, -11, 1.7, 0.1),
          key(v("the chat", 13), 2.29, 0.674, 0.165, 0.785, 0.433, -11, 2.5, 0.15),
          key(v("the chat", 19), 2.4, 0.686, 0.131, 0.787, 0.408, -11, 3.2, 0.2),
          key(v("the chat", 27), 2.58, 0.69, 0.121, 0.788, 0.4, -11, 3.4, 0.25),
          key(v("the diff", 42), 2.25, 0.53, 0.36, 0.705, 0.49, -12.5, 3.6, 0.36),
          key(v("jump"), 1.6, 0.48, 0.42, 0.7, 0.49, -15.5, 4, 0.55),
          key(AT.steer, 1.25, 0.48, 0.63, 0.7, 0.5, -17, 4.5, 0.8),
        ],
        flight,
      ),
      moving([
        key(AT.steer, 2.95, 0.22, 0.89, 0.6, 0.55, -18, -6, -1),
        key(v("queue"), 2.75, 0.62, 0.87, 0.78, 0.555, -12, -3, -0.3),
        key(v("steer", 16), 2.45, 0.7, 0.8, 0.8, 0.55, -11, -1, 0),
        key(said("hero.adapt", "from", 13), 1.85, 0.56, 0.36, 0.72, 0.48, -10, 2, 0.5),
        key(AT.live, 2.25, 0.48, 0.32, 0.7, 0.48, -8, 3, 0.7),
      ]),
      moving([
        key(AT.live, 1.35, 0.5, 0.3, 0.7, 0.44, -22, 9, 1.5),
        key(SHADE.pull.at + SHADE.pull.frames + 10, 1.85, 0.51, 0.17, 0.7, 0.415, -14, 6, 0.6),
        key(AT.ship, 2.5, 0.56, 0.15, 0.72, 0.41, -6, 2, -0.5),
      ]),
      moving([
        key(AT.ship, 1.7, 0.52, 0.37, 0.7, 0.5, -16, 4, 0.8),
        key(v("details", 4), 1.72, 0.58, 0.37, 0.71, 0.5, -14, 3.5, 0.5),
        key(AT.lineup, 2.3, 0.58, 0.8, 0.72, 0.55, -8, 1, -0.4),
      ]),
    ],
    tall: [
      entrance([
        key(AT.organize, 1.05, 0.64, 0.68, 0.53, 1.55, -24, 28, -4),
        key(AT.organize + 36, 1.12, 0.62, 0.67, 0.53, 0.8, -12, 12, -1),
        key(v("the lift"), 1.28, 0.6, 0.62, 0.52, 0.785, -9, 11, 0),
        key(AT.dictate, 1.32, 0.42, 0.58, 0.47, 0.76, -6, 8, 1),
      ]),
      settling(
        [
          key(AT.dictate, 1.6, 0.72, 0.44, 0.54, 0.78, -2, 12, -0.6),
          key(v("the words"), 2.05, 0.6, 0.38, 0.54, 0.765, -9, 10, -0.1),
          key(v("send", -3), 2.05, 0.6, 0.375, 0.54, 0.76, -9, 9, 0),
          key(v("the chat", -7), 2.05, 0.602, 0.37, 0.54, 0.754, -9, 9, 0),
          key(v("the chat", -2), 2.04, 0.611, 0.346, 0.541, 0.731, -9, 8.9, 0.02),
          key(v("the chat", 3), 2.03, 0.629, 0.293, 0.543, 0.677, -9, 8.7, 0.05),
          key(v("the chat", 8), 2.03, 0.654, 0.223, 0.545, 0.605, -9, 8.5, 0.1),
          key(v("the chat", 13), 2.08, 0.674, 0.165, 0.547, 0.547, -9, 8.3, 0.15),
          key(v("the chat", 19), 2.17, 0.686, 0.131, 0.549, 0.512, -9, 8.1, 0.2),
          key(v("the chat", 27), 2.29, 0.69, 0.121, 0.55, 0.501, -9, 8, 0.25),
          key(v("the chat", 37), 2.27, 0.68, 0.125, 0.548, 0.505, -9, 8, 0.26),
          key(said("hero.intro", "from", 12), 1.8, 0.5, 0.215, 0.5, 0.585, -9.1, 7.9, 0.28),
          key(v("the diff", 35), 1.66, 0.5, 0.33, 0.5, 0.635, -10, 7.5, 0.36),
          key(v("jump"), 1.34, 0.5, 0.43, 0.5, 0.665, -12, 6.8, 0.6),
          key(AT.steer, 0.97, 0.5, 0.72, 0.5, 0.735, -14, 6, 0.8),
        ],
        flight,
      ),
      moving([
        key(AT.steer, 1.33, 0.4, 0.88, 0.5, 0.935, 12, 18, 1),
        key(v("queue"), 1.3, 0.64, 0.87, 0.53, 0.915, 1, 16, 0.4),
        key(v("steer", 16), 1.15, 0.68, 0.8, 0.53, 0.84, -6, 12, 0.1),
        key(said("hero.adapt", "from", 13), 1.25, 0.56, 0.34, 0.5, 0.58, -9, 8, -0.2),
        key(AT.live, 1.55, 0.48, 0.32, 0.5, 0.575, -10, 7, -0.5),
      ]),
      moving([
        key(AT.live, 1.0, 0.5, 0.33, 0.5, 0.53, -16, 10, 1.5),
        key(SHADE.pull.at + SHADE.pull.frames + 10, 1.55, 0.51, 0.17, 0.5, 0.45, -10, 6, 0.5),
        key(AT.ship, 2.1, 0.55, 0.15, 0.5, 0.44, -5, 4, -0.5),
      ]),
      moving([
        key(AT.ship, 1.3, 0.54, 0.36, 0.5, 0.6, 8, 8, -1),
        key(v("details", 4), 1.2, 0.58, 0.4, 0.51, 0.6, 0, 8.5, 0),
        key(AT.lineup, 1.2, 0.58, 0.8, 0.52, 0.84, -6, 10, 0.5),
      ]),
    ],
  };
}

function pathsOf(reel: Reel): Record<Framing, Path> {
  const keys = keysOf(reel);
  return { wide: pathOf(keys.wide), tall: pathOf(keys.tall) };
}

export const HERO_PATHS: Record<Theme, Record<Framing, Path>> = { dark: pathsOf(HEROES.dark), light: pathsOf(HEROES.light) };
const problems = (["dark", "light"] as const).flatMap((theme) =>
  (["wide", "tall"] as const).flatMap((framing) => problemsOf(HEROES[theme], framing, HERO_PATHS[theme][framing])),
);
// The headlines' ink is measured off a render of them alone (Launch's probe prop), which the camera's clearance is
// checked against, so that render must go ahead of the check: REMOTION_PROBE=1 lets it.
if (problems.length && !process.env.REMOTION_PROBE) throw new Error(`The camera:\n${problems.join("\n")}`);

const still = (take: number) => whenShown(HEROES[THEME], take);
const hero = HEROES[THEME].take;

/** The frames of the 16:9 cut kept as stills: the Android card, each moment at its peak, and the lineup. */
export const STILLS = {
  android: AT.android + 50,
  organize: still(mark(hero, "the lift") + 30),
  dictate: still(mark(hero, "the words") + 12),
  code: still(mark(hero, "the diff") + 50),
  steer: still(stream(hero, "hero.adapt").to + 20),
  live: SHADE.pull.at + 80,
  ship: still(mark(hero, "pull request") + 40),
  lineup: AT.end - 30,
};
