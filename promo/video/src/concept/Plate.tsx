import type React from "react";
import { AbsoluteFill } from "remotion";
import { Screen } from "../components/Screen";
import { takes, type TakeId } from "../takes";

export type PlateProps = { take: TakeId; frame: number; shade: boolean };

/**
 * A take's screen at frame [frame] at its own pixel size, with the system bars, and the notification shade all the way
 * down given [shade]: a texture for the concept's styleframes to put on a device's glass.
 */
export const Plate: React.FC<PlateProps> = ({ take, frame, shade }) => (
  <AbsoluteFill>
    <Screen take={take} frame={frame} width={takes[take].width} shade={shade ? { open: 1, finger: null } : undefined} />
  </AbsoluteFill>
);
