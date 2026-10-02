// Writes public/audio/cues.json, what scripts/music.py scores the cut to: the tempo, where the sections and the swells
// fall, the frame the Fold's hinge lands flat on, and the frames the takes' taps and key presses play on in the cut.
// Run with `node --experimental-strip-types scripts/cues.ts`: src/edit.ts is plain TypeScript with no imports.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { BPM, DURATION, FPS, RUNS, SECTIONS, UNFOLD, WHOOSHES, type TakeId } from "../src/edit.ts";

type Take = { touch: ([number, number, number] | null)[]; keys: (string[] | null)[] };

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const takes = JSON.parse(readFileSync(join(video, "public", "footage", "takes.json"), "utf8")) as Record<TakeId, Take>;

/** The timeline frames frame [f] of [take] plays on: once per run holding it. */
function onTimeline(take: TakeId, f: number): number[] {
  return RUNS.filter((r) => r.take === take && f >= r.src && f < r.src + (r.to - r.from)).map((r) => r.from + (f - r.src));
}

const taps: number[] = [];
const keys: { frame: number; key: string; down: boolean }[] = [];
for (const id of Object.keys(takes) as TakeId[]) {
  const take = takes[id];
  take.touch.forEach((touch, f) => {
    const before = take.touch[f - 1];
    if (touch?.[2] === 1 && before?.[2] !== 1) taps.push(...onTimeline(id, f));
  });
  take.keys.forEach((held, f) => {
    const now = held ?? [];
    const before = take.keys[f - 1] ?? [];
    for (const key of now) if (!before.includes(key)) for (const frame of onTimeline(id, f)) keys.push({ frame, key, down: true });
    for (const key of before) if (!now.includes(key)) for (const frame of onTimeline(id, f)) keys.push({ frame, key, down: false });
  });
}
taps.sort((x, y) => x - y);
keys.sort((x, y) => x.frame - y.frame);

const cues = { fps: FPS, bpm: BPM, frames: DURATION, sections: SECTIONS, whooshes: WHOOSHES, hinge: UNFOLD.to, taps, keys };
mkdirSync(join(video, "public", "audio"), { recursive: true });
writeFileSync(join(video, "public", "audio", "cues.json"), `${JSON.stringify(cues, null, 2)}\n`);
console.log(`cues: ${taps.length} taps, ${keys.filter((k) => k.down).length} key presses, ${WHOOSHES.length} swells`);
