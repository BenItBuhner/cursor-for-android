import { easeIn, easeInOut, lerp, progress } from "./math";
import { liveStep, mark, stream, takeOf, takes, type TakeId, type Theme } from "./takes";

export const FPS = 60;
export const BPM = 120;
/** Frames to a beat: the score's grid, which every cut lands on. */
export const BEAT = (60 / BPM) * FPS;
export const beat = (n: number) => Math.round(n * BEAT);

/** Where each part of the video starts, on the beat. */
export const AT = {
  title: beat(0),
  wordmark: beat(1),
  android: beat(3),
  organize: beat(6),
  dictate: beat(11),
  code: beat(18),
  steer: beat(30),
  live: beat(40),
  ship: beat(45),
  lineup: beat(51),
  end: beat(58),
};

export const DURATION = AT.end + beat(7);

/** The app's theme the cut is rendered in: the phone, the lineup and the score all follow it. */
export const THEME: Theme = "dark";

/** The moments told over the phone, each with its headline broken into lines for a wide frame and for a tall one. */
export const MOMENTS = [
  { at: AT.organize, until: AT.dictate, wide: ["Organize", "projects."], tall: ["Organize", "projects."] },
  { at: AT.dictate, until: AT.code, wide: ["Say what", "you want."], tall: ["Say what", "you want."] },
  { at: AT.code, until: AT.steer, wide: ["Watch it", "code."], tall: ["Watch it", "code."] },
  { at: AT.steer, until: AT.live, wide: ["Queue it.", "Steer it."], tall: ["Queue it.", "Steer it."] },
  { at: AT.live, until: AT.ship, wide: ["Follow it", "live."], tall: ["Follow it", "live."] },
  { at: AT.ship, until: AT.lineup, wide: ["Ship it."], tall: ["Ship it."] },
];

/**
 * A take played from frame [take] at frame [at] of the video, [speed] take frames to one of the video's (1 unless the
 * take is only typing, listening or waiting), until the next cut.
 */
export type Cut = { at: number; take: number; speed?: number };
export type Reel = { take: TakeId; cuts: Cut[]; until: number };

/**
 * The take's frame on screen at frame [f] of the video, as the cut that holds frame [on] plays it: run on past the
 * cut's ends when [on] is not [f], as a shot a light carries out, or in, is.
 */
export function takeFrame(reel: Reel, f: number, on = f): number {
  let cut = reel.cuts[0]!;
  for (const c of reel.cuts) if (c.at <= on) cut = c;
  const frame = Math.round(cut.take + (f - cut.at) * (cut.speed ?? 1));
  return Math.max(0, Math.min(takes[reel.take].frames - 1, frame));
}

/** The first frame of the video to show the reel's take at or past frame [take]. */
export function whenShown(reel: Reel, take: number): number {
  for (let f = reel.cuts[0]!.at; f < reel.until; f++) if (takeFrame(reel, f) >= take) return f;
  throw new Error(`${reel.take}: the reel never gets to take frame ${take}`);
}

/**
 * A stretch of a take from frame [from] to frame [to], or for [frames] of the video, at [speed] take frames a frame (1
 * by default; given all three, the speed is what fits). One piece of a part may leave its length out, to fill what the
 * others leave of the part, and then either end: its other end follows.
 */
type Piece = { from?: number; to?: number; frames?: number; speed?: number };
type Part = { at: number; until: number; pieces: Piece[] };

function cuts(parts: Part[]): Cut[] {
  const out: Cut[] = [];
  for (const part of parts) {
    const lengths = part.pieces.map((p) => {
      if (p.frames !== undefined) return p.frames;
      if (p.from !== undefined && p.to !== undefined) return Math.round((p.to - p.from) / (p.speed ?? 1));
      return undefined;
    });
    const open = lengths.filter((n) => n === undefined).length;
    if (open > 1) throw new Error(`The part from ${part.at} leaves the length of ${open} pieces out`);
    const left = part.until - part.at - lengths.reduce<number>((sum, n) => sum + (n ?? 0), 0);
    if (open === 0 && left !== 0) throw new Error(`The part from ${part.at} ends ${left} frames before it should`);
    let at = part.at;
    part.pieces.forEach((p, i) => {
      const frames = lengths[i] ?? left;
      if (frames <= 0) throw new Error(`The part from ${part.at} leaves its piece ${i} no frames`);
      const speed = p.frames !== undefined && p.from !== undefined && p.to !== undefined ? (p.to - p.from) / p.frames : (p.speed ?? 1);
      if (p.from === undefined && p.to === undefined) throw new Error(`The part from ${part.at} gives its piece ${i} no end`);
      const from = p.from ?? p.to! - Math.round(frames * speed);
      out.push(speed === 1 ? { at, take: from } : { at, take: from, speed });
      at += frames;
    });
  }
  return out;
}

/** A pull of the notification shade down, and a fling of it back up if it goes back, from frame [at] for [frames]. */
export type ShadeMoves = { pull: { at: number; frames: number }; fling?: { at: number; frames: number } };

