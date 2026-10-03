import type React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { Screen } from "../components/Screen";
import { takes } from "../takes";
import { REELS, type ReelId } from "./reels";

/** Frame [i] of reel [id]: its take at the [i]th frame the film needs of it, at the take's own pixel size. */
export const Reel: React.FC<{ id: ReelId }> = ({ id }) => {
  const i = useCurrentFrame();
  const reel = REELS[id];
  return (
    <AbsoluteFill>
      <Screen
        take={reel.take}
        frame={reel.frames[i] ?? 0}
        width={takes[reel.take].width}
        lock={reel.lock}
        clock={reel.clock(reel.frames[i] ?? 0)}
        runFor={reel.runFor}
      />
    </AbsoluteFill>
  );
};
