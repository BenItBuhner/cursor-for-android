// Renders the launch video from the capture's takes: the cues and the score, the 16:9 cut, the 9:16 cut and the stills,
// into out/, and copies them to --dest if given.
//
//   node scripts/render.mjs [--only=wide,tall,stills] [--dest=DIR] [--concurrency=3]
//
// Needs the takes (promo/capture/run.sh, then `npm run footage`), and python3 with scripts/requirements.txt for the score.
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k, v ?? "true"];
  }),
);
const only = new Set((args.only ?? "wide,tall,stills").split(","));
const concurrency = args.concurrency ?? "3";
const out = join(video, "out");

const NAME = "cursor-for-android-launch";
const CUTS = [
  { key: "wide", composition: "Launch", framing: "wide", file: `${NAME}.mp4` },
  { key: "tall", composition: "LaunchVertical", framing: "tall", file: `${NAME}-vertical.mp4` },
];
/** The stills: the title card, the stream on the phone, the tablet's workspace and desktop mode. */
const STILLS = [
  { frame: 64, file: `${NAME}-title.png` },
  { frame: 760, file: `${NAME}-phone.png` },
  { frame: 1664, file: `${NAME}-tablet.png` },
  { frame: 2650, file: `${NAME}-desktop.png` },
];

function run(command, argv) {
  console.log(`\n$ ${command} ${argv.join(" ")}`);
  const r = spawnSync(command, argv, { cwd: video, stdio: "inherit" });
  if (r.status !== 0) throw new Error(`${command} failed with ${r.status}`);
}

if (!existsSync(join(video, "public", "footage", "takes.json"))) run("node", ["scripts/footage.mjs"]);
run("node", ["--experimental-strip-types", "--no-warnings", "scripts/cues.ts"]);
run("python3", ["scripts/music.py", "public/audio/score.wav"]);

mkdirSync(out, { recursive: true });
const made = [];
for (const cut of CUTS) {
  if (!only.has(cut.key)) continue;
  const file = join(out, cut.file);
  run("npx", ["remotion", "render", cut.composition, file, `--props=${JSON.stringify({ framing: cut.framing, music: true })}`, `--concurrency=${concurrency}`]);
  made.push(file);
}
if (only.has("stills")) {
  for (const still of STILLS) {
    const file = join(out, still.file);
    run("npx", ["remotion", "still", "Launch", file, `--frame=${still.frame}`, "--image-format=png", `--props=${JSON.stringify({ framing: "wide", music: false })}`]);
    made.push(file);
  }
}

if (args.dest) {
  mkdirSync(args.dest, { recursive: true });
  for (const file of made) copyFileSync(file, join(args.dest, file.split("/").pop()));
  console.log(`\nCopied ${made.length} files to ${args.dest}`);
}
