import type React from "react";
import { AbsoluteFill, useCurrentFrame, useVideoConfig } from "remotion";
import { shadeAt, type ShadeMoves } from "../edit";
import { takeOf, type Theme } from "../takes";
import { COLOR, SANS, TRACK } from "../theme";
import { Device } from "./Device";
import { Screen } from "./Screen";

/**
 * The app's two themes side by side on the cut's stage, for choosing the one it is cut in: both phones' takes from
 * frame [from] for [frames], the edits landing and the first diff opened and folded, a follow-up typed and queued, and
 * the notification shade pulled down over the run. Its still is frame [still], the diff open.
 */
export const COMPARE = { from: 640, frames: 420, still: 100 };
const MOVES: ShadeMoves = { pull: { at: 300, frames: 24 } };

const THEMES: { theme: Theme; label: string; x: number }[] = [
  { theme: "dark", label: "Dark", x: 0.37 },
  { theme: "light", label: "Light", x: 0.63 },
];

/** Each phone's screen width, and where its label and its screen's top stand. */
const PHONE = 380;
const LABEL_TOP = 52;
const SCREEN_TOP = 160;

export const Compare: React.FC = () => {
  const f = useCurrentFrame();
  const { width } = useVideoConfig();
  const shade = shadeAt(f, MOVES);
  return (
    <AbsoluteFill style={{ background: COLOR.stage }}>
      {THEMES.map(({ theme, label, x }) => {
        const take = takeOf("phone", theme);
        const cx = x * width;
        return (
          <div key={theme} style={{ position: "absolute", inset: 0 }}>
            <div
              style={{
                position: "absolute",
                left: cx - 300,
                width: 600,
                top: LABEL_TOP,
                textAlign: "center",
                fontFamily: SANS,
                fontWeight: 500,
                fontSize: 48,
                lineHeight: 1.2,
                letterSpacing: TRACK.display,
                color: COLOR.ink,
              }}
            >
              {label}
            </div>
            <Device take={take} x={cx - PHONE / 2} y={SCREEN_TOP} width={PHONE}>
              <Screen take={take} frame={COMPARE.from + f} width={PHONE} shade={shade} />
            </Device>
          </div>
        );
      })}
    </AbsoluteFill>
  );
};
