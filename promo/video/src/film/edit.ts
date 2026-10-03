import { mark } from "../takes";

export const FPS = 60;
export const BPM = 120;
/** Frames to a beat: the score's grid, which every cut lands on. */
export const BEAT = (60 / BPM) * FPS;
export const beat = (n: number) => Math.round(n * BEAT);
export const DURATION = beat(65);

/**
 * One run, one day: the phone is put down on a stone table in the morning, the run is started, steered and followed, it
 * ships by the next morning, and the same app is on the foldable and the tablet beside it. Each shot is [from, to) in
 * frames of the film, every cut on a beat.
 */
export const SHOT = {
  black: { from: 0, to: beat(2) },
  wake: { from: beat(2), to: beat(6) },
  title: { from: beat(6), to: beat(10) },
  say: { from: beat(10), to: beat(18) },
  code: { from: beat(18), to: beat(28) },
  steer: { from: beat(28), to: beat(36) },
  live: { from: beat(36), to: beat(46) },
  ship: { from: beat(46), to: beat(52) },
  phone: { from: beat(52), to: beat(54) },
  foldable: { from: beat(54), to: beat(56) },
  tablet: { from: beat(56), to: beat(58) },
  tableau: { from: beat(58), to: beat(60) },
  end: { from: beat(60), to: DURATION },
} as const;
export type ShotId = keyof typeof SHOT;

export function shotAt(f: number): ShotId {
  for (const [id, s] of Object.entries(SHOT) as [ShotId, { from: number; to: number }][]) if (f >= s.from && f < s.to) return id;
  return "end";
}

/** The time cut inside "Follow it live.": the same framing, from the morning to that night. */
export const NIGHT = beat(38);
/** The lamp coming on, and the phone waking to its lock screen as the run's notification updates. */
export const LAMP = NIGHT + 14;
export const WAKE_AT_NIGHT = NIGHT + 32;
/** The screen waking on the table in the first shot. */
export const WAKE = SHOT.wake.from + 26;

/**
 * The phone's take across the film, as the frame of the take shown at each of these frames of the film (straight lines
 * between them; a jump at a cut), from the capture's marks: the mic at 219, the send at 457, the diff landed by 610
 * and folded at 738, the follow-up queued at 984 and steering at 1026, the tests from 1341, the pull request at 1920.
 */
const SEND = mark("phone", "send");
const MIC = mark("phone", "mic");
export const PHONE_TAKE: [number, number][] = [
  [SHOT.wake.from, 180],
  [SHOT.title.from, 180],
  [beat(11), MIC],
  [SHOT.say.to - 3, SEND],
  [SHOT.say.to - 1, SEND + 2],
  [SHOT.code.from, 560],
  // Past 607, the frame between the edit's row going and its diff drawn: the diff lands whole, on the beat.
  [beat(21) - 1, 606],
  [beat(21), 609],
  [beat(23), 640],
  [beat(26) + 10, 700],
  [SHOT.code.to - 1, 818],
  [SHOT.steer.from, 820],
  [beat(32), mark("phone", "queue")],
  [beat(34), mark("phone", "steer")],
  [SHOT.steer.to - 1, 1120],
  [SHOT.live.from, 1120],
  [NIGHT - 1, 1200],
  [WAKE_AT_NIGHT, 1341],
  [SHOT.live.to - 1, 1800],
  [SHOT.ship.from, 1876],
  [beat(48), mark("phone", "pull request")],
  [beat(50), 2040],
  [SHOT.ship.to - 1, 2060],
];

/** The take frame each lineup device holds: the diff landed, the same moment on every screen. */
export const LOCKED = 640;

/** The phone's take frame at frame [f] of the film. */
export function phoneTake(f: number): number {
  const keys = PHONE_TAKE;
  if (f <= keys[0]![0]) return keys[0]![1];
  for (let i = 1; i < keys.length; i++) {
    const [f1, t1] = keys[i]!;
    const [f0, t0] = keys[i - 1]!;
    if (f <= f1) return Math.round(f1 === f0 ? t1 : t0 + ((t1 - t0) * (f - f0)) / (f1 - f0));
  }
  return keys[keys.length - 1]![1];
}

/** The screen at night is its lock screen: the notification posted over it, the app behind the lock. */
export const lockedAt = (f: number) => f >= WAKE_AT_NIGHT && f < SHOT.live.to;
/** The screen's brightness at frame [f]: off before it wakes on the table, off at night until the notification wakes it. */
export function screenOn(f: number): number {
  const rise = (at: number, frames: number) => Math.max(0, Math.min(1, (f - at) / frames));
  if (f < SHOT.wake.to) return rise(WAKE, 16);
  if (f >= NIGHT && f < SHOT.live.to) return rise(WAKE_AT_NIGHT, 10);
  return 1;
}