/**
 * The notification shade's pull down over the run, in frames of the video. It never goes back up: it stays down until
 * the light carrying "Ship it." in burns it out with the rest of the shot ([heroShade]).
 */
export const SHADE: ShadeMoves = { pull: { at: AT.live + 10, frames: 24 } };

/** The hero's shade at frame [f] of the shot that holds frame [on]: down over "Follow it live." alone. */
export const heroShade = (on: number, f: number): Shade | undefined => (on >= AT.live && on < AT.ship ? shadeAt(f) : undefined);

/** Frames a finger takes to come down before a swipe, and to fade after it lifts. */
const TOUCH = 3;
const LIFT = 10;

export type Shade = {
  /** 0 with the shade up, 1 with it all the way down. */
  open: number;
  /** The finger on it, as fractions of the screen, while it pulls or flings it and as it lifts. */
  finger: { x: number; y: number; alpha: number } | null;
};

/**
 * The shade at frame [f] as [moves] (the cut's own, by default) move it: a finger comes down on the status bar and
 * pulls it down, and later comes down under the notification and flings it back up, the shade flying the rest of the
 * way on its own.
 */
export function shadeAt(f: number, moves: ShadeMoves = SHADE): Shade {
  const { pull, fling } = moves;
  const pulled = easeInOut(progress(f, pull.at, pull.at + pull.frames));
  const open = fling && f >= fling.at ? 1 - easeIn(progress(f, fling.at, fling.at + fling.frames)) : pulled;
  const swipe = (down: number, lift: number, from: number, to: number) => {
    if (f < down - TOUCH || f >= lift + LIFT) return null;
    const y = lerp(from, to, easeInOut(progress(f, down, lift)));
    const alpha = f < down ? (f - down + TOUCH + 1) / (TOUCH + 1) : 1 - progress(f, lift, lift + LIFT);
    return { x: 0.5, y, alpha };
  };
  const finger = swipe(pull.at, pull.at + pull.frames, 0.012, 0.46) ?? (fling ? swipe(fling.at - 4, fling.at + 6, 0.64, 0.3) : null);
  return { open, finger };
}

/** Frames the answer to the steer is left up to be read once it has landed, before the shade comes down over it. */
const READ = 45;

/**
 * [take]'s run through the six moments, from its own marks, so a device's dark and light takes cut alike. A Project is
 * held, lifted and carried to the front, and the part ends as it settles; the task is said into the microphone, the
 * listening shown at twice the take's pace, the words landing (less the one frame the composer's footer drops its label
 * on as they do) and sent; the chat opens and the run writes its first edit, opened as it is written so its diff lands
 * in view, folded away again, and the follow-up field tapped; a follow-up is typed as the run writes the rest of its
 * edits, queued, steered (the wait for the steer to be taken at three times the pace) and answered, the answer left up
 * to be read; the notification shade pulled down over the run as it tests and opens its pull request, the light carrying
 * it out while it waits on its checks; and the answer, with the pull request's section of the
 * details. Everything the run streams plays as the capture filmed it, and the cuts leave out whole stretches of the run
 * rather than any of what it says.
 */
export function hero(take: TakeId): Reel {
  const m = (name: string) => mark(take, name);
  const s = (key: string) => stream(take, key);
  /** The frame the composer's footer shows with no label, between "Transcribing…" and its fade out. */
  const blink = m("the words") + 1;
  const waiting = { from: m("steer") + 6, to: m("the steer") - 2, speed: 3 };
  const answered = s("hero.adapt").to + READ;
  /**
   * Where "Queue it. Steer it." picks the run up: as far before the wait for the steer as the part has room for once
   * the wait and the answer have theirs. The edits the run writes under the typing hold it to the take's own pace.
   */
  const queueing =
    waiting.from - (AT.live - AT.steer - Math.round((waiting.to - waiting.from) / waiting.speed) - (answered - waiting.to));
  /** Where "Watch it code." picks the run up: as far before that as the part is long. */
  const coding = queueing - (AT.steer - AT.code);
  /** The tests running as the shade comes down, the step moving on to the pull request once it is down. */
  const shaded = liveStep(take, "Running gh pr create --fill").from - 70;
  const answering = s("hero.final").from - 10;
  const reel: Reel = {
    take,
    until: AT.lineup,
    cuts: cuts([
      { at: AT.organize, until: AT.dictate, pieces: [{ from: m("hold") - 24 }] },
      {
        at: AT.dictate,
        until: AT.code,
        pieces: [
          { to: m("mic") + 16 },
          { from: m("mic") + 16, to: m("stop") - 10, speed: 2 },
          { from: m("stop") - 10, to: blink },
          { from: blink + 1, to: coding },
        ],
      },
      { at: AT.code, until: AT.steer, pieces: [{ from: coding }] },
      {
        at: AT.steer,
        until: AT.live,
        pieces: [{ from: queueing, to: waiting.from }, waiting, { from: waiting.to, to: answered }],
      },
      {
        at: AT.live,
        until: AT.ship,
        pieces: [{ from: shaded }],
      },
      {
        at: AT.ship,
        until: AT.lineup,
        pieces: [
          { from: answering, to: m("details") + 30 },
          { from: m("details") + 30, to: m("pull request") - 4, speed: 2 },
          { from: m("pull request") - 4 },
        ],
      },
    ]),
  };
  const organized = takeFrame(reel, AT.dictate - 1);
  if (organized < m("dropped") + 9) throw new Error(`${take}: "Organize projects." ends at ${organized}, before the Project settles`);
  if (takeFrame(reel, AT.dictate) < organized) throw new Error(`${take}: "Say what you want." goes back to ${takeFrame(reel, AT.dictate)}`);
  const sendAt = whenShown(reel, m("send"));
  if (sendAt >= AT.code) throw new Error(`${take}: the prompt is sent at ${sendAt}, after "Say what you want." ends`);
  const lead = whenShown(reel, m("mic")) - AT.dictate;
  if (lead < 8) throw new Error(`${take}: the microphone is tapped ${lead} frames into "Say what you want."`);
  if (queueing <= m("follow-up") || queueing > m("follow-up") + 20) {
    throw new Error(`${take}: "Queue it. Steer it." picks the run up at ${queueing}, not as the follow-up starts to be typed`);
  }
  return reel;
}

