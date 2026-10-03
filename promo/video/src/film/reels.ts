import { staticFile } from "remotion";
import type { DeviceId, TakeId } from "../takes";
import { LOCKED, lockedAt, phoneTake, SHOT } from "./edit";

/**
 * The screens the film puts on the devices' glass, rendered ahead as stills (scripts/reels.ts, Remotion's Reel
 * compositions) because a WebGL texture can't be a DOM overlay: each reel is the take frames one screen shows, in order,
 * with the system bars and the finger drawn over them, or, for the phone at night, its lock screen.
 */
export type ReelId = DeviceId | "phone-lock";
/**
 * A reel: its take, whether it's the lock screen, the take frames in order, the time its status bar or lock screen
 * shows at each (the film's day, not the capture's 9:41), and how long the run has been going before the take began.
 */
export type ReelSpec = { take: TakeId; lock: boolean; frames: number[]; clock: (take: number) => string; runFor: number };

/** The take frame the next morning begins at: the PR's details, a night after the run was started. */
const NEXT_MORNING = 1876;
const day = (take: number) => (take >= NEXT_MORNING ? "7:52" : "8:14");

function phoneFrames(lock: boolean): number[] {
  const out = new Set<number>(lock ? [] : [LOCKED]);
  for (let f = SHOT.wake.from; f < SHOT.ship.to; f++) if (lockedAt(f) === lock) out.add(phoneTake(f));
  return [...out].sort((a, b) => a - b);
}

export const REELS: Record<ReelId, ReelSpec> = {
  phone: { take: "phone", lock: false, frames: phoneFrames(false), clock: day, runFor: 0 },
  // Started at 8:14 and still going at 11:48 that night.
  "phone-lock": { take: "phone", lock: true, frames: phoneFrames(true), clock: () => "11:48", runFor: (15 * 60 + 34) * 60 },
  foldable: { take: "foldable", lock: false, frames: [LOCKED], clock: day, runFor: 0 },
  tablet: { take: "tablet", lock: false, frames: [LOCKED], clock: day, runFor: 0 },
};

/** What a reel's stills were rendered from, so a changed edit or clock renders them again. */
export const reelManifest = (id: ReelId) => {
  const r = REELS[id];
  return JSON.stringify({ take: r.take, lock: r.lock, frames: r.frames, clock: r.frames.map(r.clock), runFor: r.runFor });
};

/** The still of reel [id] showing take frame [take], or the nearest it has. */
export function reelSrc(id: ReelId, take: number): string {
  const frames = REELS[id].frames;
  let best = 0;
  for (let i = 0; i < frames.length; i++) if (Math.abs(frames[i]! - take) < Math.abs(frames[best]! - take)) best = i;
  return staticFile(`reel/${id}/${String(best).padStart(4, "0")}.jpg`);
}
