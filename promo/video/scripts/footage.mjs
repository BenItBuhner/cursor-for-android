// Brings the capture's takes (promo/capture/out) into the video: each segment's MP4 copied to public/footage, and
// public/footage/takes.json with what the capture recorded beside every frame — the app's content size, the system
// bars' heights, the clock, a finger, the keys held, the take's marks — and the frames each streamed text lands in,
// which the cut must never speed through or cut inside.
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const out = join(video, "..", "capture", "out");
const footage = join(video, "public", "footage");

const SEGMENTS = [
  { id: "hero-phone", take: "hero", segment: "phone" },
  { id: "hero-large", take: "hero", segment: "large" },
  { id: "desktop", take: "desktop", segment: "desktop" },
];

/** The repository coalesces a burst for up to 80 ms and the capture waits 150 ms for it: text lands a few frames on. */
const LANDING_FRAMES = 12;

mkdirSync(footage, { recursive: true });
const takes = {};
for (const { id, take, segment } of SEGMENTS) {
  const mp4 = join(out, take, `${segment}.mp4`);
  const jsonl = join(out, take, `${segment}.jsonl`);
  if (!existsSync(mp4) || !existsSync(jsonl)) throw new Error(`No ${take}/${segment} capture: run promo/capture/run.sh ${take}`);
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
  const pacing = join(out, take, "pacing.json");
  const bursts = existsSync(pacing) ? JSON.parse(readFileSync(pacing, "utf8")).bursts : [];
  const t0 = frames[0].t;
  const t1 = frames[frames.length - 1].t;
  const byKey = new Map();
  for (const burst of bursts) {
    if (burst.t < t0 || burst.t > t1) continue;
    const frame = frames.findIndex((f) => f.t >= burst.t);
    const span = byKey.get(burst.key) ?? { key: burst.key, kind: burst.kind, from: frame, to: frame, tokens: 0 };
    span.to = Math.min(frames.length - 1, frame + LANDING_FRAMES);
    span.tokens += burst.tokens;
    byKey.set(burst.key, span);
  }
  const [width, height] = probeSize(frames);
  takes[id] = {
    file: `footage/${id}.mp4`,
    width,
    height,
    frames: frames.length,
    t: frames.map((f) => f.t),
    screen: frames.map((f) => f.screen),
    clock: frames.map((f) => f.clock),
    content: frames.map((f) => [Math.min(f.content[2], width), Math.min(f.content[3], height)]),
    statusBar: frames.map((f) => f.statusBar),
    navBar: frames.map((f) => f.navBar),
    touch: frames.map((f) => (f.touch ? [f.touch.x, f.touch.y, f.touch.down ? 1 : 0] : null)),
    keys: frames.map((f) => f.keys ?? null),
    marks,
    streams: [...byKey.values()],
  };
  console.log(`${id}: ${frames.length} frames ${width}x${height}, marks ${JSON.stringify(marks)}, ${byKey.size} streams`);
}
writeFileSync(join(footage, "takes.json"), JSON.stringify(takes));

/** The segment's canvas: the largest content it holds, which the capture sized it to (rounded down to even). */
function probeSize(frames) {
  const w = Math.max(...frames.map((f) => f.content[2]));
  const h = Math.max(...frames.map((f) => f.content[3]));
  return [w - (w % 2), h - (h % 2)];
}
