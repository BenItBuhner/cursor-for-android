// Renders the launch film (src/film): the UI reels its screens show, its score, each cut as a PNG sequence in
// out/film-<framing>/ (so a shot can be rendered again on its own with --frames and the cut re-encoded around it), the
// cuts encoded with the score under them, and its stills and type keyframes, into out/film/, copied to --dest if given.
//
//   npx tsx scripts/render-film.ts [--only=wide,tall,stills] [--frames=FROM-TO,...] [--missing] [--encode-only] [--dest=DIR] [--concurrency=3]
//
// Needs the takes (npm run footage), python3 with scripts/requirements.txt for the score, and ffmpeg.
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const args: Record<string, string> = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k!, v ?? "true"];
  }),
);
const only = new Set((args.only ?? "wide,tall,stills").split(","));
// One page at a time: the software GL renders as fast for one as for three, and three slow each other over time.
const concurrency = args.concurrency ?? "1";
const out = join(video, "out");
const film = join(out, "film");

const NAME = "cursor-for-android";
const CUTS = [
  { key: "wide", composition: "Film", file: `${NAME}-launch.mp4` },
  { key: "tall", composition: "FilmVertical", file: `${NAME}-launch-vertical.mp4` },
] as const;

function run(command: string, argv: string[]) {
  console.log(`\n$ ${command} ${argv.join(" ")}`);
  const r = spawnSync(command, argv, { cwd: video, stdio: "inherit" });
  if (r.status !== 0) throw new Error(`${command} failed with ${r.status}`);
}

if (!existsSync(join(video, "public", "footage", "takes.json"))) run("node", ["scripts/footage.mjs"]);
const { DURATION, FPS, SHOT, WORDS } = await import("../src/film/edit");
const { REELS, reelManifest } = await import("../src/film/reels");
const stale = (Object.keys(REELS) as (keyof typeof REELS)[]).filter((id) => {
  const manifest = join(video, "public", "reel", id, "manifest.json");
  return !existsSync(manifest) || readFileSync(manifest, "utf8") !== reelManifest(id);
});
if (stale.length) run("npx", ["tsx", "scripts/reels.ts", `--only=${stale.join(",")}`]);

mkdirSync(film, { recursive: true });
const made: string[] = [];
const frame = (dir: string, f: number) => join(dir, `${String(f).padStart(4, "0")}.png`);

/**
 * Renders [from, to] of [composition] into [dir] as NNNN.png, from [bundle], a fresh browser every [CHUNK] frames: a
 * page drawing the film slows to a third of its pace over a few hundred frames, and a new one starts at full pace.
 */
const CHUNK = 150;
function sequence(bundle: string, composition: string, dir: string, from: number, to: number) {
  mkdirSync(dir, { recursive: true });
  for (let a = from; a <= to; a += CHUNK) {
    const b = Math.min(to, a + CHUNK - 1);
    const tmp = join(tmpdir(), `film-${composition}`);
    rmSync(tmp, { recursive: true, force: true });
    run("npx", ["remotion", "render", bundle, composition, tmp, "--sequence", "--image-format=png", `--frames=${a}-${b}`, `--concurrency=${concurrency}`]);
    for (const name of readdirSync(tmp)) {
      const f = Number(/(\d+)\.png$/.exec(name)?.[1]);
      renameSync(join(tmp, name), frame(dir, f));
    }
    rmSync(tmp, { recursive: true, force: true });
  }
}

const cutting = CUTS.some((c) => only.has(c.key));
if (cutting && !args["encode-only"]) {
  run("npx", ["tsx", "scripts/film-cues.ts"]);
  run("python3", ["scripts/music.py", "public/audio/film-score.wav", "film-cues.json"]);
}
const ranges = (args.frames ?? `0-${DURATION - 1}`).split(",").map((r) => r.split("-").map(Number) as [number, number]);
const bundle = join(out, "film-bundle");
if (cutting && !args["encode-only"]) {
  rmSync(bundle, { recursive: true, force: true });
  run("npx", ["remotion", "bundle", "src/index.ts", `--out-dir=${bundle}`]);
}
for (const cut of CUTS) {
  if (!only.has(cut.key)) continue;
  const dir = join(out, `film-${cut.key}`);
  if (!args["encode-only"]) {
    for (const [from, to = from] of ranges) {
      if (!args.missing) {
        sequence(bundle, cut.composition, dir, from, to);
        continue;
      }
      // Only the frames not yet in [dir], a run at a time.
      for (let f = from; f <= to; f++) {
        if (existsSync(frame(dir, f))) continue;
        let g = f;
        while (g < to && !existsSync(frame(dir, g + 1))) g++;
        sequence(bundle, cut.composition, dir, f, g);
        f = g;
      }
    }
  }
  const missing = Array.from({ length: DURATION }, (_, f) => f).filter((f) => !existsSync(frame(dir, f)));
  if (missing.length) throw new Error(`${cut.key}: ${missing.length} frames not rendered, from ${missing[0]}`);
  const file = join(film, cut.file);
  run("ffmpeg", [
    "-v", "error", "-y",
    "-framerate", String(FPS), "-i", join(dir, "%04d.png"),
    "-i", "public/audio/film-score.wav",
    "-map", "0:v:0", "-map", "1:a:0",
    "-c:v", "libx264", "-preset", "slow", "-crf", "14", "-tune", "film", "-pix_fmt", "yuv420p",
    "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
    "-c:a", "aac", "-b:a", "320k",
    "-t", String(DURATION / FPS), "-movflags", "+faststart", file,
  ]);
  made.push(file);
}

/** A still from the middle of each shot, and each line of type at its steadiest: a beat after it comes on. */
if (only.has("stills")) {
  for (const cut of CUTS) {
    const dir = join(out, `film-${cut.key}`);
    const stills = join(film, "stills", cut.key);
    const type = join(film, "type-keyframes", cut.key);
    mkdirSync(stills, { recursive: true });
    mkdirSync(type, { recursive: true });
    for (const [id, s] of Object.entries(SHOT)) {
      if (id === "black") continue;
      const f = Math.round((s.from + s.to) / 2);
      if (!existsSync(frame(dir, f))) continue;
      const file = join(stills, `${String(s.from).padStart(4, "0")}-${id}.png`);
      copyFileSync(frame(dir, f), file);
      made.push(file);
    }
    for (const w of WORDS) {
      const f = Math.min(w.to - 1, w.from + 30);
      if (!existsSync(frame(dir, f))) continue;
      const slug = w.text.wide.toLowerCase().replace(/[^a-z]+/g, "-").replace(/^-|-$/g, "");
      const file = join(type, `${String(f).padStart(4, "0")}-${slug}.png`);
      copyFileSync(frame(dir, f), file);
      made.push(file);
    }
  }
}

if (args.dest) {
  for (const file of made) {
    const target = join(args.dest, file.slice(film.length + 1));
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(file, target);
  }
  console.log(`\nCopied ${made.length} files to ${args.dest}`);
}
console.log(`\n${made.map((f) => basename(f)).join("\n")}`);
