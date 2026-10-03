// Renders the screens the film puts on the devices' glass (src/film/reels.ts) to public/reel/<id>/NNNN.jpg, one still
// per take frame a reel needs, in the order the reel lists them, with the manifest they were rendered from.
//
//   npx tsx scripts/reels.ts [--only=phone,phone-lock,foldable,tablet] [--concurrency=3]
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { REELS, reelManifest, type ReelId } from "../src/film/reels";

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const args: Record<string, string> = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k!, v ?? "true"];
  }),
);
const ids = (args.only?.split(",") ?? Object.keys(REELS)) as ReelId[];

for (const id of ids) {
  const dir = join(video, "public", "reel", id);
  // Outside public/, so the bundle doesn't copy half-written stills; no dot, which Remotion reads as an extension.
  const raw = join(tmpdir(), `reel-${id}`);
  rmSync(raw, { recursive: true, force: true });
  const argv = [
    "remotion", "render", `Reel-${id}`, raw, "--sequence", "--image-format=jpeg", "--jpeg-quality=94",
    "--image-sequence-pattern=[frame].[ext]", `--concurrency=${args.concurrency ?? "3"}`,
  ];
  console.log(`\n$ npx ${argv.join(" ")}`);
  const r = spawnSync("npx", argv, { cwd: video, stdio: "inherit" });
  if (r.status !== 0) throw new Error(`Reel-${id} failed with ${r.status}`);
  // Remotion pads the frame number to the reel's own length; the film asks for four digits.
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  for (const file of readdirSync(raw)) copyFileSync(join(raw, file), join(dir, `${file.split(".")[0]!.padStart(4, "0")}.jpg`));
  rmSync(raw, { recursive: true, force: true });
  writeFileSync(join(dir, "manifest.json"), reelManifest(id));
  console.log(`${id}: ${REELS[id].frames.length} stills in public/reel/${id}`);
}
