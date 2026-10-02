/**
 * The cut: which frames of which take play when, where the camera stands, what the captions say and where the score's
 * sections fall. A take always plays at its own pace, one of its frames per frame of the video, so nothing the app
 * streamed is ever sped through; `check` keeps every cut out of the frames a stream lands in.
 *
 * Free of React and of the takes themselves, so that `scripts/cues.ts` can read it with Node alone.
 */

export const FPS = 60;
export const BPM = 100;
/** Frames a beat of the score lasts. Cuts fall on beats. */
export const BEAT = (FPS * 60) / BPM;
export const BAR = BEAT * 4;
/** The frame beat [n] of the score starts on. */
export const b = (n: number) => Math.round(n * BEAT);

export const DURATION = b(92);

export type TakeId = "hero-phone" | "hero-large" | "desktop";

/** Timeline frames [from, to) showing a take from its frame [src] on, at its own pace. */
export type Run = { take: TakeId; from: number; to: number; src: number };

/** The pan from the tablet to the monitor, its swiftest frame on the desk section's downbeat. */
export const WHIP = { from: b(63.5), to: b(64.5) };

export const RUNS: Run[] = [
  // The model sheet: the tap on the model lands on the second beat of the first bar with the phone.
  { take: "hero-phone", from: 108, to: b(8), src: 0 },
  // From the prompt typed to the details opened, unbroken: the send, the thinking, the tools, the subagents, the queue.
  { take: "hero-phone", from: b(8), to: 1378, src: 246 },
  // Unfolded: the details pinned, the window growing into the tablet's, the edits landing.
  { take: "hero-large", from: 1392, to: b(51), src: 0 },
  // The tests' last stretch, the answer, the queued follow-up going out and its thinking.
  { take: "hero-large", from: b(51), to: b(59), src: 760 },
  // The pull request's answer.
  { take: "hero-large", from: b(59), to: WHIP.to, src: 1120 },
  // Desktop mode: the numbered sidebar, the Project and its panel. Ctrl goes down as the monitor lands.
  { take: "desktop", from: WHIP.from, to: b(72), src: 42 },
  // The palette finding the worker, the worker, the switcher back to the Project.
  { take: "desktop", from: b(72), to: b(85), src: 456 },
];

/** The frame of [take] on screen at timeline frame [t]: a run's first frame before it, its last one after. */
export function sourceFrame(take: TakeId, t: number): number {
  const runs = RUNS.filter((r) => r.take === take);
  let run = runs[0];
  if (!run) throw new Error(`No run of ${take}`);
  for (const r of runs) if (t >= r.from) run = r;
  return run.src + Math.max(0, Math.min(t, run.to - 1) - run.from);
}

// ---- The devices, in millimetres (the handheld) and in the desktop's own pixels (the monitor) ---------------------

/** Pixel Fold. Each half is the folded phone's width; the cover screen sits on the outside of the left one. */
export const FOLD = {
  halfWidth: 79.35,
  height: 139.7,
  halfDepth: 5.8,
  cover: { x: 6.075, y: 4.75, w: 67.2, h: 130.2 },
  /** The inner screen's bezel, left and right, top and bottom. */
  innerBezel: { x: 5.55, y: 8.35 },
  radius: 8.5,
  coverRadius: 5.2,
  innerRadius: 3.2,
};

/** Pixel Tablet's bezel, corners and thickness, around a screen of the tablet's dp at the Fold's inner density. */
export const TABLET = { bezel: { x: 11, y: 11 }, radius: 13, screenRadius: 4, depth: 8.1 };

/** Take pixels per millimetre: the capture filmed each screen at three quarters of its pixels. */
export const PX_PER_MM = { cover: 1568 / 130.2, inner: 1380 / 123 };

/** The inner screen at the Fold's size and at the tablet's, in mm: the take's window at those two sizes. */
export const INNER = { w: 1655 / PX_PER_MM.inner, h: 123 };
export const TABLET_SCREEN = { w: 2520 / PX_PER_MM.inner, h: 1574 / PX_PER_MM.inner };

/** A desktop-mode monitor: the take's 1920x1032 window over a 48 px taskbar, in the window's own pixels. */
export const MONITOR = { w: 1920, h: 1080, window: 1032, taskbar: 48, bezel: 14, chin: 22, pxPerUnit: 1.5 };

