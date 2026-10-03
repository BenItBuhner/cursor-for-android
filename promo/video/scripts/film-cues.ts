// Writes public/audio/film-cues.json, what scripts/music.py scores the film (src/film) to: the same parts as the
// screen cut's score laid on the film's shots, the night the time cut drops the kit for, the beats the lineup's devices
// land on, the dip into the end card, the frame the prompt is sent on, the frames a finger comes down on the phone's
// take, and the foley the day is made of: the screen waking, the lamp's switch and the notification's chime.
//
//   npx tsx scripts/film-cues.ts
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { BPM, beat, DURATION, FOLEY, FPS, NIGHT, phoneTake, SHOT } from "../src/film/edit";
import { takes } from "../src/takes";

/** The most take frames one frame of the film moves through that still count as playing, not a cut. */
const PLAYING = 4;

function taps(from: number, until: number): number[] {
  const touch = takes.phone.touch;
  const out: number[] = [];
  for (let f = from + 1; f < until; f++) {
    const now = phoneTake(f);
    const before = phoneTake(f - 1);
    if (now - before < 1 || now - before > PLAYING) continue;
    for (let k = before + 1; k <= now; k++) if (touch[k]?.[2] === 1 && touch[k - 1]?.[2] !== 1) out.push(f);
  }
  return out;
}

const video = join(dirname(fileURLToPath(import.meta.url)), "..");
const cues = {
  fps: FPS,
  bpm: BPM,
  frames: DURATION,
  at: {
    title: SHOT.wake.from,
    wordmark: FOLEY.wake,
    android: SHOT.title.from,
    organize: SHOT.say.from,
    dictate: beat(14),
    code: SHOT.code.from,
    steer: SHOT.steer.from,
    live: SHOT.live.from,
    night: NIGHT,
    ship: SHOT.ship.from,
    lineup: SHOT.phone.from,
    end: SHOT.end.from,
  },
  lands: [SHOT.phone.from, SHOT.foldable.from, SHOT.tablet.from],
  sweeps: [],
  dip: 16,
  carries: [],
  send: FOLEY.send,
  taps: [...taps(SHOT.say.from, SHOT.live.from), ...taps(SHOT.ship.from, SHOT.ship.to), FOLEY.mic, FOLEY.tap]
    .sort((a, b) => a - b)
    .filter((f, i, all) => i === 0 || f - all[i - 1]! > 4),
  foley: { screen: [FOLEY.wake], lamp: FOLEY.lamp, chime: FOLEY.chime },
};
mkdirSync(join(video, "public", "audio"), { recursive: true });
writeFileSync(join(video, "public", "audio", "film-cues.json"), `${JSON.stringify(cues, null, 2)}\n`);
console.log(`film cues: ${cues.frames} frames at ${cues.bpm} bpm, the night at ${NIGHT}, the send at ${cues.send}, taps at ${cues.taps.join(", ")}`);
