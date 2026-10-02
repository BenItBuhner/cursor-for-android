import type React from "react";
import { AbsoluteFill, useVideoConfig } from "remotion";
import { worldTransform } from "../camera";
import type { Pose } from "../edit";

/** A lens a little longer than normal: the devices keep their shape, their turns still read as depth. */
const PERSPECTIVE = 2600;

/** A device in the room, seen through the camera at [pose]; [unit] DOM pixels to the device's unit. */
export const Stage: React.FC<{ pose: Pose; unit: number; opacity?: number; children: React.ReactNode }> = ({
  pose,
  unit,
  opacity = 1,
  children,
}) => {
  const { width, height } = useVideoConfig();
  return (
    <AbsoluteFill
      style={{
        perspective: PERSPECTIVE,
        perspectiveOrigin: `${pose.ax * width}px ${pose.ay * height}px`,
        opacity,
      }}
    >
      <div
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          transformStyle: "preserve-3d",
          transform: worldTransform(pose, unit, width, height),
        }}
      >
        {children}
      </div>
    </AbsoluteFill>
  );
};
