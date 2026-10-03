import type React from "react";
import { AbsoluteFill, Audio, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import type { Framing } from "./camera";
import { AndroidCard, CLOSE, OPEN } from "./components/AndroidCard";
import { EndCard } from "./components/EndCard";
import { Hero, HeroHeadlines } from "./components/Hero";
import { Lineup } from "./components/Lineup";
import { Stage, Wiped, wipeAt } from "./components/Stage";
import { TitleCard } from "./components/TitleCard";
import { AT } from "./edit";
import type { Theme } from "./takes";

export type LaunchProps = {
  framing: Framing;
  music: boolean;
  theme: Theme;
  /** The hero's headlines alone, white on black, for measuring their ink (camera.ts INK). */
  probe?: boolean;
};

/**
 * The cut, part by part on the beat sheet in edit.ts, over the stage and under the score, the app in [theme]: the
 * title card, which the green card opens out of; the green card, which closes into the hero's phone; the hero, which
 * a light crossing the stage carries into the lineup; and the end card, which the lineup dips to the stage into.
 */
export const Launch: React.FC<LaunchProps> = ({ framing, music, theme, probe = false }) => {
  const f = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const scene = { framing, f, width, height };
  if (probe) {
    return (
      <AbsoluteFill style={{ background: "#000", overflow: "hidden" }}>
        <HeroHeadlines framing={framing} f={f} height={height} />
      </AbsoluteFill>
    );
  }
  // A light crossing the stage carries each cut: the shot going out, run on past the cut, burns out under its beam, and
  // the one coming in, run on from before it, comes up in its wake.
  const wipe = wipeAt(f, width);
  const intoLineup = wipe?.at === AT.lineup;
  return (
    <AbsoluteFill style={{ overflow: "hidden" }}>
      <Stage {...scene} />
      {f < AT.android + OPEN ? <TitleCard {...scene} /> : null}
      {f >= CLOSE.from && (f < AT.lineup || intoLineup) ? (
        <Wiped mask={wipe?.out}>
          <Hero {...scene} theme={theme} shot={wipe ? wipe.at - 1 : f} />
        </Wiped>
      ) : null}
      {wipe && !intoLineup ? (
        <Wiped mask={wipe.in}>
          <Hero {...scene} theme={theme} shot={wipe.at} />
        </Wiped>
      ) : null}
      {f >= CLOSE.from && f < AT.lineup ? <HeroHeadlines framing={framing} f={f} height={height} /> : null}
      {f >= AT.android && f < CLOSE.until ? <AndroidCard {...scene} theme={theme} /> : null}
      {(f >= AT.lineup || intoLineup) && f < AT.end ? (
        <Wiped mask={wipe?.in}>
          <Lineup {...scene} theme={theme} />
        </Wiped>
      ) : null}
      {f >= AT.end ? <EndCard {...scene} /> : null}
      {music ? <Audio src={staticFile("audio/score.wav")} /> : null}
    </AbsoluteFill>
  );
};
