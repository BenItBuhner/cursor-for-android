import type React from "react";
import { cameraAt, HERO_PATHS, screenOf, type Framing } from "../camera";
import { AT, HEROES } from "../edit";
import { easeInOut, progress } from "../math";
import type { Theme } from "../takes";
import { COLOR } from "../theme";
import { Headline, LEADING } from "./Headline";
import { iconCentre } from "./TitleCard";

/** How far the camera pushes in on the news, at an even pace from the open to the close. */
const PUSH = 0.09;
/** Frames the green takes to open from the icon's footprint to the whole frame: slow off the icon, fast to the edges. */
export const OPEN = 22;
/**
 * The close: the frame the green starts closing into the phone, a little ahead of the hero's beat so the disc is well
 * in by the hit, and the frame it is gone.
 */
export const CLOSE = { from: AT.organize - 4, until: AT.organize + 12 };

/**
 * The news, full bleed in Android's green: it opens out of the title card's icon, a disc growing from where the icon
 * stands until it fills the frame, with the headline rising into it as it does; the headline wipes out into the beat,
 * and the green closes over the hero's first frames into a disc that shrinks away into the phone as it rises in, fast
 * at first and slowing as it goes, so the green hands the frame to the phone rather than cutting.
 */
export const AndroidCard: React.FC<{ framing: Framing; theme: Theme; f: number; width: number; height: number }> = ({
  framing,
  theme,
  f,
  width,
  height,
}) => {
  const size = framing === "wide" ? 216 : 184;
  const lines = framing === "wide" ? ["Now on Android."] : ["Now on", "Android."];
  const open = easeInOut(progress(f, AT.android, AT.android + OPEN));
  const close = progress(f, CLOSE.from, CLOSE.until);
  let clipPath: string | undefined;
  if (open < 1) {
    const [cx, cy] = iconCentre(framing, AT.android, width);
    const reach = Math.hypot(Math.max(cx, 1 - cx) * width, Math.max(cy, 1 - cy) * height);
    clipPath = `circle(${(reach * open).toFixed(1)}px at ${(cx * 100).toFixed(2)}% ${(cy * 100).toFixed(2)}%)`;
  } else if (close > 0) {
    // Into the middle of what shows of the phone's screen, or the bottom edge while it is still under the frame.
    const screen = screenOf(framing, HEROES[theme].take, cameraAt(HERO_PATHS[theme][framing], f));
    const cx = Math.min(Math.max(screen.left + screen.width / 2, width * 0.04), width * 0.96);
    const top = Math.max(screen.top, 0);
    const bottom = Math.min(screen.top + screen.height, height);
    const cy = top < bottom ? (top + bottom) / 2 : height * 0.96;
    const reach = Math.hypot(Math.max(cx, width - cx), Math.max(cy, height - cy));
    clipPath = `circle(${(reach * (1 - close) ** 2).toFixed(1)}px at ${cx.toFixed(1)}px ${cy.toFixed(1)}px)`;
  }
  return (
    <div style={{ position: "absolute", inset: 0, background: COLOR.android, clipPath }}>
      <div style={{ position: "absolute", inset: 0, transform: `scale(${1 + PUSH * progress(f, AT.android, CLOSE.until)})` }}>
        <Headline
          lines={lines}
          f={f}
          at={AT.android + 6}
          until={CLOSE.from + 2}
          size={size}
          x={0}
          y={(height - lines.length * size * LEADING) / 2}
          align="center"
          color={COLOR.onAndroid}
        />
      </div>
    </div>
  );
};