/** Points on the screens, from their top left as fractions of them, where the camera looks; the device's centre is 0,0. */
export const onCover = (fx: number, fy: number): [number, number] => [
  FOLD.cover.x + fx * FOLD.cover.w,
  -FOLD.height / 2 + FOLD.cover.y + fy * FOLD.cover.h,
];
export const onInner = (fx: number, fy: number): [number, number] => [(fx - 0.5) * INNER.w, (fy - 0.5) * INNER.h];
export const onTablet = (fx: number, fy: number): [number, number] => [
  (fx - 0.5) * TABLET_SCREEN.w,
  (fy - 0.5) * TABLET_SCREEN.h,
];
/** A point of the desktop's window in its pixels, from the screen's centre. */
export const onDesk = (x: number, y: number): [number, number] => [x - MONITOR.w / 2, y - MONITOR.h / 2];

// ---- The camera ---------------------------------------------------------------------------------------------------

/**
 * Where the camera stands: [at] (a point of the device, in its units, from its centre) sits at [ax], [ay] of the frame,
 * [k] frame pixels to the unit, the device turned [rx], [ry], [rz] degrees about that point.
 */
export type Pose = { at: [number, number]; k: number; rx: number; ry: number; rz: number; ax: number; ay: number };
type PoseIn = Partial<Omit<Pose, "at" | "k">> & Pick<Pose, "at" | "k">;
const pose = (p: PoseIn): Pose => ({ rx: 0, ry: 0, rz: 0, ax: 0.5, ay: 0.5, ...p });

export type Ease = "drift" | "swift" | "out";
/** The camera from [a] at [from] to [b] at [to], for the wide cut and the tall one. A shot starting where another ends cuts. */
export type Shot = { from: number; to: number; ease: Ease; wide: [Pose, Pose]; tall: [Pose, Pose] };

const shot = (from: number, to: number, ease: Ease, wide: [PoseIn, PoseIn], tall: [PoseIn, PoseIn]): Shot => ({
  from,
  to,
  ease,
  wide: [pose(wide[0]), pose(wide[1])],
  tall: [pose(tall[0]), pose(tall[1])],
});

/** Continues [s]'s last pose: a move out of a shot rather than a cut. */
const from = (s: Shot, framing: "wide" | "tall"): PoseIn => s[framing][1];

const PHONE_X = 0.645;
const entrance = shot(
  96,
  b(4) + 12,
  "out",
  [
    { at: onCover(0.5, 0.5), k: 5.0, rx: 64, ry: -6, rz: 6, ax: PHONE_X, ay: 1.3 },
    { at: onCover(0.5, 0.5), k: 6.5, rx: 9, ry: -19, ax: PHONE_X },
  ],
  [
    { at: onCover(0.5, 0.5), k: 8.4, rx: 64, ry: -6, rz: 6, ay: 1.3 },
    { at: onCover(0.5, 0.5), k: 10.2, rx: 9, ry: -16, ay: 0.57 },
  ],
);
const models = shot(
  entrance.to,
  b(8),
  "drift",
  [from(entrance, "wide"), { at: onCover(0.5, 0.47), k: 7.1, rx: 6, ry: -13, ax: PHONE_X }],
  [from(entrance, "tall"), { at: onCover(0.5, 0.47), k: 10.8, rx: 6, ry: -11, ay: 0.57 }],
);
const unfoldFrom = b(38);
const unfoldTo = b(40) + 12;
const panel = shot(
  b(35),
  unfoldFrom,
  "drift",
  [
    { at: onCover(0.5, 0.5), k: 7.0, rx: 4, ry: -15, ax: PHONE_X },
    { at: onCover(0.5, 0.5), k: 6.7, rx: 5, ry: -21, ax: PHONE_X },
  ],
  [
    { at: onCover(0.5, 0.5), k: 10.6, rx: 4, ry: -13, ay: 0.57 },
    { at: onCover(0.5, 0.5), k: 10.2, rx: 5, ry: -19, ay: 0.57 },
  ],
);
const unfold = shot(
  unfoldFrom,
  unfoldTo,
  "swift",
  [from(panel, "wide"), { at: onInner(0.5, 0.5), k: 6.35, rx: 6, ry: -9, ax: 0.6 }],
  [from(panel, "tall"), { at: onInner(0.5, 0.5), k: 6.6, rx: 6, ry: -8, ay: 0.56 }],
);
const inner = shot(
  unfold.to,
  b(43),
  "drift",
  [from(unfold, "wide"), { at: onInner(0.5, 0.5), k: 6.75, rx: 4, ry: -5, ax: 0.6 }],
  [from(unfold, "tall"), { at: onInner(0.62, 0.45), k: 8.4, rx: 4, ry: -6, ay: 0.56 }],
);
/**
 * The window grows into the tablet's from here: the camera draws back with it, square on, and holds the tablet high
 * enough in the wide frame for the caption under it to sit on the floor rather than over the composer.
 */
