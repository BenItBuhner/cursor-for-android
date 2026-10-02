import type React from "react";
import { AbsoluteFill, Audio, staticFile, useCurrentFrame } from "remotion";
import type { Framing } from "./camera";
import { Backdrop } from "./components/Backdrop";
import { Captions } from "./components/Captions";
import { Desk } from "./components/Desk";
import { EndCard } from "./components/EndCard";
import { Handheld } from "./components/Handheld";
import { Keycaps } from "./components/Keycaps";
import { Title } from "./components/Title";
import { DESK, DURATION, END, HANDHELD, WHIP } from "./edit";
import { easeInOut, progress } from "./math";
import { COLOR } from "./theme";

export type LaunchProps = { framing: Framing; music: boolean };

/** Frames the title card is up for, lifting away as the phone comes in. */
const TITLE_TO = 150;

/** The room and what stands in it at [t], which may fall between frames: everything the camera films. */
const Scene: React.FC<{ t: number; framing: Framing }> = ({ t, framing }) => (
  <AbsoluteFill style={{ background: COLOR.room }}>
    <Backdrop t={t} framing={framing} />
    {t < TITLE_TO ? <Title t={t} framing={framing} /> : null}
    {t >= HANDHELD.from && t < HANDHELD.to ? <Handheld t={t} framing={framing} /> : null}
    {t >= DESK.from && t < DESK.to ? (
      <Desk t={t} framing={framing} opacity={1 - easeInOut(progress(t, END.from, END.from + 30))} />
    ) : null}
    {t >= END.from ? <EndCard t={t} framing={framing} /> : null}
  </AbsoluteFill>
);

/** The moves too swift for a frame to hold still: the phone's first rush up and the pan to the monitor. */
const SHUTTERED = [
  { from: HANDHELD.from, to: HANDHELD.from + 24, looks: 6 },
  { from: WHIP.from, to: WHIP.to, looks: 8 },
];
/** A 180 degree shutter: each frame sees the half of a frame around it. */
const SHUTTER = 0.5;

/**
 * The scene as the camera exposes it: in a swift move, looks through the open shutter averaged. Each look is opaque,
 * room and all, and laid over the ones before at one over its count, so that every look weighs the same.
 */
const Exposed: React.FC<{ t: number; framing: Framing }> = ({ t, framing }) => {
  const move = SHUTTERED.find((m) => t >= m.from && t < m.to);
  if (!move) return <Scene t={t} framing={framing} />;
  return (
    <>
      {Array.from({ length: move.looks }, (_, i) => (
        <AbsoluteFill key={i} style={{ opacity: 1 / (i + 1) }}>
          <Scene t={t + SHUTTER * (i / (move.looks - 1) - 0.5)} framing={framing} />
        </AbsoluteFill>
      ))}
    </>
  );
};

export const Launch: React.FC<LaunchProps> = ({ framing, music }) => {
  const t = useCurrentFrame();
  const black = easeInOut(progress(t, DURATION - 28, DURATION));
  return (
    <AbsoluteFill style={{ background: COLOR.room }}>
      <Exposed t={t} framing={framing} />
      <Captions t={t} framing={framing} />
      <Keycaps t={t} framing={framing} />
      {black > 0 ? <AbsoluteFill style={{ background: "#000", opacity: black }} /> : null}
      {music ? <Audio src={staticFile("audio/score.wav")} /> : null}
    </AbsoluteFill>
  );
};