/**
 * The diff card's lift off the glass once its lines have landed (take 609, film 630), and its settling back down, all
 * the way, before the take folds it into "2 edits" at take 738 (film 806).
 */
export const LIFT = { up: beat(21) + 10, down: beat(25) + 14, rise: 36, fall: 30 };

/** The lift at frame [f], 0 to 1, overshooting a little on the way up as a spring does. */
export function liftAt(f: number): number {
  const p = Math.max(0, Math.min(1, (f - LIFT.up) / LIFT.rise));
  const q = Math.max(0, Math.min(1, (f - LIFT.down) / LIFT.fall));
  const c = 1.9;
  const up = p <= 0 ? 0 : 1 + (c + 1) * (p - 1) ** 3 + c * (p - 1) ** 2;
  const down = q * q * (3 - 2 * q);
  return up * (1 - down);
}

/**
 * A line of display type: on at [from], off at [to], both on beats, with a six-frame ease in and out so the line
 * never pops. [at] is where it sits in each framing, as fractions of the frame: its left edge (or centre, if
 * [align] is centre) and its top.
 */
export type Words = {
  text: { wide: string; tall: string };
  from: number;
  to: number;
  size: { wide: number; tall: number };
  at: { wide: [number, number]; tall: [number, number] };
  align?: "left" | "center";
  ink: "day" | "night";
};

/** Every verb at one size and one margin, the title's two lines at another, the devices' names at a third. */
const VERB = { size: { wide: 140, tall: 136 }, at: { wide: [0.067, 0.11], tall: [0.09, 0.07] } } as const;
const TITLE = { wide: 120, tall: 116 };
const NAME = { size: { wide: 112, tall: 112 }, at: VERB.at };
const verb = (text: string, from: number, to: number, ink: "day" | "night"): Words => ({
  text: { wide: text, tall: text },
  from,
  to,
  size: { ...VERB.size },
  at: { wide: [...VERB.at.wide], tall: [...VERB.at.tall] },
  ink,
});
const name = (text: string, shot: { from: number; to: number }): Words => ({
  text: { wide: text, tall: text },
  from: shot.from,
  to: shot.to,
  size: { ...NAME.size },
  at: { wide: [...NAME.at.wide], tall: [...NAME.at.tall] },
  ink: "day",
});

/** Six frames to arrive, six to leave; fully opaque on the beat. */
export const TYPE_EASE = 6;

/** How fully [words] is drawn at frame [f]: 0 off, 1 held. Arrives with an ease-out so it is on the beat, leaves smoothly. */
export function typeAt(f: number, words: Words): number {
  const tin = Math.max(0, Math.min(1, (f - words.from + 1) / TYPE_EASE));
  const tout = Math.max(0, Math.min(1, (words.to - f) / TYPE_EASE));
  const arrive = 1 - (1 - tin) ** 3;
  const leave = tout * tout * (3 - 2 * tout);
  return arrive * leave;
}

export const WORDS: Words[] = [
  { text: { wide: "Cursor", tall: "Cursor" }, from: beat(6), to: beat(8), size: TITLE, at: { wide: [0.5, 0.325], tall: [0.5, 0.4] }, align: "center", ink: "day" },
  { text: { wide: "Now on Android.", tall: "Now on\nAndroid." }, from: beat(8), to: beat(10), size: TITLE, at: { wide: [0.5, 0.325], tall: [0.5, 0.38] }, align: "center", ink: "day" },
  verb("Say it.", beat(11), beat(15), "day"),
  verb("Watch it\ncode.", beat(20), beat(24), "day"),
  verb("Steer it.", beat(32), beat(36), "day"),
  verb("Follow it\nlive.", beat(40), beat(44), "night"),
  verb("Ship it.", beat(47), beat(51), "day"),
  name("Phone.", SHOT.phone),
  name("Foldable.", SHOT.foldable),
  name("Tablet.", SHOT.tablet),
];

/** The frames the score's foley lands on: the screen waking, the lamp's switch, the notification, the pull request's tap. */
export const FOLEY = {
  wake: WAKE,
  lamp: LAMP,
  chime: WAKE_AT_NIGHT,
  tap: beat(48),
  send: SHOT.say.to - 3,
  mic: beat(11),
  queue: beat(32),
  steer: beat(34),
};
