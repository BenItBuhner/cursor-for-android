import type React from "react";
import { AbsoluteFill, spring } from "remotion";
import type { Framing } from "../camera";
import { END, FOLD, FPS, INNER, TABLET, TABLET_SCREEN, type Pose } from "../edit";
import { easeInOut, easeOut, lerp, progress } from "../math";
import { lastFrame } from "../takes";
import { COLOR, MONO, SANS } from "../theme";
import { AppIcon } from "./AppIcon";
import { HingedFold, MM, Shadow, Slab } from "./Devices";
import { Stage } from "./Stage";

/** The screens' last looks: the cover with the subagents at work, the inner screen with its panel pinned, the tablet's answer. */
const FRAMES = { cover: 1000, inner: 110, tablet: lastFrame("hero-large") };

/** The open Fold's and the tablet's outlines, in mm. */
const OPEN = { w: INNER.w + 2 * (FOLD.halfWidth - INNER.w / 2), h: FOLD.height };
const TAB = { w: TABLET_SCREEN.w + 2 * TABLET.bezel.x, h: TABLET_SCREEN.h + 2 * TABLET.bezel.y };

type Layout = {
  pose: [Pose, Pose];
  /** The tablet's and the open Fold's centres, and the shut Fold's hinge (its left edge), in mm. */
  tablet: [number, number];
  open: [number, number];
  shut: [number, number];
  rise: number;
  /** The text's lines, by the frame height they are centred on. */
  lines: { title: number; tagline: number; platforms: number; repo: number; note: number };
  title: number;
  icon: number;
  body: number;
};

const pose = (at: [number, number], k: number, ay: number, rx: number, ry: number): Pose => ({ at, k, rx, ry, rz: 0, ax: 0.5, ay });

const LAYOUT: Record<Framing, Layout> = {
  wide: {
    pose: [pose([0, -10], 2.75, 0.53, 4, -6), pose([0, -10], 2.75, 0.53, 2, 4)],
    tablet: [143, 0],
    open: [-83.6, 11.3],
    shut: [-266.3, 11.3],
    rise: 230,
    lines: { title: 168, tagline: 238, platforms: 872, repo: 930, note: 1010 },
    title: 72,
    icon: 64,
    body: 28,
  },
  tall: {
    pose: [pose([0, -9.15], 3.55, 0.5, 4, -6), pose([0, -9.15], 3.55, 0.5, 2, 4)],
    tablet: [0, -90],
    open: [-50.7, 83],
    shut: [50.7, 83],
    rise: 300,
    lines: { title: 238, tagline: 316, platforms: 1604, repo: 1666, note: 1800 },
    title: 76,
    icon: 68,
    body: 32,
  },
};

/** When each device springs up, and each line of text comes in. */
const RISE = { shut: END.from + 6, open: END.from + 14, tablet: END.from + 22 };
const TEXT = { title: END.from + 16, tagline: END.from + 26, platforms: END.from + 34, repo: END.from + 42, note: END.from + 50 };

const Line: React.FC<{ y: number; at: number; t: number; children: React.ReactNode; style?: React.CSSProperties }> = ({
  y,
  at,
  t,
  children,
  style,
}) => {
  const p = easeOut(progress(t, at, at + 24));
  return (
    <div
      style={{
        position: "absolute",
        left: 0,
        right: 0,
        top: y,
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
        fontFamily: SANS,
        opacity: p,
        transform: `translateY(calc(-50% + ${18 * (1 - p)}px))`,
        ...style,
      }}
    >
      {children}
    </div>
  );
};

/** The end card: every screen the app was filmed on, side by side, over its name and where to find it. */
export const EndCard: React.FC<{ t: number; framing: Framing }> = ({ t, framing }) => {
  const l = LAYOUT[framing];
  const drift = easeInOut(progress(t, END.from, END.to));
  const [a, z] = l.pose;
  const p: Pose = { ...a, rx: lerp(a.rx, z.rx, drift), ry: lerp(a.ry, z.ry, drift) };
  const lift = (start: number) =>
    (1 - spring({ frame: t - start, fps: FPS, config: { damping: 19, stiffness: 70, mass: 1.1 } })) * l.rise;
  const place = (x: number, y: number): React.CSSProperties => ({
    position: "absolute",
    left: 0,
    top: 0,
    transformStyle: "preserve-3d",
    transform: `translate3d(${x * MM}px, ${y * MM}px, 0)`,
  });
  const shut = { x: l.shut[0], y: l.shut[1] + lift(RISE.shut) };
  const open = { x: l.open[0], y: l.open[1] + lift(RISE.open) };
  const tablet = { x: l.tablet[0], y: l.tablet[1] + lift(RISE.tablet) };
  return (
    <AbsoluteFill>
      <Stage pose={p} unit={MM}>
        <Shadow x={shut.x + FOLD.halfWidth / 2} y={shut.y + 6} w={FOLD.halfWidth} h={FOLD.height} strength={0.5} />
        <Shadow x={open.x} y={open.y + 6} w={OPEN.w} h={OPEN.h} strength={0.5} />
        <Shadow x={tablet.x} y={tablet.y + 6} w={TAB.w} h={TAB.h} strength={0.5} />
        <div style={place(shut.x, shut.y)}>
          <HingedFold turn={p} fold={0} cover={FRAMES.cover} inner={0} />
        </div>
        <div style={place(open.x, open.y)}>
          <Slab turn={p} frame={FRAMES.inner} />
        </div>
        <div style={place(tablet.x, tablet.y)}>
          <Slab turn={p} frame={FRAMES.tablet} />
        </div>
      </Stage>
      <Line y={l.lines.title} at={TEXT.title} t={t} style={{ gap: l.icon * 0.34 }}>
        <AppIcon size={l.icon} glow={0.8} />
        <span style={{ color: COLOR.text, fontSize: l.title, fontWeight: 700, letterSpacing: "-0.035em", whiteSpace: "nowrap" }}>
          Cursor for Android
        </span>
      </Line>
      <Line y={l.lines.tagline} at={TEXT.tagline} t={t}>
        <span style={{ color: COLOR.dim, fontSize: l.body + 2, fontWeight: 500, letterSpacing: "-0.005em" }}>
          Unofficial, native client for Cursor Cloud Agents
        </span>
      </Line>
      <Line y={l.lines.platforms} at={TEXT.platforms} t={t}>
        <span style={{ color: COLOR.text, fontSize: l.body, fontWeight: 600, letterSpacing: "0.01em", whiteSpace: "pre" }}>
          {"Phones  ·  Foldables  ·  Tablets  ·  Desktop mode"}
        </span>
      </Line>
      <Line y={l.lines.repo} at={TEXT.repo} t={t}>
        <span style={{ color: COLOR.accent, fontFamily: MONO, fontSize: l.body - 2, fontWeight: 500 }}>
          github.com/BenItBuhner/cursor-for-android
        </span>
      </Line>
      <Line y={l.lines.note} at={TEXT.note} t={t}>
        <span style={{ color: COLOR.faint, fontSize: l.body - 8, fontWeight: 500, letterSpacing: "0.02em" }}>
          Not affiliated with Anysphere, Inc.
        </span>
      </Line>
    </AbsoluteFill>
  );
};
