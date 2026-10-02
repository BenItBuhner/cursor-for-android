import { Easing } from "remotion";
import type { Ease, Pose, Shot } from "./edit";

export type Framing = "wide" | "tall";

const EASE: Record<Ease, (p: number) => number> = {
  drift: Easing.inOut(Easing.sin),
  swift: Easing.bezier(0.7, 0, 0.25, 1),
  out: Easing.bezier(0.16, 1, 0.3, 1),
};

const lerp = (a: number, b: number, p: number) => a + (b - a) * p;

/** The camera at frame [t]: the shot's two poses eased between, the first shot's before it and the last one's after. */
export function poseAt(shots: Shot[], framing: Framing, t: number): Pose {
  const first = shots[0];
  const last = shots[shots.length - 1];
  if (!first || !last) throw new Error("No shots");
  if (t < first.from) return first[framing][0];
  if (t >= last.to) return last[framing][1];
  const s = shots.find((x) => t >= x.from && t < x.to) ?? last;
  const [a, b] = s[framing];
  const p = EASE[s.ease]((t - s.from) / (s.to - s.from));
  return {
    at: [lerp(a.at[0], b.at[0], p), lerp(a.at[1], b.at[1], p)],
    // Zoom moves evenly to the eye in its logarithm.
    k: a.k * Math.pow(b.k / a.k, p),
    rx: lerp(a.rx, b.rx, p),
    ry: lerp(a.ry, b.ry, p),
    rz: lerp(a.rz, b.rz, p),
    ax: lerp(a.ax, b.ax, p),
    ay: lerp(a.ay, b.ay, p),
  };
}

/**
 * The world's transform for [pose], its device laid out [unit] DOM pixels to the unit around its origin: the point looked
 * at is brought to the origin, turned about, scaled to the frame and set where the frame wants it.
 */
export function worldTransform(pose: Pose, unit: number, width: number, height: number): string {
  const s = pose.k / unit;
  return [
    `translate3d(${pose.ax * width}px, ${pose.ay * height}px, 0)`,
    `scale3d(${s}, ${s}, ${s})`,
    `rotateX(${pose.rx}deg)`,
    `rotateY(${pose.ry}deg)`,
    `rotateZ(${pose.rz}deg)`,
    `translate3d(${-pose.at[0] * unit}px, ${-pose.at[1] * unit}px, 0)`,
  ].join(" ");
}
