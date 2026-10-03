import type React from "react";
import { useLayoutEffect, useRef, useState } from "react";
import { AbsoluteFill } from "remotion";
import { ROLE, typeStyle } from "./type";

const PAPER = "#ECE8E1";
const INK = "#191612";
const QUIET = "#7d776d";

const Caption: React.FC<{ children: React.ReactNode; color?: string }> = ({ children, color = QUIET }) => (
  <div style={{ color, ...typeStyle(ROLE.caption, 20) }}>{children}</div>
);

/** One rung of the size ladder: [text] at [size] px, its optical size following, labelled. */
const Rung: React.FC<{ size: number; text: string }> = ({ size, text }) => (
  <div style={{ display: "flex", alignItems: "baseline", gap: 28 }}>
    <div style={{ width: 190, flex: "none" }}>
      <Caption>
        {size} px · opsz {Math.max(8, Math.min(144, size))}
      </Caption>
    </div>
    <div style={{ color: INK, whiteSpace: "nowrap", ...typeStyle(ROLE.display, size) }}>{text}</div>
  </div>
);

/**
 * The tap's grade pulse, frame by frame: the same word at each step, left-aligned, with a rule at the right edge of the
 * first so it shows the line never grows. GRAD adds weight without width; the press reads, the layout holds.
 */
const Pulse: React.FC = () => {
  const steps = [0, 55, 100, 70, 30, 0];
  const first = useRef<HTMLDivElement>(null);
  const [edge, setEdge] = useState<number | null>(null);
  useLayoutEffect(() => setEdge(first.current?.getBoundingClientRect().width ?? null), []);
  return (
    <div style={{ position: "relative", display: "flex", flexDirection: "column", gap: 6 }}>
      {steps.map((grade, i) => (
        <div key={i} style={{ display: "flex", alignItems: "baseline", gap: 28 }}>
          <div style={{ width: 190, flex: "none" }}>
            <Caption>
              f{i * 2} · GRAD {grade}
            </Caption>
          </div>
          <div ref={i === 0 ? first : undefined} style={{ color: INK, whiteSpace: "nowrap", ...typeStyle(ROLE.display, 64, grade) }}>
            Ship it.
          </div>
        </div>
      ))}
      {edge === null ? null : <div style={{ position: "absolute", top: -8, bottom: -8, left: 218 + edge, width: 1.5, background: "#c2462e" }} />}
    </div>
  );
};

/** The concept's type system on one sheet: the face, the size ladder with optical size, the grade pulse, the rules. */
export const TypeSheet: React.FC = () => (
  <AbsoluteFill style={{ background: PAPER, padding: "84px 112px", display: "flex", flexDirection: "column", gap: 56 }}>
    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
      <div style={{ color: INK, ...typeStyle(ROLE.display, 40) }}>Type system — Roboto Flex</div>
      <Caption>Display wght 600 · wdth 100 · tracking 0 → −0.012 em by size · Caption wght 450</Caption>
    </div>
    <div style={{ display: "flex", gap: 120 }}>
      <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
        <Caption color={INK}>Optical size follows the size on screen, every frame, through camera pushes</Caption>
        <Rung size={144} text="Say it." />
        <Rung size={72} text="Watch it code." />
        <Rung size={36} text="Steer it while it works." />
        <Rung size={18} text="Running npm test · 00:15 · Stop" />
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
        <Caption color={INK}>The only moving axis: grade, on a tap, 12 frames</Caption>
        <Pulse />
      </div>
    </div>
    <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 40, marginTop: "auto" }}>
      {[
        ["One weight per role", "Weight never animates. No ramp through the mushy middle weights; a word is set, not grown."],
        ["Size drives optics", "opsz = rendered px, clamped 8–144. Big type tightens and gains contrast; small type opens up."],
        ["Grade for touch", "A press adds GRAD 0→100→0 on the tap's spring. Weight without width, so nothing reflows."],
        ["Cut, don't wipe", "Words hard-cut in on the beat and hold 0.6–1.2 s. No per-glyph waves, blurs or tracking sweeps."],
      ].map(([head, body]) => (
        <div key={head} style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          <div style={{ color: INK, ...typeStyle(ROLE.display, 26) }}>{head}</div>
          <div style={{ color: QUIET, ...typeStyle(ROLE.caption, 19) }}>{body}</div>
        </div>
      ))}
    </div>
  </AbsoluteFill>
);
