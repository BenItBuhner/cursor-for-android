// Writes public/audio/cues.json, what scripts/music.py scores the cut to: the tempo and length, where each part of the
// beat sheet starts, the beats the lineup's devices land on, the frames the light sweeps the stage on, the lights that
// carry the cuts, the dip into the end card, the frame the prompt is sent on, and the frames of the video a finger
// comes down on.
//
//   npx tsx scripts/cues.ts
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { AT, BPM, beat, DURATION, FPS, HEROES, LINEUP, lineupReel, SHADE, SWEEPS, takeFrame, THEME, whenShown, CARRIES, CARRY, carryFrom, DIP, type Reel } from "../src/edit";
import { mark, takeOf, takes } from "../src/takes";

/** The most take frames one frame of the video moves through that still count as playing, not a cut. */
const PLAYING = 4;

/** The frames of the video between [from] and [until] that a finger comes down on in [reel], as its playback runs over them. */
function taps(reel: Reel, from: number, until: number): number[] {
  const touch = takes[reel.take].touch;
  const out: number[] = [];
  for (let f = from; f < until; f++) {
    const now = takeFrame(reel, f);
    const before = f === from ? now - 1 : takeFrame(reel, f - 1);
    if (now - before < 1 || now - before > PLAYING) continue;
    for (let k = before + 1; k <= now; k++) if (touch[k]?.[2] === 1 && touch[k - 1]?.[2] !== 1) out.push(f);
  }
  return out;
}

/** The notification shade's finger, coming down to pull it (edit.ts shadeAt). */
const swipes = [SHADE.pull.at];

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const hero = HEROES[THEME];
const cues = {
  fps: FPS,
  bpm: BPM,
  frames: DURATION,
  theme: THEME,
  at: AT,
  lands: LINEUP.map((_, i) => AT.lineup + beat(i)),
  sweeps: [...SWEEPS],
  dip: DIP,
  carries: CARRIES.map((at) => ({ at, from: carryFrom(at), frames: CARRY.frames })),
  send: whenShown(hero, mark(hero.take, "send")),
  taps: [...taps(hero, AT.organize, AT.lineup), ...swipes, ...taps(lineupReel(takeOf("phone", THEME)), AT.lineup, AT.end)].sort((a, b) => a - b),
};
mkdirSync(join(video, "public", "audio"), { recursive: true });
writeFileSync(join(video, "public", "audio", "cues.json"), `${JSON.stringify(cues, null, 2)}\n`);
console.log(`cues: ${cues.frames} frames at ${cues.bpm} bpm in the ${cues.theme} cut, the send at ${cues.send}, taps at ${cues.taps.join(", ")}`);
