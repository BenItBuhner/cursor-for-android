import type React from "react";
import { Composition } from "remotion";
import { check, DURATION, FPS } from "./edit";
import { Launch, type LaunchProps } from "./Launch";
import { takes } from "./takes";

check(takes);

const wide: LaunchProps = { framing: "wide", music: true };
const tall: LaunchProps = { framing: "tall", music: true };

export const Root: React.FC = () => (
  <>
    <Composition id="Launch" component={Launch} durationInFrames={DURATION} fps={FPS} width={1920} height={1080} defaultProps={wide} />
    <Composition
      id="LaunchVertical"
      component={Launch}
      durationInFrames={DURATION}
      fps={FPS}
      width={1080}
      height={1920}
      defaultProps={tall}
    />
  </>
);
