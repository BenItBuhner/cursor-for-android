import type React from "react";
import { cameraOn, HERO_PATHS, LENS, screenOf, TALL_TOP, type Framing } from "../camera";
import { HEROES, heroShade, MOMENTS, takeFrame } from "../edit";
import type { Theme } from "../takes";
import { Device } from "./Device";
import { Headline, LEADING } from "./Headline";
import { Screen } from "./Screen";
import { lightAt } from "./Stage";

/**
 * Where the hero's headline goes in each framing, and its size. A wide frame sets it at [left], centred down the frame
 * beside the phone; a tall one centres it across the frame, over the phone, in the room over the screen's resting top
 * ([TALL_TOP]) less [clear].
 */
const LAYOUT = {
  wide: { size: 150, left: 150 },
  tall: { size: 124, clear: 44 },
} as const;

/** The hero's headlines alone, each standing over its moment. */
export const HeroHeadlines: React.FC<{ framing: Framing; f: number; height: number }> = ({ framing, f, height }) => (
  <>
    {MOMENTS.map((moment) => {
      if (f < moment.at || f >= moment.until) return null;
      if (framing === "wide") {
        const { size, left } = LAYOUT.wide;
        const block = moment.wide.length * size * LEADING;
        return <Headline key={moment.at} lines={moment.wide} f={f} at={moment.at} until={moment.until} size={size} x={left} y={(height - block) / 2} />;
      }
      const { size, clear } = LAYOUT.tall;
      const block = moment.tall.length * size * LEADING;
      const room = TALL_TOP * height - clear;
      return (
        <Headline key={moment.at} lines={moment.tall} f={f} at={moment.at} until={moment.until} size={size} x={0} y={(room - block) / 2} align="center" />
      );
    })}
  </>
);

/**
 * The hero's phone at frame [f], as the shot that holds frame [shot] has it: while a light carries a cut across the
 * frame, the shot going out runs on a few frames past it, and the one coming in a few frames before it, each with its
 * camera coasting on past its end ([cameraOn]). Its headlines are [HeroHeadlines].
 */
export const Hero: React.FC<{ framing: Framing; theme: Theme; f: number; shot?: number; width: number; height: number }> = ({
  framing,
  theme,
  f,
  shot = f,
  width,
  height,
}) => {
  const reel = HEROES[theme];
  const cam = cameraOn(HERO_PATHS[theme][framing], shot, f);
  const screen = screenOf(framing, reel.take, cam);
  const origin = `${cam.x * width}px ${cam.y * height}px`;
  return (
    <>
      <div style={{ position: "absolute", inset: 0, perspective: LENS[framing].perspective, perspectiveOrigin: origin }}>
        <div
          style={{
            position: "absolute",
            inset: 0,
            transformOrigin: origin,
            transform: `rotateX(${cam.tilt}deg) rotateY(${cam.turn}deg) rotateZ(${cam.roll}deg)`,
          }}
        >
          <Device take={reel.take} x={screen.left} y={screen.top} width={screen.width} light={lightAt(f, width, height)}>
            <Screen take={reel.take} frame={takeFrame(reel, f, shot)} width={screen.width} shade={heroShade(shot, f)} />
          </Device>
        </div>
      </div>
    </>
  );
};
