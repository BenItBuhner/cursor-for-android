import raw from "../public/footage/takes.json";

export type DeviceId = "phone" | "foldable" | "tablet";
export type TakeId = DeviceId | `${DeviceId}-light`;

/** The app's dark theme, or its light one: which of a device's takes plays. */
export type Theme = "dark" | "light";
export const takeOf = (device: DeviceId, theme: Theme): TakeId => (theme === "dark" ? device : `${device}-light`);

/** Text or an edit the scripted run streamed in a take: the frames from its first token to its landing on screen. */
export type Stream = { key: string; kind: "thinking" | "assistant" | "edit"; from: number; to: number; tokens: number };

/** A step the live notification showed, from frame [from] to frame [to]: its text, the phase in its header, its actions. */
export type LiveStep = { from: number; to: number; text: string; sub: string; actions: string[]; indeterminate: boolean };

/** A take as `scripts/footage.mjs` wrote it: the MP4 and what the capture recorded beside each of its frames. */
export type Take = {
  file: string;
  device: DeviceId;
  /** The app in its dark theme. */
  night: boolean;
  width: number;
  height: number;
  frames: number;
  /** Virtual milliseconds from one frame to the next: the capture's clock, not the video's. */
  frameMs: number;
  t: number[];
  clock: string[];
  statusBar: number[];
  navBar: number[];
  /** A finger on the screen: x, y and 1 while it is down, 0 in the frames after it lifts. */
  touch: ([number, number, number] | null)[];
  /** The run's live notification: its chronometer counts from [startT] on the take's clock ([t]). */
  live: { app: string; title: string; startT: number; steps: LiveStep[] };
  /** The share of the screen that changed since the frame before. */
  motion: number[];
  marks: Record<string, number>;
  streams: Stream[];
  peakPerSecond: number;
};

export const takes = raw as unknown as Record<TakeId, Take>;

export function mark(take: TakeId, name: string): number {
  const frame = takes[take].marks[name];
  if (frame === undefined) throw new Error(`The ${take} take has no mark "${name}"`);
  return frame;
}

export function stream(take: TakeId, key: string): Stream {
  const s = takes[take].streams.find((x) => x.key === key);
  if (!s) throw new Error(`The ${take} take streams no ${key}`);
  return s;
}

/** The live notification's step at frame [frame] of [take], or null while none is posted. */
export function liveAt(take: TakeId, frame: number): LiveStep | null {
  return takes[take].live.steps.find((s) => frame >= s.from && frame <= s.to) ?? null;
}

/** The step of [take]'s live notification that reads [text]. */
export function liveStep(take: TakeId, text: string): LiveStep {
  const s = takes[take].live.steps.find((x) => x.text === text);
  if (!s) throw new Error(`The ${take} take's live notification never reads "${text}"`);
  return s;
}

/** Screen pixels to a dp on the take's screen: the capture filmed each at its own density and scale. */
export const DP_WIDTH: Record<DeviceId, number> = { phone: 411, foldable: 791, tablet: 1280 };
export const pxPerDp = (take: TakeId) => takes[take].width / DP_WIDTH[takes[take].device];