const grow = shot(
  b(43),
  b(45),
  "swift",
  [from(inner, "wide"), { at: onTablet(0.5, 0.5), k: 5.4, rx: 3, ry: 0, ax: 0.5, ay: 0.41 }],
  [from(inner, "tall"), { at: onTablet(0.5, 0.5), k: 4.25, rx: 3, ry: 0, ay: 0.55 }],
);
const tablet = shot(
  b(45),
  b(47),
  "drift",
  [from(grow, "wide"), { at: onTablet(0.5, 0.5), k: 5.52, rx: 4, ry: 2, ax: 0.5, ay: 0.41 }],
  [from(grow, "tall"), { at: onTablet(0.5, 0.5), k: 4.4, rx: 4, ry: 2, ay: 0.55 }],
);
const answer = shot(
  b(51),
  b(57),
  "drift",
  [
    { at: onTablet(0.47, 0.6), k: 9.0, rx: 5, ry: 12, ax: 0.6 },
    { at: onTablet(0.47, 0.72), k: 9.6, rx: 5, ry: 8, ax: 0.6 },
  ],
  [
    { at: onTablet(0.47, 0.6), k: 10.6, rx: 5, ry: 10, ay: 0.56 },
    { at: onTablet(0.47, 0.7), k: 11.2, rx: 5, ry: 7, ay: 0.56 },
  ],
);
const pullRequest = shot(
  b(59),
  WHIP.from,
  "drift",
  [
    { at: onTablet(0.5, 0.64), k: 9.2, rx: 6, ry: 10, ax: 0.56 },
    { at: onTablet(0.53, 0.56), k: 7.0, rx: 4, ry: 4, ax: 0.53, ay: 0.48 },
  ],
  [
    { at: onTablet(0.47, 0.66), k: 10.8, rx: 6, ry: 9, ay: 0.56 },
    { at: onTablet(0.62, 0.55), k: 6.4, rx: 4, ry: 4, ay: 0.56 },
  ],
);
// The tablet and the monitor stand side by side, and the pan carries both: the monitor keeps the distance it chases the
// tablet at, so that one is always leaving the frame as the other comes into it.
const handheldOut = shot(
  WHIP.from,
  WHIP.to,
  "swift",
  [from(pullRequest, "wide"), { at: onTablet(0.5, 0.5), k: 6.2, rx: 4, ry: 58, rz: -2, ax: -0.22, ay: 0.5 }],
  [from(pullRequest, "tall"), { at: onTablet(0.5, 0.5), k: 5.6, rx: 4, ry: 58, rz: -2, ax: -0.32, ay: 0.55 }],
);

