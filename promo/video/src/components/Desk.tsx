import type React from "react";
import { poseAt, type Framing } from "../camera";
import { DESK_SHOTS, MONITOR, sourceFrame } from "../edit";
import { GRAPHITE, toCamera, type Turn } from "../light";
import { takes } from "../takes";
import { SANS } from "../theme";
import { AppIcon } from "./AppIcon";
import { Body, Sheen } from "./Body";
import { Footage } from "./Footage";
import { Stage } from "./Stage";

/** DOM pixels to a pixel of the desktop: its take was filmed at one and a half times them, so it lands one to one. */
const UNIT = MONITOR.pxPerUnit;
const px = (v: number) => v * UNIT;

const BODY = { w: MONITOR.w + 2 * MONITOR.bezel, h: MONITOR.h + MONITOR.bezel + MONITOR.chin, depth: 30, radius: 9 };

/**
 * The taskbar a desktop-mode session keeps under its windows, drawn plainly (the capture has no system UI): the app
 * drawer, the app running, and the status area with the clock.
 */
const Taskbar: React.FC<{ clock: string }> = ({ clock }) => {
  const h = MONITOR.taskbar;
  return (
    <div
      style={{
        position: "absolute",
        left: 0,
        top: px(MONITOR.window),
        width: px(MONITOR.w),
        height: px(h),
        background: "#0f0f0f",
        borderTop: `${px(1)}px solid #232323`,
        boxSizing: "border-box",
        display: "flex",
        alignItems: "center",
        padding: `0 ${px(18)}px`,
        color: "#ececec",
        fontFamily: SANS,
        fontSize: px(15),
        fontWeight: 500,
      }}
    >
      <svg width={px(20)} height={px(20)} viewBox="0 0 20 20" style={{ display: "block", marginRight: px(22) }}>
        {[3, 10, 17].flatMap((y) => [3, 10, 17].map((x) => <circle key={`${x}-${y}`} cx={x} cy={y} r={1.9} fill="#dcdcdc" />))}
      </svg>
      <div style={{ position: "relative", width: px(30), height: px(30) }}>
        <AppIcon size={px(30)} />
        <div
          style={{
            position: "absolute",
            left: px(9),
            top: px(35),
            width: px(12),
            height: px(2.5),
            borderRadius: px(2),
            background: "#e8e8e8",
          }}
        />
      </div>
      <div style={{ flex: 1 }} />
      <svg width={px(19)} height={px(17)} viewBox="0 0 28 25" style={{ display: "block", marginRight: px(12) }}>
        <path d="M14 24.2 L0.9 8.1 C4.5 5.1 9.1 3.3 14 3.3 C18.9 3.3 23.5 5.1 27.1 8.1 Z" fill="#ececec" />
      </svg>
      <svg width={px(10)} height={px(18)} viewBox="0 0 14 26" style={{ display: "block", marginRight: px(16) }}>
        <rect x="4.5" y="0" width="5" height="2.6" rx="1" fill="#ececec" />
        <rect x="0.9" y="2.4" width="12.2" height="22.7" rx="2.4" fill="rgba(236,236,236,0.32)" />
        <rect x="0.9" y="6.6" width="12.2" height="18.5" rx="2.4" fill="#ececec" />
      </svg>
      <span style={{ fontVariantNumeric: "tabular-nums" }}>{clock}</span>
    </div>
  );
};

/** A monitor on a stand showing the desktop take's window at [frame], its screen's centre at the origin. */
export const Monitor: React.FC<{ turn: Turn; frame: number }> = ({ turn, frame }) => {
  const take = takes.desktop;
  const f = Math.max(0, Math.min(take.frames - 1, Math.round(frame)));
  const face = (
    <>
      <div
        style={{
          position: "absolute",
          left: px(MONITOR.bezel),
          top: px(MONITOR.bezel),
          width: px(MONITOR.w),
          height: px(MONITOR.h),
          overflow: "hidden",
          background: "#000",
          borderRadius: px(2),
        }}
      >
        <Footage take="desktop" frame={f} scale={UNIT / MONITOR.pxPerUnit} />
        <Taskbar clock={take.clock[f] ?? "9:41"} />
      </div>
      <Sheen turn={turn} strength={0.7} />
    </>
  );
  const camera = toCamera(turn);
  // The body's centre sits below the screen's by half the chin's extra height.
  const centre = (MONITOR.chin - MONITOR.bezel) / 2;
  return (
    <>
      <div style={{ position: "absolute", left: 0, top: 0, transformStyle: "preserve-3d", transform: `translate3d(0, ${px(560)}px, ${px(-BODY.depth)}px)` }}>
        <Body w={170} h={620} depth={26} radii={[10, 10, 10, 10]} unit={UNIT} metal={GRAPHITE} camera={camera} frontColor="#2b2c30" backColor="#232427" />
      </div>
      <div style={{ position: "absolute", left: 0, top: 0, transformStyle: "preserve-3d", transform: `translateY(${px(centre)}px)` }}>
        <Body
          w={BODY.w}
          h={BODY.h}
          depth={BODY.depth}
          radii={[BODY.radius, BODY.radius, BODY.radius, BODY.radius]}
          unit={UNIT}
          metal={GRAPHITE}
          camera={camera}
          front={face}
          frontColor="#070707"
          backColor="#1c1d20"
        />
      </div>
    </>
  );
};

/** The desktop-mode half of the cut: the monitor, under the desk's camera. */
export const Desk: React.FC<{ t: number; framing: Framing; opacity?: number }> = ({ t, framing, opacity = 1 }) => {
  const pose = poseAt(DESK_SHOTS, framing, t);
  return (
    <Stage pose={pose} unit={UNIT} opacity={opacity}>
      <Monitor turn={pose} frame={sourceFrame("desktop", t)} />
    </Stage>
  );
};
