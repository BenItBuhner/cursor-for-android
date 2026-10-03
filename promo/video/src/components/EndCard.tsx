import type React from "react";
import type { Framing } from "../camera";
import { AT, DURATION } from "../edit";
import { clamp01, easeOut, progress } from "../math";
import { COLOR, SANS, TRACK } from "../theme";
import { AppIcon } from "./AppIcon";
import { Kinetic } from "./Headline";

export const REPO = "github.com/BenItBuhner/cursor-for-android";
export const DISCLAIMER = "Unofficial client for Cursor Cloud Agents. Not affiliated with Anysphere, Inc.";

/** How far the camera pushes in on the name by the last frame, slowing to a stop as the video ends. */
const PUSH = 0.05;
/** Frames the icon takes to settle to its size out of the dark, and the bloom behind it to come up and ease back. */
const SETTLE = 28;
const BLOOM = { up: 16, down: 70 };

/**
 * The close: out of the dark beat the lineup dips to, the icon settles from a little large to its size on the last hit
 * as a warm bloom comes up behind it and eases back; the name rides a weight wave in under it, where to get it rises
 * under that as the camera pushes slowly in, and the small print holds still under them.
 */
export const EndCard: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f, width, height }) => {
  const wide = framing === "wide";
  const icon = wide ? 168 : 196;
  const title = wide ? 112 : 92;
  const url = wide ? 36 : 34;
  const rise = (at: number, by: number) => {
    const p = easeOut(progress(f, at, at + 24));
    return { opacity: clamp01((f - at + 1) / 8), transform: `translateY(${(1 - p) * by}px)` };
  };
  const settled = easeOut(progress(f, AT.end, AT.end + SETTLE));
  const bloom = easeOut(progress(f, AT.end, AT.end + BLOOM.up)) * (1 - 0.55 * easeOut(progress(f, AT.end + BLOOM.up, AT.end + BLOOM.up + BLOOM.down)));
  const push = 1 + PUSH * Math.sin((progress(f, AT.end, DURATION - 1) * Math.PI) / 2);
  const bloomSize = icon * 7;
  return (
    <div style={{ position: "absolute", inset: 0 }}>
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          top: height * (wide ? 0.25 : 0.3),
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          transform: `scale(${push})`,
        }}
      >
        <div style={{ position: "relative" }}>
          <div
            style={{
              position: "absolute",
              left: icon / 2 - bloomSize / 2,
              top: icon / 2 - bloomSize / 2,
              width: bloomSize,
              height: bloomSize,
              borderRadius: "50%",
              background: `radial-gradient(circle, rgba(255,196,110,${(0.24 * bloom).toFixed(3)}) 0%, rgba(250,176,70,${(0.08 * bloom).toFixed(3)}) 28%, rgba(245,165,36,0) 60%)`,
            }}
          />
          <div style={{ position: "relative", transform: `scale(${1.22 - 0.22 * settled})`, opacity: clamp01((f - AT.end + 1) / 6) }}>
            <AppIcon size={icon} glow={0.6} />
          </div>
        </div>
        <div style={{ marginTop: icon * 0.26 }}>
          <Kinetic lines={["Cursor for Android"]} f={f} at={AT.end + 6} size={title} align="center" />
        </div>
        <div
          style={{
            marginTop: title * 0.34,
            fontFamily: SANS,
            fontWeight: 400,
            fontSize: url,
            letterSpacing: TRACK.text,
            color: COLOR.inkSoft,
            whiteSpace: "nowrap",
            ...rise(AT.end + 26, url * 0.6),
          }}
        >
          {REPO}
        </div>
      </div>
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          bottom: height * (wide ? 0.07 : 0.06),
          textAlign: "center",
          fontFamily: SANS,
          fontWeight: 400,
          fontSize: wide ? 22 : 24,
          lineHeight: 1.4,
          letterSpacing: TRACK.text,
          color: COLOR.inkSoft,
          padding: `0 ${width * 0.08}px`,
          ...rise(AT.end + 36, 10),
        }}
      >
        {DISCLAIMER}
      </div>
    </div>
  );
};