/** The Fold, unfolding into the Pixel Fold's inner screen and growing into the tablet. */
export const HANDHELD_SHOTS: Shot[] = [
  entrance,
  models,
  shot(
    b(8),
    b(12),
    "drift",
    [
      { at: onCover(0.5, 0.25), k: 10.4, rx: 10, ry: 15, ax: 0.62, ay: 0.47 },
      { at: onCover(0.5, 0.23), k: 11.0, rx: 9, ry: 11, ax: 0.62, ay: 0.47 },
    ],
    [
      { at: onCover(0.5, 0.26), k: 15.0, rx: 10, ry: 13, ay: 0.52 },
      { at: onCover(0.5, 0.24), k: 15.8, rx: 9, ry: 10, ay: 0.52 },
    ],
  ),
  shot(
    b(12),
    b(16),
    "drift",
    [
      { at: onCover(0.5, 0.33), k: 8.3, rx: 4, ry: -10, ax: PHONE_X },
      { at: onCover(0.5, 0.36), k: 7.7, rx: 5, ry: -14, ax: PHONE_X },
    ],
    [
      { at: onCover(0.5, 0.35), k: 12.4, rx: 4, ry: -9, ay: 0.55 },
      { at: onCover(0.5, 0.38), k: 11.8, rx: 5, ry: -12, ay: 0.55 },
    ],
  ),
  shot(
    b(16),
    b(20),
    "drift",
    [
      { at: onCover(0.5, 0.26), k: 11.6, rx: 8, ry: 10, ax: 0.6, ay: 0.45 },
      { at: onCover(0.5, 0.28), k: 12.3, rx: 7, ry: 7, ax: 0.6, ay: 0.45 },
    ],
    [
      { at: onCover(0.5, 0.27), k: 16.0, rx: 8, ry: 9, ay: 0.54 },
      { at: onCover(0.5, 0.29), k: 16.8, rx: 7, ry: 6, ay: 0.54 },
    ],
  ),
  shot(
    b(20),
    b(24),
    "drift",
    [
      { at: onCover(0.5, 0.4), k: 9.0, rx: 5, ry: -12, rz: -1.5, ax: PHONE_X },
      { at: onCover(0.5, 0.42), k: 9.6, rx: 5, ry: -9, rz: -1, ax: PHONE_X },
    ],
    [
      { at: onCover(0.5, 0.4), k: 13.2, rx: 5, ry: -10, rz: -1.5, ay: 0.55 },
      { at: onCover(0.5, 0.42), k: 13.8, rx: 5, ry: -8, rz: -1, ay: 0.55 },
    ],
  ),
  shot(
    b(24),
    b(28),
    "drift",
    [
      { at: onCover(0.5, 0.55), k: 9.2, rx: 6, ry: 12, ax: 0.62 },
      { at: onCover(0.5, 0.58), k: 9.8, rx: 6, ry: 9, ax: 0.62 },
    ],
    [
      { at: onCover(0.5, 0.55), k: 13.4, rx: 6, ry: 10, ay: 0.55 },
      { at: onCover(0.5, 0.58), k: 14.0, rx: 6, ry: 8, ay: 0.55 },
    ],
  ),
  shot(
    b(28),
    b(32),
    "drift",
    [
      { at: onCover(0.5, 0.62), k: 8.4, rx: 4, ry: -12, ax: PHONE_X },
      { at: onCover(0.5, 0.7), k: 9.2, rx: 5, ry: -9, ax: PHONE_X },
    ],
    [
      { at: onCover(0.5, 0.62), k: 12.6, rx: 4, ry: -10, ay: 0.55 },
      { at: onCover(0.5, 0.7), k: 13.4, rx: 5, ry: -8, ay: 0.55 },
    ],
  ),
  shot(
    b(32),
    b(35),
    "drift",
    [
      { at: onCover(0.5, 0.83), k: 11.4, rx: 10, ry: 10, ax: 0.6, ay: 0.54 },
      { at: onCover(0.5, 0.8), k: 12.0, rx: 9, ry: 7, ax: 0.6, ay: 0.54 },
    ],
    [
      { at: onCover(0.5, 0.83), k: 16.0, rx: 10, ry: 9, ay: 0.55 },
      { at: onCover(0.5, 0.8), k: 16.8, rx: 9, ry: 6, ay: 0.55 },
    ],
  ),
  panel,
  unfold,
  inner,
  grow,
  tablet,
  shot(
    b(47),
    b(51),
    "drift",
    [
      { at: onTablet(0.8, 0.43), k: 10.4, rx: 6, ry: -14, ax: 0.55 },
      { at: onTablet(0.8, 0.45), k: 11.1, rx: 6, ry: -10, ax: 0.55 },
    ],
    [
      { at: onTablet(0.81, 0.44), k: 10.8, rx: 6, ry: -12, ay: 0.56 },
      { at: onTablet(0.81, 0.46), k: 11.6, rx: 6, ry: -9, ay: 0.56 },
    ],
  ),
  answer,
  shot(
    b(57),
    b(59),
    "drift",
    [
      { at: onTablet(0.5, 0.72), k: 8.2, rx: 3, ry: -8, ax: 0.54 },
      { at: onTablet(0.5, 0.7), k: 8.6, rx: 3, ry: -6, ax: 0.54 },
    ],
    [
      { at: onTablet(0.46, 0.72), k: 10.2, rx: 3, ry: -7, ay: 0.56 },
      { at: onTablet(0.46, 0.7), k: 10.6, rx: 3, ry: -5, ay: 0.56 },
    ],
  ),
  pullRequest,
  handheldOut,
];

