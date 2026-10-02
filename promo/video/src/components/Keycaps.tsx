import type React from "react";
import type { Framing } from "../camera";
import { DESK, sourceFrame } from "../edit";
import { easeInOut, easeOut, progress } from "../math";
import { takes } from "../takes";
import { COLOR, SANS } from "../theme";

/** A chord typed in the desktop take, in the video's frames: its keys in the order they went down, and what was held when. */
type Chord = { from: number; to: number; keys: string[]; downAt: number[]; held: string[][] };

/** Frames a chord stays up after its last key lifts, so it can be read, and the frames it then takes to go. */
const LATCH = 30;
const FADE = 12;
const POP = 6;

let chords: Chord[] | null = null;

/** The desktop take's held keys over the desk's frames, split where nothing is held. */
function chordsOf(): Chord[] {
  if (chords) return chords;
  const out: Chord[] = [];
  let open: Chord | null = null;
  for (let t = DESK.from; t < DESK.to; t++) {
    const keys = takes.desktop.keys[sourceFrame("desktop", t)] ?? null;
    if (!keys || keys.length === 0) {
      open = null;
      continue;
    }
    if (!open) {
      open = { from: t, to: t, keys: [], downAt: [], held: [] };
      out.push(open);
    }
    for (const k of keys) {
      if (!open.keys.includes(k)) {
        open.keys.push(k);
        open.downAt.push(t);
      }
    }
    open.held.push(keys);
    open.to = t + 1;
  }
  chords = out;
  return out;
}

/** The latest chord still up at [t]. */
function chordAt(t: number): Chord | undefined {
  let at: Chord | undefined;
  for (const c of chordsOf()) if (t >= c.from && t < c.to + LATCH + FADE) at = c;
  return at;
}

/** The keys pressed in desktop mode at [t], as keycaps under the picture: the take has no keyboard of its own to show. */
export const Keycaps: React.FC<{ t: number; framing: Framing }> = ({ t, framing }) => {
  const chord = chordAt(t);
  if (!chord) return null;
  const tall = framing === "tall";
  const size = tall ? 34 : 30;
  const held = t < chord.to ? chord.held[t - chord.from] ?? [] : [];
  const gone = easeInOut(progress(t, chord.to + LATCH, chord.to + LATCH + FADE));
  return (
    <div
      style={{
        position: "absolute",
        left: 0,
        right: 0,
        bottom: tall ? 154 : 64,
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
        gap: size * 0.4,
        fontFamily: SANS,
        opacity: 1 - gone,
        transform: `translateY(${8 * gone}px)`,
      }}
    >
      {chord.keys.map((key, i) => {
        const pop = easeOut(progress(t, chord.downAt[i] ?? chord.from, (chord.downAt[i] ?? chord.from) + POP));
        if (pop <= 0) return null;
        const down = held.includes(key);
        return (
          <div key={key} style={{ display: "flex", alignItems: "center", gap: size * 0.4, opacity: pop }}>
            {i > 0 ? <span style={{ color: COLOR.dim, fontSize: size * 0.8, fontWeight: 500 }}>+</span> : null}
            <div
              style={{
                minWidth: size * 1.9,
                boxSizing: "border-box",
                padding: `${size * 0.3}px ${size * 0.55}px`,
                borderRadius: size * 0.36,
                background: "rgba(20,19,17,0.85)",
                border: `${Math.max(1.5, size * 0.05)}px solid ${down ? COLOR.accent : "rgba(255,255,255,0.16)"}`,
                boxShadow: down
                  ? "0 1px 0 rgba(0,0,0,0.6), 0 6px 16px rgba(0,0,0,0.3)"
                  : "0 3px 0 rgba(0,0,0,0.6), 0 10px 24px rgba(0,0,0,0.35)",
                color: COLOR.text,
                fontSize: size,
                fontWeight: 600,
                lineHeight: 1.15,
                textAlign: "center",
                transform: `translateY(${down ? 2 : 0}px) scale(${0.86 + 0.14 * pop})`,
              }}
            >
              {key}
            </div>
          </div>
        );
      })}
    </div>
  );
};
