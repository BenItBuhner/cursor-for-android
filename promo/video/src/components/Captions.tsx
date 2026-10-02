import type React from "react";
import { AbsoluteFill } from "remotion";
import type { Framing } from "../camera";
import { CAPTIONS, type Caption } from "../edit";
import { easeInOut, easeOut, progress } from "../math";
import { COLOR, SANS } from "../theme";

/** Frames between one word of a caption starting in and the next. */
const STAGGER = 2.5;
const WORD_IN = 18;
const OUT = 14;
/** The scrim outstays the words a little, so they never sit on the bare footage as they go. */
const SCRIM_OUT = 16;

const SCRIM = {
  side: "linear-gradient(90deg, rgba(8,7,6,0.92) 0%, rgba(8,7,6,0.8) 26%, rgba(8,7,6,0.62) 34%, rgba(8,7,6,0.38) 42%, rgba(8,7,6,0) 60%)",
  below: "linear-gradient(0deg, rgba(8,7,6,0.85) 0%, rgba(8,7,6,0.5) 20%, rgba(8,7,6,0) 42%)",
  top: "linear-gradient(180deg, rgba(8,7,6,0.85) 0%, rgba(8,7,6,0.55) 16%, rgba(8,7,6,0) 32%)",
};

type Look = {
  scrim: string;
  box: React.CSSProperties;
  align: "left" | "center";
  kicker: number;
  text: number;
};

function lookOf(c: Caption, framing: Framing): Look {
  if (framing === "tall") {
    return {
      scrim: SCRIM.top,
      box: { left: 90, right: 90, top: 182, alignItems: "center" },
      align: "center",
      kicker: 24,
      text: 64,
    };
  }
  if (c.place === "below") {
    return {
      scrim: SCRIM.below,
      box: { left: 410, right: 410, bottom: 150, alignItems: "center" },
      align: "center",
      kicker: 20,
      text: 50,
    };
  }
  return {
    scrim: SCRIM.side,
    box: { left: 144, width: 600, top: 0, bottom: 0, justifyContent: "center", paddingBottom: 60 },
    align: "left",
    kicker: 20,
    text: 56,
  };
}

/** The caption up at [t], if any: a kicker and a line, its words rising in one after another over a scrim. */
export const Captions: React.FC<{ t: number; framing: Framing }> = ({ t, framing }) => {
  const c = CAPTIONS.find((x) => t >= x.from - 2 && t < x.to + SCRIM_OUT);
  if (!c) return null;
  const look = lookOf(c, framing);
  const out = easeInOut(progress(t, c.to, c.to + OUT));
  const scrim = Math.min(easeOut(progress(t, c.from - 2, c.from + 16)), 1 - easeInOut(progress(t, c.to, c.to + SCRIM_OUT)));
  const kickerIn = easeOut(progress(t, c.from, c.from + 16));
  const words = c.text.split(" ");
  return (
    <AbsoluteFill>
      <AbsoluteFill style={{ background: look.scrim, opacity: scrim }} />
      <div
        style={{
          position: "absolute",
          display: "flex",
          flexDirection: "column",
          boxSizing: "border-box",
          fontFamily: SANS,
          textAlign: look.align,
          // A halo that only shows where the words sit over a screen's own text.
          textShadow: "0 0 36px rgba(8,7,6,0.95), 0 0 16px rgba(8,7,6,0.9), 0 2px 8px rgba(8,7,6,0.75)",
          opacity: 1 - out,
          transform: `translateY(${-14 * out}px)`,
          ...look.box,
        }}
      >
        <div
          style={{
            color: COLOR.accent,
            fontSize: look.kicker,
            fontWeight: 600,
            letterSpacing: "0.16em",
            textTransform: "uppercase",
            marginBottom: look.kicker * 0.8,
            opacity: kickerIn,
            transform: `translateY(${10 * (1 - kickerIn)}px)`,
          }}
        >
          {c.kicker}
        </div>
        <div
          style={{
            color: COLOR.text,
            fontSize: look.text,
            fontWeight: 600,
            lineHeight: 1.08,
            letterSpacing: "-0.02em",
            textWrap: "balance",
          }}
        >
          {words.map((word, i) => {
            const p = easeOut(progress(t, c.from + 4 + i * STAGGER, c.from + 4 + i * STAGGER + WORD_IN));
            return (
              <span
                key={i}
                style={{
                  display: "inline-block",
                  whiteSpace: "pre",
                  opacity: p,
                  transform: `translateY(${0.42 * (1 - p)}em)`,
                  filter: p < 1 ? `blur(${6 * (1 - p)}px)` : undefined,
                }}
              >
                {i < words.length - 1 ? `${word} ` : word}
              </span>
            );
          })}
        </div>
      </div>
    </AbsoluteFill>
  );
};