/** The Fold's hinge, 0 shut to 1 flat: the left half swings open over the unfold's first two beats. */
export const UNFOLD = { from: unfoldFrom + 6, to: unfoldFrom + 6 + b(1.75) };
/** Frames the handheld is on screen. */
export const HANDHELD = { from: entrance.from, to: handheldOut.to };

const deskIn = shot(
  WHIP.from,
  WHIP.to,
  "swift",
  [
    { at: onDesk(960, 560), k: 0.78, rx: 4, ry: -54, rz: 2, ax: 1.26 },
    { at: onDesk(960, 560), k: 0.83, rx: 3, ry: -9, ax: 0.5, ay: 0.49 },
  ],
  [
    { at: onDesk(300, 420), k: 1.05, rx: 4, ry: -54, rz: 2, ax: 1.32, ay: 0.55 },
    { at: onDesk(300, 420), k: 1.3, rx: 3, ry: -8, ay: 0.55 },
  ],
);

/** The monitor, in desktop mode. */
export const DESK_SHOTS: Shot[] = [
  deskIn,
  shot(
    deskIn.to,
    b(68),
    "drift",
    [from(deskIn, "wide"), { at: onDesk(920, 540), k: 0.87, rx: 3, ry: -5, ax: 0.5, ay: 0.49 }],
    [from(deskIn, "tall"), { at: onDesk(420, 420), k: 1.36, rx: 3, ry: -6, ay: 0.55 }],
  ),
  shot(
    b(68),
    b(72),
    "drift",
    [
      { at: onDesk(1150, 300), k: 1.22, rx: 5, ry: 9, ax: 0.52 },
      { at: onDesk(1260, 320), k: 1.14, rx: 5, ry: 5, ax: 0.52 },
    ],
    // From the Project's chat across to its panel as it slides in.
    [
      { at: onDesk(1086, 160), k: 1.6, rx: 5, ry: 7, ay: 0.5 },
      { at: onDesk(1505, 370), k: 1.25, rx: 5, ry: 4, ay: 0.5 },
    ],
  ),
  shot(
    b(72),
    b(77),
    "drift",
    // The palette right of the caption beside it.
    [
      { at: onDesk(953, 190), k: 1.5, rx: 7, ry: -6, ax: 0.67, ay: 0.42 },
      { at: onDesk(953, 200), k: 1.58, rx: 6, ry: -3, ax: 0.67, ay: 0.42 },
    ],
    [
      { at: onDesk(962, 190), k: 1.5, rx: 7, ry: -5, ay: 0.45 },
      { at: onDesk(962, 200), k: 1.56, rx: 6, ry: -3, ay: 0.45 },
    ],
  ),
  shot(
    b(77),
    b(85),
    "drift",
    [
      { at: onDesk(960, 470), k: 1.0, rx: 4, ry: 7, ax: 0.5, ay: 0.48 },
      { at: onDesk(960, 520), k: 0.9, rx: 3, ry: 3, ax: 0.5, ay: 0.48 },
    ],
    [
      { at: onDesk(953, 360), k: 1.5, rx: 4, ry: 6, ay: 0.5 },
      { at: onDesk(960, 470), k: 1.02, rx: 3, ry: 3, ay: 0.5 },
    ],
  ),
];

export const DESK = { from: deskIn.from, to: b(85) };

/** The end card: the takes' last looks on every screen they were filmed on, side by side. */
export const END = { from: b(84), to: DURATION };

// ---- Captions -----------------------------------------------------------------------------------------------------

/** Where a caption stands: beside the device, or under it when the device fills the frame's width. */
export type CaptionPlace = "side" | "below";
export type Caption = { from: number; to: number; kicker: string; text: string; place: CaptionPlace };

const caption = (from: number, to: number, kicker: string, text: string, place: CaptionPlace = "side"): Caption => ({
  from,
  to,
  kicker,
  text,
  place,
});

