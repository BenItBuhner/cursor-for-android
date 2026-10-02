import type React from "react";
import { AbsoluteFill, spring } from "remotion";
import type { Framing } from "../camera";
import { FPS } from "../edit";
import { easeOut, progress } from "../math";
import { COLOR, SANS } from "../theme";
import { AppIcon } from "./AppIcon";

const WORDS = ["Cursor", "for", "Android"];

/**
 * The title card: the app's icon, its name and what it is. The phone's entrance eases out as hard as this does, so
 * the title lifts clear and dissolves as the camera tilts down to it, gone before the phone's top edge reaches its words.
 */
export const Title: React.FC<{ t: number; framing: Framing }> = ({ t, framing }) => {
  const tall = framing === "tall";
  const icon = spring({ frame: t - 6, fps: FPS, durationInFrames: 26, config: { damping: 13, stiffness: 120, mass: 0.9 } });
  const sub = easeOut(progress(t, 30, 52));
  const lift = easeOut(progress(t, 94, 140));
  const gone = easeOut(progress(t, 96, 118));
  return (
    <AbsoluteFill
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        paddingBottom: tall ? 120 : 60,
        fontFamily: SANS,
        opacity: 1 - gone,
        transform: `translateY(${-(tall ? 520 : 300) * lift}px) scale(${1 - 0.06 * lift})`,
        filter: gone > 0 ? `blur(${12 * gone}px)` : undefined,
      }}
    >
      <div
        style={{
          opacity: Math.min(1, icon * 1.6),
          transform: `translateY(${24 * (1 - icon)}px) scale(${0.72 + 0.28 * icon})`,
          marginBottom: tall ? 46 : 40,
        }}
      >
        <AppIcon size={tall ? 150 : 132} glow={1} />
      </div>
      <div
        style={{
          color: COLOR.text,
          fontSize: tall ? 88 : 104,
          fontWeight: 700,
          letterSpacing: "-0.035em",
          lineHeight: 1.04,
          whiteSpace: "nowrap",
        }}
      >
        {WORDS.map((word, i) => {
          const p = easeOut(progress(t, 14 + i * 5, 14 + i * 5 + 24));
          return (
            <span
              key={word}
              style={{
                display: "inline-block",
                whiteSpace: "pre",
                opacity: p,
                transform: `translateY(${0.34 * (1 - p)}em)`,
                filter: p < 1 ? `blur(${10 * (1 - p)}px)` : undefined,
              }}
            >
              {i < WORDS.length - 1 ? `${word} ` : word}
            </span>
          );
        })}
      </div>
      <div
        style={{
          marginTop: tall ? 26 : 22,
          maxWidth: tall ? 820 : undefined,
          color: COLOR.dim,
          fontSize: tall ? 36 : 32,
          fontWeight: 500,
          lineHeight: 1.3,
          letterSpacing: "-0.005em",
          textAlign: "center",
          textWrap: "balance",
          opacity: sub,
          transform: `translateY(${14 * (1 - sub)}px)`,
        }}
      >
        Unofficial Android client for Cursor Cloud Agents
      </div>
    </AbsoluteFill>
  );
};
