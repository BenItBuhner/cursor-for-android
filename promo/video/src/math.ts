import { Easing } from "remotion";

export const clamp01 = (x: number) => Math.max(0, Math.min(1, x));
export const lerp = (a: number, b: number, p: number) => a + (b - a) * p;

/** How far frame [t] is through [from, to), 0 before and 1 after. */
export const progress = (t: number, from: number, to: number) => clamp01((t - from) / (to - from));

export const easeOut = Easing.bezier(0.16, 1, 0.3, 1);
export const easeInOut = Easing.bezier(0.65, 0, 0.35, 1);
export const smooth = (p: number) => p * p * (3 - 2 * p);

/** 1 over [inFrom, inTo), held, and back to 0 over [outFrom, outTo): a fade in and out. */
export function envelope(t: number, inFrom: number, inTo: number, outFrom: number, outTo: number): number {
  return Math.min(easeOut(progress(t, inFrom, inTo)), 1 - easeInOut(progress(t, outFrom, outTo)));
}