export const CAPTIONS: Caption[] = [
  caption(b(5) + 6, b(8) - 6, "Models", "Pick the model for the job"),
  caption(b(12) + 6, b(16) - 6, "Agents", "Send a task from anywhere"),
  caption(b(16) + 18, b(20) - 6, "Live", "Watch it think, token by token"),
  caption(b(20) + 12, b(24) - 6, "Tools", "Every tool call, as it runs"),
  caption(b(28) + 6, b(31), "Subagents", "Subagents take a piece each"),
  caption(b(32) + 6, b(35) - 6, "Queue", "Line up what comes next"),
  caption(b(40) + 12, b(43) - 6, "Foldables", "Unfold, and the panel pins alongside"),
  caption(b(44) + 12, b(47) - 6, "Tablets", "Room for the whole workspace", "below"),
  caption(b(47) + 12, b(51) - 6, "Changes", "Edits land as they're made"),
  caption(b(55) + 12, b(59) - 6, "Queue", "The follow-up goes out on its own"),
  caption(b(60), WHIP.from - 6, "Pull requests", "From prompt to pull request"),
  caption(b(65) + 18, b(68) - 6, "Desktop mode", "Keyboard first on the big screen", "below"),
  // Out before Ctrl+Shift+B, whose keycaps would read against it.
  caption(b(68) + 12, b(71) - 20, "Projects", "Ctrl+1 opens a Project"),
  caption(b(72) + 12, b(77) - 6, "Search", "Ctrl+K finds any chat"),
  caption(b(80), b(84) - 12, "Switcher", "Ctrl+Tab flips between chats", "below"),
];

// ---- The score ----------------------------------------------------------------------------------------------------

/** Where the score changes: what `scripts/music.py` builds its arrangement on. */
export const SECTIONS = [
  { name: "intro", from: 0 },
  { name: "groove", from: b(4) },
  { name: "lift", from: b(28) },
  { name: "build", from: b(36) },
  { name: "drop", from: b(40) },
  { name: "tablet", from: b(44) },
  { name: "desk", from: b(64) },
  { name: "outro", from: END.from },
] as const;

/** Moves the score marks with a swell: the phone rising, the unfold, the growth, the monitor coming in. */
export const WHOOSHES = [entrance.from + 4, unfold.from + 6, grow.from, deskIn.from];

/** Every frame the picture cuts on: a shot starting where none continues into it. */
export function cuts(): number[] {
  const all = [...HANDHELD_SHOTS, ...DESK_SHOTS];
  const out = new Set<number>();
  for (const s of all) if (!all.some((o) => o !== s && o.to === s.from && sameStage(o, s))) out.add(s.from);
  for (const r of RUNS) out.add(r.from);
  return [...out].sort((x, y) => x - y);
}

const sameStage = (x: Shot, y: Shot) => HANDHELD_SHOTS.includes(x) === HANDHELD_SHOTS.includes(y);

// ---- Checks -------------------------------------------------------------------------------------------------------

type TakeFacts = { frames: number; streams: { key: string; from: number; to: number }[] };

/**
 * Fails on a cut that would break the takes' pace: a run past its take's end, or one that starts or stops inside the
 * frames a stream lands in (a run holding a stream plays all of it, at its own speed).
 */
export function check(takes: Record<TakeId, TakeFacts>): void {
  for (const r of RUNS) {
    const take = takes[r.take];
    const last = r.src + (r.to - r.from) - 1;
    const ofTake = RUNS.filter((o) => o.take === r.take);
    if (r.src < 0 || r.to <= r.from) throw new Error(`Run of ${r.take} at ${r.from} is empty`);
    if (last > take.frames - 1 && r !== ofTake[ofTake.length - 1]) {
      throw new Error(`Run of ${r.take} at ${r.from} runs past the take (${last} > ${take.frames - 1})`);
    }
    for (const s of take.streams) {
      const inside = (f: number) => f > s.from && f <= s.to;
      if (inside(r.src) || inside(Math.min(last, take.frames - 1) + 1)) {
        throw new Error(`Run of ${r.take} at ${r.from} (${r.src}-${last}) cuts into ${s.key} (${s.from}-${s.to})`);
      }
    }
  }
  for (const list of [HANDHELD_SHOTS, DESK_SHOTS]) {
    list.forEach((s, i) => {
      const next = list[i + 1];
      if (s.to <= s.from) throw new Error(`Shot at ${s.from} is empty`);
      if (next && next.from < s.to) throw new Error(`Shots at ${s.from} and ${next.from} overlap`);
    });
  }
}
