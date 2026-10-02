import raw from "../public/footage/takes.json";
import type { TakeId } from "./edit";

/** Text the scripted run streamed into a take: the frames from its first token landing to its last. */
export type Stream = { key: string; kind: string; from: number; to: number; tokens: number };

/** A take as `scripts/footage.mjs` wrote it: the MP4 and what the capture recorded beside each of its frames. */
export type Take = {
  file: string;
  width: number;
  height: number;
  frames: number;
  t: number[];
  screen: string[];
  clock: string[];
  /** The app's window, in the take's pixels from its top left. */
  content: [number, number][];
  statusBar: number[];
  navBar: number[];
  /** A finger on the screen: x, y and 1 while it is down, 0 in the frames after it lifts. */
  touch: ([number, number, number] | null)[];
  keys: (string[] | null)[];
  marks: Record<string, number>;
  streams: Stream[];
};

export const takes = raw as unknown as Record<TakeId, Take>;

export const lastFrame = (take: TakeId) => takes[take].frames - 1;
