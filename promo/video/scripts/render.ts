// Renders the launch video from the capture's takes: the cues and the score, the 16:9 cut, the 9:16 cut, the stills and
// the side-by-side of the app's two themes, into out/, and copies them to --dest if given.
//
//   npx tsx scripts/render.ts [--only=wide,tall,stills,compare] [--dest=DIR] [--concurrency=3]
//
// Needs the takes (promo/capture/run.sh for each device, dark and light, then `npm run footage`), python3 with
// scripts/requirements.txt for the score, and ffmpeg to lay the score under the picture.
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const args: Record<string, string> = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k!, v ?? "true"];
  }),
);
const only = new Set((args.only ?? "wide,tall,stills,compare").split(","));
const concurrency = args.concurrency ?? "3";
const out = join(video, "out");

const NAME = "cursor-for-android";
const CUTS = [
  { key: "wide", composition: "Launch", framing: "wide", file: `${NAME}-launch.mp4` },
  { key: "tall", composition: "LaunchVertical", framing: "tall", file: `${NAME}-launch-vertical.mp4` },
];

function run(command: string, argv: string[]) {
  console.log(`\n$ ${command} ${argv.join(" ")}`);
  const r = spawnSync(command, argv, { cwd: video, stdio: "inherit" });
  if (r.status !== 0) throw new Error(`${command} failed with ${r.status}`);
}

if (!existsSync(join(video, "public", "footage", "takes.json"))) run("node", ["scripts/footage.mjs"]);
// The edit reads the takes, so it is loaded only once they are there.
const { STILLS } = await import("../src/camera");
const { DURATION, FPS, THEME } = await import("../src/edit");
const { COMPARE } = await import("../src/components/Compare");

mkdirSync(out, { recursive: true });
const made: string[] = [];
if (only.has("wide") || only.has("tall")) {
  run("npx", ["tsx", "scripts/cues.ts"]);
  run("python3", ["scripts/music.py", "public/audio/score.wav"]);
}
for (const cut of CUTS) {
  if (!only.has(cut.key)) continue;
  const file = join(out, cut.file);
  const picture = join(out, `.${cut.file}`);
  const props = JSON.stringify({ framing: cut.framing, music: false, theme: THEME });
  run("npx", ["remotion", "render", cut.composition, picture, `--props=${props}`, `--concurrency=${concurrency}`]);
  // Remotion's AAC leaves the encoder's 2048 priming samples in the stream, so the score would land 2.5 frames late;
  // ffmpeg's encoder writes them into the edit list, which players skip.
  run("ffmpeg", [
    "-v", "error", "-y", "-i", picture, "-i", "public/audio/score.wav",
    "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "320k",
    "-t", String(DURATION / FPS), "-movflags", "+faststart", file,
  ]);
  rmSync(picture);
  made.push(file);
}
if (only.has("stills")) {
  const props = JSON.stringify({ framing: "wide", music: false, theme: THEME });
  for (const [name, frame] of Object.entries(STILLS)) {
    const file = join(out, `${NAME}-launch-${name}.png`);
    run("npx", ["remotion", "still", "Launch", file, `--frame=${frame}`, "--image-format=png", `--props=${props}`]);
    made.push(file);
  }
}
if (only.has("compare")) {
  const clip = join(out, `${NAME}-light-vs-dark.mp4`);
  run("npx", ["remotion", "render", "Compare", clip, `--concurrency=${concurrency}`]);
  made.push(clip);
  const still = join(out, `${NAME}-light-vs-dark.png`);
  run("npx", ["remotion", "still", "Compare", still, `--frame=${COMPARE.still}`, "--image-format=png"]);
  made.push(still);
}

if (args.dest) {
  mkdirSync(args.dest, { recursive: true });
  for (const file of made) copyFileSync(file, join(args.dest, basename(file)));
  console.log(`\nCopied ${made.length} files to ${args.dest}`);
}
