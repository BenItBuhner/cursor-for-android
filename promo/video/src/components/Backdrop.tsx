import type React from "react";
import { AbsoluteFill } from "remotion";
import { poseAt, type Framing } from "../camera";
import { b, DESK, DESK_SHOTS, END, HANDHELD, HANDHELD_SHOTS } from "../edit";
import { easeInOut, lerp, progress } from "../math";

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

/** Where the device stands in the frame at [t], kept to the frame's middle. */
function anchorAt(t: number, framing: Framing): [number, number] {
  if (t < HANDHELD.from) return [0.5, 0.45];
  if (t >= END.from) return [0.5, 0.5];
  const hand = poseAt(HANDHELD_SHOTS, framing, t);
  const desk = poseAt(DESK_SHOTS, framing, t);
  const w = easeInOut(progress(t, DESK.from, DESK.from + b(1)));
  return [clamp(lerp(hand.ax, desk.ax, w), 0.3, 0.7), clamp(lerp(hand.ay, desk.ay, w), 0.38, 0.62)];
}

/** Frames the glow trails the device by, so that it drifts after it and never jumps on a cut. */
const TRAIL = 24;

/** The room: a warm glow behind the device, a cool one opposite, and the corners falling away. */
export const Backdrop: React.FC<{ t: number; framing: Framing }> = ({ t, framing }) => {
  let x = 0;
  let y = 0;
  for (let i = 0; i < TRAIL; i++) {
    const [ax, ay] = anchorAt(t - i, framing);
    x += ax / TRAIL;
    y += ay / TRAIL;
  }
  return (
    <AbsoluteFill>
      <AbsoluteFill
        style={{
          background: `radial-gradient(ellipse 58% 62% at ${x * 100}% ${y * 100}%, rgba(233,184,114,0.105) 0%, rgba(233,184,114,0.045) 38%, rgba(233,184,114,0) 72%)`,
        }}
      />
      <AbsoluteFill
        style={{
          background: `radial-gradient(ellipse 55% 48% at ${(1.08 - x) * 100}% ${(0.9 - y * 0.8) * 100}%, rgba(118,146,206,0.05) 0%, rgba(118,146,206,0) 65%)`,
        }}
      />
      <AbsoluteFill
        style={{ background: "radial-gradient(ellipse 88% 82% at 50% 50%, rgba(0,0,0,0) 52%, rgba(0,0,0,0.58) 100%)" }}
      />
    </AbsoluteFill>
  );
};
