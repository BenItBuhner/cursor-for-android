import type React from "react";
import { Freeze, OffthreadVideo, staticFile } from "remotion";
import type { TakeId } from "../edit";
import { easeOut } from "../math";
import { takes, type Take } from "../takes";
import { SANS } from "../theme";

/** Take pixels to a dp on the handheld screens: 420 dpi, filmed at three quarters of their pixels. */
export const PX_PER_DP = (420 / 160) * 0.75;

/** Which system bars a screen has: the cover's has the camera's punch hole in its status bar. */
export type Bars = "cover" | "inner";

/**
 * Frame [frame] of [take], its app window only (from [x] across, [w] wide, in the take's pixels; all of it by default),
 * laid out [scale] DOM pixels to the take's from the parent's top left, with the system bars and the finger over it.
 */
export const Footage: React.FC<{ take: TakeId; frame: number; scale: number; x?: number; w?: number; bars?: Bars }> = ({
  take,
  frame,
  scale,
  x = 0,
  w,
  bars,
}) => {
  const t = takes[take];
  const f = Math.max(0, Math.min(t.frames - 1, Math.round(frame)));
  const [cw, ch] = t.content[f] ?? [t.width, t.height];
  const width = w ?? cw - x;
  return (
    <div style={{ position: "absolute", left: 0, top: 0, width: width * scale, height: ch * scale, overflow: "hidden", background: "#000" }}>
      <div
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          width: t.width,
          height: t.height,
          transformOrigin: "0 0",
          transform: `scale(${scale}) translateX(${-x}px)`,
        }}
      >
        <Freeze frame={f}>
          <OffthreadVideo
            src={staticFile(t.file)}
            muted
            toneMapped={false}
            style={{ position: "absolute", left: 0, top: 0, width: t.width, height: t.height, maxWidth: "none" }}
          />
        </Freeze>
        {bars ? <SystemBars take={t} f={f} kind={bars} width={cw} height={ch} /> : null}
        <Finger take={take} f={f} />
      </div>
    </div>
  );
};

const dp = (v: number) => v * PX_PER_DP;

/**
 * The bars the platform draws around the app, which the capture has no system UI to draw: the app was told their
 * insets and left their room empty. The clock is the take's; the icons are Wi-Fi and a charged battery.
 */
const SystemBars: React.FC<{ take: Take; f: number; kind: Bars; width: number; height: number }> = ({ take, f, kind, width, height }) => {
  const status = take.statusBar[f] ?? 0;
  const nav = take.navBar[f] ?? 0;
  const side = kind === "cover" ? dp(26) : dp(24);
  return (
    <>
      {status > 0 ? (
        <div
          style={{
            position: "absolute",
            left: 0,
            top: 0,
            width,
            height: status,
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: `0 ${side}px`,
            boxSizing: "border-box",
            color: "#f2f2f2",
            fontFamily: SANS,
            fontWeight: 500,
            fontSize: dp(14),
            letterSpacing: dp(0.1),
          }}
        >
          <span style={{ fontVariantNumeric: "tabular-nums" }}>{take.clock[f] ?? "9:41"}</span>
          <span style={{ display: "flex", alignItems: "center", gap: dp(6) }}>
            <Wifi size={dp(16)} />
            <Battery size={dp(16)} />
          </span>
        </div>
      ) : null}
      {kind === "cover" && status > 0 ? (
        <div
          style={{
            position: "absolute",
            left: width / 2 - dp(9),
            top: status / 2 - dp(9),
            width: dp(18),
            height: dp(18),
            borderRadius: "50%",
            background: "radial-gradient(circle at 38% 35%, #1d2230 0%, #07080b 45%, #000 70%)",
            boxShadow: `0 0 0 ${dp(0.8)}px #000`,
          }}
        />
      ) : null}
      {nav > 0 ? (
        <div
          style={{
            position: "absolute",
            left: width / 2 - dp(54),
            top: height - nav / 2 - dp(2),
            width: dp(108),
            height: dp(4),
            borderRadius: dp(2),
            background: "rgba(245,245,245,0.74)",
          }}
        />
      ) : null}
    </>
  );
};

const Wifi: React.FC<{ size: number }> = ({ size }) => (
  <svg width={size * 1.12} height={size} viewBox="0 0 28 25" style={{ display: "block" }}>
    <path d="M14 24.2 L0.9 8.1 C4.5 5.1 9.1 3.3 14 3.3 C18.9 3.3 23.5 5.1 27.1 8.1 Z" fill="#f2f2f2" />
  </svg>
);

const Battery: React.FC<{ size: number }> = ({ size }) => (
  <svg width={size * 0.56} height={size * 1.06} viewBox="0 0 14 26" style={{ display: "block" }}>
    <rect x="4.5" y="0" width="5" height="2.6" rx="1" fill="#f2f2f2" />
    <rect x="0.9" y="2.4" width="12.2" height="22.7" rx="2.4" fill="rgba(242,242,242,0.32)" />
    <rect x="0.9" y="6.6" width="12.2" height="18.5" rx="2.4" fill="#f2f2f2" />
  </svg>
);

/** A press in a take: where, the frame the finger went down and the one it lifted on. */
type Press = { x: number; y: number; down: number; up: number };

/** Frames a lifted finger's mark takes to fade: the capture keeps it in the metadata that long. */
const LIFT_FRAMES = 12;

const pressCache = new Map<TakeId, Press[]>();

function pressesOf(take: TakeId): Press[] {
  const cached = pressCache.get(take);
  if (cached) return cached;
  const out: Press[] = [];
  let wasDown = false;
  takes[take].touch.forEach((touch, i) => {
    const isDown = touch?.[2] === 1;
    if (touch && isDown && !wasDown) out.push({ x: touch[0], y: touch[1], down: i, up: Number.POSITIVE_INFINITY });
    const last = out[out.length - 1];
    if (!isDown && wasDown && last) last.up = i;
    wasDown = isDown;
  });
  pressCache.set(take, out);
  return out;
}

/** The finger, as Android's "show taps" draws it: a disc under it while it is down that swells and fades as it lifts. */
const Finger: React.FC<{ take: TakeId; f: number }> = ({ take, f }) => {
  const press = pressesOf(take).find((p) => f >= p.down && f < p.up + LIFT_FRAMES);
  if (!press) return null;
  const r = dp(22);
  const landed = easeOut(Math.min(1, (f - press.down + 1) / 6));
  const lifted = f >= press.up ? (f - press.up + 1) / LIFT_FRAMES : 0;
  const scale = (0.55 + 0.45 * landed) * (1 + 0.35 * lifted);
  const opacity = landed * (1 - lifted);
  return (
    <div
      style={{
        position: "absolute",
        left: press.x - r,
        top: press.y - r,
        width: 2 * r,
        height: 2 * r,
        borderRadius: "50%",
        background: "rgba(255,255,255,0.3)",
        boxShadow: `inset 0 0 0 ${dp(1.5)}px rgba(255,255,255,0.55), 0 0 ${dp(10)}px rgba(255,255,255,0.12)`,
        transform: `scale(${scale})`,
        opacity,
      }}
    />
  );
};
