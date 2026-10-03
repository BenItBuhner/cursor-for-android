import { Easing } from "remotion";

export const clamp01 = (x: number) => Math.max(0, Math.min(1, x));
export const lerp = (a: number, b: number, p: number) => a + (b - a) * p;

/** How far frame [t] is through [from, to), 0 before and 1 after. */
export const progress = (t: number, from: number, to: number) => (to <= from ? (t >= from ? 1 : 0) : clamp01((t - from) / (to - from)));

export const easeOut = Easing.bezier(0.16, 1, 0.3, 1);
export const easeIn = Easing.bezier(0.5, 0, 0.9, 0.4);
export const easeInOut = Easing.bezier(0.65, 0, 0.35, 1);
/** Rest to rest with the least jerk: no jolt as it starts or stops. */
export const easeSmooth = (t: number) => t * t * t * (10 + t * (6 * t - 15));