/** The hero's take, in the app's [theme]. */
export const heroTake = (theme: Theme) => takeOf("phone", theme);
export const HEROES: Record<Theme, Reel> = { dark: hero(heroTake("dark")), light: hero(heroTake("light")) };

/**
 * Frames into the lineup the first diff lands on every device: once the last has landed and the camera settled on all
 * three, and late enough that every lineup ends before the diff's fold is tapped.
 */
export const LINEUP_DIFF = 83;

/**
 * The frames a light sweeps across the stage on, left to right behind whatever stands on it, carrying nothing: the
 * first act's first frame, which the green card closes into, and the frame the lineup's diff lands on all three devices.
 */
export const SWEEPS = [AT.organize, AT.lineup + LINEUP_DIFF] as const;

/**
 * The cuts a light carries: each act's first frame after the first, and the lineup's. Its beam crosses the frame at an
 * even pace over [CARRY.frames], over the middle of the frame on the cut, and the shot going out burns out under it as
 * the one coming in comes up in its wake.
 */
export const CARRIES = [AT.dictate, AT.code, AT.steer, AT.live, AT.ship, AT.lineup] as const;
export const CARRY = { frames: 38 };

/** Frames before the end card the stage closes over the lineup, and the score falls away, so the card lands out of a dark, quiet beat. */
export const DIP = 16;

/** The frame the light carrying the cut at [at] sets out from, off the frame's left. */
export const carryFrom = (at: number) => at - CARRY.frames / 2;

/**
 * Each device's take in the lineup: the same stretch, the first edit open as it is written and its diff landing at
 * [LINEUP_DIFF], in step. Held to the capture's own steps, which every take films on the same frames.
 */
export const lineupReel = (take: TakeId): Reel => ({
  take,
  until: AT.end,
  cuts: [{ at: AT.lineup, take: mark(take, "the diff") - LINEUP_DIFF }],
});

export const LINEUP = ["phone", "foldable", "tablet"] as const;

/** Every reel the cut plays in the app's [theme]. */
export const reelsOf = (theme: Theme): Reel[] => [HEROES[theme], ...LINEUP.map((d) => lineupReel(takeOf(d, theme)))];

/**
 * The rule the whole cut keeps: what the run streams (its text and the lines of its edits) is never shown faster than
 * the capture filmed it, which the capture holds to 100 tokens a second, and no cut jumps out of or into the middle of
 * one. Throws on the first cut that breaks it.
 */
export function check(reels: Reel[]) {
  for (const reel of reels) {
    const take = takes[reel.take];
    if (take.peakPerSecond > 100) throw new Error(`${reel.take}: the capture streamed ${take.peakPerSecond} tokens in a second`);
    reel.cuts.forEach((cut, i) => {
      const end = reel.cuts[i + 1]?.at ?? reel.until;
      if (end <= cut.at) throw new Error(`${reel.take}: the cut at ${cut.at} is out of order`);
      const from = cut.take;
      const to = Math.round(cut.take + (end - 1 - cut.at) * (cut.speed ?? 1));
      if (to >= take.frames) throw new Error(`${reel.take}: the take runs out before frame ${end}`);
      for (const st of take.streams) {
        if ((cut.speed ?? 1) > 1 && from <= st.to && to >= st.from) {
          throw new Error(`${reel.take}: the cut at ${cut.at} plays ${st.key} (take ${st.from}–${st.to}) at ${cut.speed}x`);
        }
      }
      const next = reel.cuts[i + 1];
      if (!next || Math.abs(next.take - (to + 1)) <= 1) return;
      for (const st of take.streams) {
        const inside = (x: number) => x > st.from && x < st.to;
        if (inside(to) || inside(next.take)) {
          throw new Error(`${reel.take}: the cut at ${next.at} jumps from take ${to} to ${next.take}, inside ${st.key}`);
        }
      }
    });
  }
}
