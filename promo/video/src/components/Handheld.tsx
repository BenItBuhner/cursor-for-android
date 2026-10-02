import type React from "react";
import { Easing } from "remotion";
import { poseAt, type Framing } from "../camera";
import { FOLD, HANDHELD_SHOTS, INNER, sourceFrame, TABLET_SCREEN, TABLET, UNFOLD } from "../edit";
import { lerp, progress } from "../math";
import { growthAt, HingedFold, MM, Shadow, Slab } from "./Devices";
import { Stage } from "./Stage";

/** The hinge's swing: it gives a little at first, opens in one motion and settles flat. */
const swing = Easing.bezier(0.5, 0, 0.18, 1);

export const unfoldAt = (t: number) => swing(progress(t, UNFOLD.from, UNFOLD.to));

/** The Fold through the handheld half of the cut: shut on the cover screen, unfolding, and growing into the tablet. */
export const Handheld: React.FC<{ t: number; framing: Framing }> = ({ t, framing }) => {
  const pose = poseAt(HANDHELD_SHOTS, framing, t);
  const fold = unfoldAt(t);
  const large = sourceFrame("hero-large", t);
  const g = fold < 1 ? 0 : growthAt(large);
  // The shadow follows the body: under the shut phone's right of the hinge, then the open slab, then the tablet.
  const shadow =
    fold < 1
      ? { x: lerp(FOLD.halfWidth / 2, 0, fold), w: lerp(FOLD.halfWidth, 2 * FOLD.halfWidth, fold), h: FOLD.height }
      : { x: 0, w: lerp(INNER.w + 11, TABLET_SCREEN.w + 2 * TABLET.bezel.x, g), h: lerp(FOLD.height, TABLET_SCREEN.h + 2 * TABLET.bezel.y, g) };
  return (
    <Stage pose={pose} unit={MM}>
      <Shadow x={shadow.x} y={6} w={shadow.w} h={shadow.h} />
      {fold < 1 ? (
        <HingedFold turn={pose} fold={fold} cover={sourceFrame("hero-phone", t)} inner={large} />
      ) : (
        <Slab turn={pose} frame={large} />
      )}
    </Stage>
  );
};
