// Brings the capture's takes (promo/capture/out) into the video: each take's MP4 copied to public/footage, and
// public/footage/takes.json with what the capture recorded beside every frame (the app's content size, the system
// bars' heights, the clock, a finger, the live notification, the take's marks), the frames each streamed text and edit
// runs over, which the edit may cut between but never plays faster than the capture, and how much of the screen
// changes from each frame to the next, which the camera keeps still over.
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const out = join(video, "..", "capture", "out");
const footage = join(video, "public", "footage");

/** Each device's take in the app's dark theme, and in its light one. */
const TAKES = ["phone", "foldable", "tablet", "phone-light", "foldable-light", "tablet-light"];

/** The repository coalesces a burst for up to 80 ms before the screen shows it: text lands a few frames on. */
const LANDING_FRAMES = 8;

/** The screen as the motion is measured on it: a pixel this many levels brighter or darker than a frame before moved. */
const MOTION_WIDTH = 54;
const MOTION_LEVELS = 6;

mkdirSync(footage, { recursive: true });
const takes = {};
for (const id of TAKES) {
  const mp4 = join(out, id, `${id}.mp4`);
  const jsonl = join(out, id, `${id}.jsonl`);
  const test = id.replace(/-(\w)/, (_, c) => c.toUpperCase());
  if (!existsSync(mp4) || !existsSync(jsonl)) throw new Error(`No ${id} take: run promo/capture/run.sh ${test}`);
  // The capture's frames reach ffmpeg as BGRA, which its scaler converts with BT.601's matrix and leaves untagged, and
  // Remotion reads an untagged stream as BT.709: the UI's colours would shift a few levels. The stream says so, as is.
  const tagged = spawnSync(
    "ffmpeg",
    ["-v", "error", "-y", "-i", mp4, "-c", "copy", "-bsf:v", "h264_metadata=matrix_coefficients=6:video_full_range_flag=0", join(footage, `${id}.mp4`)],
    { stdio: "inherit" },
  );
  if (tagged.status !== 0) throw new Error(`ffmpeg could not copy ${mp4}`);
  const frames = readFileSync(jsonl, "utf8").trim().split("\n").map((line) => JSON.parse(line));
  const marks = {};
  frames.forEach((f, i) => (f.marks ?? []).forEach((m) => (marks[m] = i)));
  if (marks.send === undefined) throw new Error(`The ${id} take has no send`);
  const pacing = JSON.parse(readFileSync(join(out, id, "pacing.json"), "utf8"));
  const t0 = frames[0].t;
  const t1 = frames[frames.length - 1].t;
  const byKey = new Map();
  for (const burst of pacing.bursts) {
    if (burst.t < t0 || burst.t > t1) continue;
    const frame = frames.findIndex((f) => f.t >= burst.t);
    const span = byKey.get(burst.key) ?? { key: burst.key, kind: burst.kind, from: frame, to: frame, tokens: 0 };
    span.to = Math.min(frames.length - 1, frame + LANDING_FRAMES);
    span.tokens += burst.tokens;
    byKey.set(burst.key, span);
  }
  const width = even(Math.max(...frames.map((f) => f.content[2])));
  const height = even(Math.max(...frames.map((f) => f.content[3])));
  takes[id] = {
    file: `footage/${id}.mp4`,
    device: frames[0].screen,
    night: !id.endsWith("-light"),
    width,
    height,
    frames: frames.length,
    frameMs: (t1 - t0) / (frames.length - 1),
    t: frames.map((f) => f.t),
    clock: frames.map((f) => f.clock),
    statusBar: frames.map((f) => f.statusBar),
    navBar: frames.map((f) => f.navBar),
    touch: frames.map((f) => (f.touch ? [f.touch.x, f.touch.y, f.touch.down ? 1 : 0] : null)),
    live: liveOf(id, frames),
    motion: motionOf(mp4, width, height, frames.length),
    marks,
    streams: [...byKey.values()],
    peakPerSecond: pacing.peakPerSecond,
  };
  console.log(`${id}: ${frames.length} frames ${width}x${height}, peak ${pacing.peakPerSecond}/s, ${byKey.size} streams`);
  console.log(`  marks ${JSON.stringify(marks)}`);
}
writeFileSync(join(footage, "takes.json"), JSON.stringify(takes));

/**
 * The live notification as the take posted it: what stays (the app, the chat's title, the actions), when its run
 * started on the take's clock, which its header's chronometer counts from, and each step it showed, from frame to frame.
 */
function liveOf(id, frames) {
  const posted = frames.map((f, i) => ({ ...f.live, i })).filter((f, i) => frames[i].live);
  if (posted.length === 0) throw new Error(`The ${id} take posted no live notification`);
  const first = posted[0];
  const startT = frames[first.i].t - first.elapsedMs;
  const steps = [];
  for (const p of posted) {
    for (const key of ["app", "title"]) if (p[key] !== first[key]) throw new Error(`The ${id} take's notification changed its ${key}`);
    if (frames[p.i].t - p.elapsedMs !== startT) throw new Error(`The ${id} take's chronometer skipped at frame ${p.i}`);
    const actions = p.actions ?? [];
    const last = steps[steps.length - 1];
    if (last && last.to === p.i - 1 && last.text === p.text && last.sub === p.sub && last.actions.join() === actions.join()) last.to = p.i;
    else steps.push({ from: p.i, to: p.i, text: p.text, sub: p.sub, actions, indeterminate: p.indeterminate === true });
  }
  return { app: first.app, title: first.title, startT, steps };
}

/** The share of the screen that changes from each frame to the next (0 for the first), on a small grey copy of it. */
function motionOf(mp4, width, height, count) {
  const w = MOTION_WIDTH;
  const h = even(Math.round((w * height) / width));
  const raw = spawnSync("ffmpeg", ["-v", "error", "-i", mp4, "-vf", `scale=${w}:${h}:flags=area,format=gray`, "-f", "rawvideo", "-"], {
    maxBuffer: 1 << 30,
  });
  if (raw.status !== 0) throw new Error(`ffmpeg could not read ${mp4}`);
  const size = w * h;
  if (raw.stdout.length !== size * count) throw new Error(`${mp4} has ${raw.stdout.length / size} frames, not ${count}`);
  const motion = [0];
  for (let i = 1; i < count; i++) {
    let moved = 0;
    for (let p = 0; p < size; p++) if (Math.abs(raw.stdout[i * size + p] - raw.stdout[(i - 1) * size + p]) > MOTION_LEVELS) moved++;
    motion.push(Math.round((moved / size) * 1000) / 1000);
  }
  return motion;
}

function even(v) {
  return v - (v % 2);
}
