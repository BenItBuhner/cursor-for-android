import type React from "react";
import { Composition, Folder } from "remotion";
import { Plate } from "./concept/Plate";
import { StyleframeCode, StyleframeLineup, StyleframeLive, StyleframeSay } from "./concept/Styleframes";
import { TypeSheet } from "./concept/TypeSheet";
import { takes } from "./takes";
import { FRAME } from "./camera";
import { Compare, COMPARE } from "./components/Compare";
import { check, DURATION, FPS, reelsOf, THEME } from "./edit";
import { Launch, type LaunchProps } from "./Launch";
import { DURATION as FILM_DURATION } from "./film/edit";
import { Film, FilmSampler } from "./film/Film";
import { Reel } from "./film/Reel";
import { REELS, type ReelId } from "./film/reels";

check([...reelsOf("dark"), ...reelsOf("light")]);

const DEVICES = ["phone", "foldable", "tablet"] as const;
/** The film's frames a review sheet samples by default: one from every shot. */
const SAMPLE = [120, 240, 420, 700, 830, 960, 1110, 1260, 1470, 1590, 1650, 1710, 1770, 1890];

const wide: LaunchProps = { framing: "wide", music: true, theme: THEME };
const tall: LaunchProps = { framing: "tall", music: true, theme: THEME };

export const Root: React.FC = () => (
  <>
    <Composition id="Launch" component={Launch} durationInFrames={DURATION} fps={FPS} {...FRAME.wide} defaultProps={wide} />
    <Composition id="LaunchVertical" component={Launch} durationInFrames={DURATION} fps={FPS} {...FRAME.tall} defaultProps={tall} />
    <Composition id="Compare" component={Compare} durationInFrames={COMPARE.frames} fps={FPS} {...FRAME.wide} />
    <Composition id="Film" component={Film} durationInFrames={FILM_DURATION} fps={FPS} {...FRAME.wide} defaultProps={{ framing: "wide" as const }} />
    <Composition id="FilmVertical" component={Film} durationInFrames={FILM_DURATION} fps={FPS} {...FRAME.tall} defaultProps={{ framing: "tall" as const }} />
    <Composition
      id="FilmSampler"
      component={FilmSampler}
      durationInFrames={SAMPLE.length}
      fps={FPS}
      {...FRAME.wide}
      defaultProps={{ framing: "wide" as const, frames: SAMPLE }}
      calculateMetadata={({ props }) => ({ durationInFrames: props.frames.length, ...FRAME[props.framing] })}
    />
    <Folder name="reels">
      {(Object.keys(REELS) as ReelId[]).map((id) => (
        <Composition
          key={id}
          id={`Reel-${id}`}
          component={Reel}
          durationInFrames={REELS[id].frames.length}
          fps={FPS}
          width={takes[REELS[id].take].width}
          height={takes[REELS[id].take].height}
          defaultProps={{ id }}
        />
      ))}
    </Folder>
    <Folder name="concept">
      <Composition id="SF1-Say" component={StyleframeSay} durationInFrames={2} fps={FPS} {...FRAME.wide} />
      <Composition id="SF2-Code" component={StyleframeCode} durationInFrames={2} fps={FPS} {...FRAME.wide} />
      <Composition id="SF3-Live" component={StyleframeLive} durationInFrames={2} fps={FPS} {...FRAME.tall} />
      <Composition id="SF4-Lineup" component={StyleframeLineup} durationInFrames={2} fps={FPS} {...FRAME.wide} />
      <Composition id="SF5-Type" component={TypeSheet} durationInFrames={1} fps={FPS} {...FRAME.wide} />
      {DEVICES.map((device) => (
        <Composition
          key={device}
          id={`Plate-${device}`}
          component={Plate}
          durationInFrames={1}
          fps={FPS}
          width={takes[device].width}
          height={takes[device].height}
          defaultProps={{ take: device, frame: 0, shade: false }}
        />
      ))}
    </Folder>
  </>
);
