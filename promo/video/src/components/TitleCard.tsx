import type React from "react";
import type { Framing } from "../camera";
import { AT } from "../edit";
import { clamp01, easeOut, progress } from "../math";
import { textWidth } from "../measure";
import { COLOR } from "../theme";
import { AppIcon } from "./AppIcon";
import { Kinetic } from "./Headline";

/** How far the camera pushes in on the open, at an even pace from its first frame to the cut. */
const PUSH = 0.07;

/** The icon's size and the wordmark's, and the gap between them, in each framing. */
const SIZES: Record<Framing, { icon: number; size: number }> = {
  wide: { icon: 232, size: 210 },
  tall: { icon: 184, size: 164 },
};

/** Frames the icon takes to land, and the wordmark to slide out from behind it. */
const LAND = 18;
const SLIDE = 26;

/**
 * Where the icon's centre stands in the frame at [f] (fractions of the frame), once laid out as [TitleCard] has it: in
 * the middle until the wordmark slides out, then at the left of the row the two make.
 */
export function iconCentre(framing: Framing, f: number, width: number): [number, number] {
  const { icon, size } = SIZES[framing];
  const gap = icon * 0.24;
  const slide = easeOut(progress(f, AT.wordmark, AT.wordmark + SLIDE));
  const push = 1 + PUSH * progress(f, AT.title, AT.android);
  const row = icon + gap + textWidth("Cursor", size);
  const atRest = (width - row) / 2 + icon / 2;
  const x = width / 2 + (atRest - width / 2) * slide;
  return [0.5 + ((x - width / 2) * push) / width, 0.5];
}

/**
 * The open: the app's icon lands in the middle of the frame, then slides aside for the wordmark, which comes out from
 * behind it on the next beat and, as it clears the icon, has a weight wave run through it. Icon and wordmark stand in
 * one row, laid out by the face's own widths and centred on the frame, and the row is held over to the right until
 * the slide by as much as puts the icon alone in the middle. The wordmark's box starts under the icon's middle, so the
 * word slides out from behind it.
 */
export const TitleCard: React.FC<{ framing: Framing; f: number; width: number; height: number }> = ({ framing, f }) => {
  const { icon, size } = SIZES[framing];
  const gap = icon * 0.24;
  const landed = easeOut(progress(f, AT.title, AT.title + LAND));
  const slide = easeOut(progress(f, AT.wordmark, AT.wordmark + SLIDE));
  const push = 1 + PUSH * progress(f, AT.title, AT.android);
  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        transform: `scale(${push})`,
      }}
    >
      <div style={{ display: "flex", alignItems: "center", transform: `translateX(calc((50% - ${icon / 2}px) * ${1 - slide}))` }}>
        <div style={{ position: "relative", zIndex: 1, transform: `scale(${0.82 + 0.18 * landed})`, opacity: clamp01((f - AT.title + 1) / 5) }}>
          <AppIcon size={icon} glow={0.5} />
        </div>
        <div style={{ marginLeft: -icon / 2, overflow: "hidden" }}>
          <div style={{ paddingLeft: icon / 2 + gap, transform: `translateX(${(slide - 1) * 100}%)`, lineHeight: 1.2 }}>
            <Kinetic
              lines={["Cursor"]}
              f={f}
              at={AT.title - 40}
              size={size}
              color={COLOR.ink}
              waves={[{ at: AT.wordmark + 10, frames: 48, crest: 700 }]}
            />
          </div>
        </div>
      </div>
    </div>
  );
};
