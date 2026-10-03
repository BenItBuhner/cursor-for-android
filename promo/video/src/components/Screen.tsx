import type React from "react";
import { Freeze, OffthreadVideo, staticFile } from "remotion";
import type { Shade } from "../edit";
import { clamp01, easeOut } from "../math";
import { liveAt, pxPerDp, takes, type TakeId } from "../takes";
import { COLOR, SYSTEM } from "../theme";

/**
 * Frame [frame] of [take] at [width] pixels across, with the system bars, a finger and, given [shade], the
 * notification shade drawn over it: the capture has no system UI to draw them, so the app was told the bars' insets
 * and left their room empty, and the live notification it posted was recorded beside each frame. The take is held at
 * its first frame and trimmed to [frame]: Remotion clamps a frozen frame to the composition's length, which a take's
 * later frames run past, but not a trim.
 */
/**
 * [clock], if given, is the time the status bar and lock screen show instead of the take's; [runFor], seconds the run
 * has already been going before the take's own start, for the live notification's chronometer.
 */
export const Screen: React.FC<{ take: TakeId; frame: number; width: number; shade?: Shade; lock?: boolean; clock?: string; runFor?: number }> = ({
  take,
  frame,
  width,
  shade,
  lock,
  clock,
  runFor = 0,
}) => {
  const t = takes[take];
  const f = Math.max(0, Math.min(t.frames - 1, Math.round(frame)));
  const scale = width / t.width;
  return (
    <div style={{ position: "absolute", inset: 0, overflow: "hidden", background: t.night ? "#000" : "#fff" }}>
      {lock ? null : (
        <Freeze frame={0}>
          <OffthreadVideo
            src={staticFile(t.file)}
            trimBefore={f}
            muted
            toneMapped={false}
            style={{ position: "absolute", left: 0, top: 0, width, height: t.height * scale, maxWidth: "none" }}
          />
        </Freeze>
      )}
      <div style={{ position: "absolute", left: 0, top: 0, width: t.width, height: t.height, transform: `scale(${scale})`, transformOrigin: "0 0" }}>
        {lock ? <LockScreen take={take} f={f} clock={clock} runFor={runFor} /> : <Finger take={take} f={f} />}
        {shade && shade.open > 0 ? <NotificationShade take={take} f={f} open={shade.open} /> : null}
        <SystemBars take={take} f={f} clock={lock ? "" : clock} />
        {shade?.finger ? (
          <FingerDisc
            take={take}
            x={shade.finger.x * t.width}
            y={shade.finger.y * t.height}
            scale={0.6 + 0.4 * clamp01(shade.finger.alpha * 1.5)}
            opacity={shade.finger.alpha}
          />
        ) : null}
      </div>
    </div>
  );
};

/** The status bar's clock, icons and gesture handle in the take's theme: light over a dark screen, dark over a light one. */
const ink = (take: TakeId) => (takes[take].night ? COLOR.statusText : COLOR.statusTextOnLight);

/** The status bar (the take's clock, Wi-Fi and a full battery) and the gesture handle, in the take's pixels. */
const SystemBars: React.FC<{ take: TakeId; f: number; clock?: string }> = ({ take, f, clock }) => {
  const t = takes[take];
  const dp = (v: number) => v * pxPerDp(take);
  const status = t.statusBar[f] ?? 0;
  const nav = t.navBar[f] ?? 0;
  const side = t.device === "phone" ? dp(24) : dp(20);
  return (
    <>
      <div
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          width: t.width,
          height: status,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: `0 ${side}px`,
          boxSizing: "border-box",
          color: ink(take),
          fontFamily: SYSTEM,
          fontWeight: 500,
          fontSize: dp(t.device === "phone" ? 14.5 : 13),
          letterSpacing: dp(0.1),
        }}
      >
        <span style={{ fontVariantNumeric: "tabular-nums" }}>{clock ?? t.clock[f] ?? "9:41"}</span>
        <span style={{ display: "flex", alignItems: "center", gap: dp(6) }}>
          <Wifi size={dp(15)} color={ink(take)} />
          <Battery size={dp(15)} color={ink(take)} />
        </span>
      </div>
      <div
        style={{
          position: "absolute",
          left: t.width / 2 - dp(54),
          top: t.height - nav / 2 - dp(2),
          width: dp(108),
          height: dp(4),
          borderRadius: dp(2),
          background: t.night ? "rgba(245,245,245,0.78)" : "rgba(27,27,31,0.62)",
        }}
      />
    </>
  );
};

const Wifi: React.FC<{ size: number; color: string }> = ({ size, color }) => (
  <svg width={size * 1.12} height={size} viewBox="0 0 28 25" style={{ display: "block" }}>
    <path d="M14 24.2 L0.9 8.1 C4.5 5.1 9.1 3.3 14 3.3 C18.9 3.3 23.5 5.1 27.1 8.1 Z" fill={color} />
  </svg>
);

const Battery: React.FC<{ size: number; color: string }> = ({ size, color }) => (
  <svg width={size * 0.56} height={size * 1.06} viewBox="0 0 14 26" style={{ display: "block" }}>
    <rect x="4.5" y="0" width="5" height="2.6" rx="1" fill={color} />
    <rect x="0.9" y="2.4" width="12.2" height="22.7" rx="2.4" fill={color} />
  </svg>
);

/** The date the capture's clock keeps (VirtualTime.EPOCH_MS), as the shade's header has it. */
const DATE = "Mon, Sep 28";

/** The shade in the system's theme, which the app's follows: its scrim, its cards, and the ink on them. */
const SHADE_LOOK = {
  dark: {
    scrim: "rgba(10,10,12,0.8)",
    card: "#2A2A2E",
    primary: "#EDEDF0",
    secondary: "#A9A9B2",
    action: "#A3BCD6",
    track: "rgba(129,161,193,0.26)",
    bar: "#81A1C1",
  },
  light: {
    scrim: "rgba(234,235,239,0.84)",
    card: "#FFFFFF",
    primary: "#1B1B1F",
    secondary: "#5E5E66",
    action: "#46668A",
    track: "rgba(95,127,160,0.22)",
    bar: "#6887A8",
  },
};

/** The notification's colour (LiveNotificationRenderer.ACCENT), behind its small icon. */
const ACCENT = "#81A1C1";

/** How long the indeterminate bar's sweep takes, in the take's milliseconds. */
const SWEEP_MS = 1500;

/**
 * The notification shade, [open] of the way down: the app blurred and dimmed under it, the date, and the run's live
 * notification as Android 16 lays out a promoted ProgressStyle one (LiveNotificationRenderer.single): the small icon
 * in the notification's colour, "Cursor • Running •" and the chronometer, the chat's title, the step, the bar, and Stop.
 */
const NotificationShade: React.FC<{ take: TakeId; f: number; open: number }> = ({ take, f, open }) => {
  const t = takes[take];
  const dp = (v: number) => v * pxPerDp(take);
  const look = SHADE_LOOK[t.night ? "dark" : "light"];
  const status = t.statusBar[f] ?? dp(28);
  /** The app dims and blurs as the shade's content slides down out from under the status bar, opaque the whole way. */
  const dimmed = clamp01(open * 1.5);
  const drop = dp(44 + 140 + 24);
  return (
    <>
      <div style={{ position: "absolute", inset: 0, background: look.scrim, opacity: dimmed, backdropFilter: `blur(${dp(9) * dimmed}px)` }} />
      <div style={{ position: "absolute", left: 0, right: 0, top: status, bottom: 0, overflow: "hidden" }}>
        <div
          style={{
            position: "absolute",
            left: 0,
            top: -status,
            width: t.width,
            fontFamily: SYSTEM,
            transform: `translateY(${-(1 - easeOut(open)) * drop}px)`,
          }}
        >
          <div style={{ position: "absolute", left: dp(24), top: status + dp(10), fontSize: dp(15), fontWeight: 500, color: look.primary }}>{DATE}</div>
          <LiveCard take={take} f={f} top={status + dp(44)} />
        </div>
      </div>
    </>
  );
};

/**
 * The run's live notification at [top] of the screen, as Android 16 lays out a promoted ProgressStyle one
 * (LiveNotificationRenderer.single): the small icon in the notification's colour, "Cursor • Running •" and the
 * chronometer, the chat's title, the step, the bar, and Stop. Nothing while the run has none posted.
 */
const LiveCard: React.FC<{ take: TakeId; f: number; top: number; runFor?: number }> = ({ take, f, top, runFor = 0 }) => {
  const t = takes[take];
  const dp = (v: number) => v * pxPerDp(take);
  const look = SHADE_LOOK[t.night ? "dark" : "light"];
  const step = liveAt(take, f);
  if (!step) return null;
  const elapsed = runFor + Math.max(0, Math.floor(((t.t[f] ?? 0) - t.live.startT) / 1000));
  const two = (n: number) => String(n).padStart(2, "0");
  // As Android's Chronometer: MM:SS, and H:MM:SS from the first hour.
  const chronometer =
    elapsed >= 3600 ? `${Math.floor(elapsed / 3600)}:${two(Math.floor(elapsed / 60) % 60)}:${two(elapsed % 60)}` : `${two(Math.floor(elapsed / 60))}:${two(elapsed % 60)}`;
  const sweep = ((t.t[f] ?? 0) % SWEEP_MS) / SWEEP_MS;
  const cardW = t.width - dp(24);
  const barW = cardW - dp(60 + 16);
  const segment = 0.36;
  return (
            <div
              style={{
                position: "absolute",
                left: dp(12),
                top,
                width: cardW,
                height: dp(140),
                borderRadius: dp(24),
                background: look.card,
                fontFamily: SYSTEM,
              }}
            >
              <div style={{ position: "absolute", left: dp(16), top: dp(16), width: dp(32), height: dp(32), borderRadius: "50%", background: ACCENT }}>
                <svg width={dp(32)} height={dp(32)} viewBox="-4 -4 32 32" style={{ display: "block" }}>
                  <g transform="translate(3.2284 2) scale(0.037588)">
                    <path
                      fill="#FFFFFF"
                      d="M457.43,125.94L244.42,2.96c-6.84-3.95-15.28-3.95-22.12,0L9.3,125.94c-5.75,3.32-9.3,9.46-9.3,16.11v247.99c0,6.65,3.55,12.79,9.3,16.11l213.01,122.98c6.84,3.95,15.28,3.95,22.12,0l213.01-122.98c5.75-3.32,9.3-9.46,9.3-16.11v-247.99c0-6.65-3.55-12.79-9.3-16.11h-.01ZM444.05,151.99l-205.63,356.16c-1.39,2.4-5.06,1.42-5.06-1.36v-233.21c0-4.66-2.49-8.97-6.53-11.31L24.87,145.67c-2.4-1.39-1.42-5.06,1.36-5.06h411.26c5.84,0,9.49,6.33,6.57,11.39h-.01Z"
                    />
                  </g>
                </svg>
              </div>
              <div
                style={{
                  position: "absolute",
                  left: dp(60),
                  top: dp(15),
                  right: dp(52),
                  fontSize: dp(12.5),
                  lineHeight: `${dp(16)}px`,
                  color: look.secondary,
                  whiteSpace: "nowrap",
                }}
              >
                {t.live.app} • {step.sub} • <span style={{ fontVariantNumeric: "tabular-nums" }}>{chronometer}</span>
              </div>
              <Chevron x={cardW - dp(16 + 28)} y={dp(14)} w={dp(28)} h={dp(20)} color={look.secondary} track={look.track} />
              <div
                style={{
                  position: "absolute",
                  left: dp(60),
                  top: dp(34),
                  right: dp(16),
                  fontSize: dp(16),
                  lineHeight: `${dp(22)}px`,
                  fontWeight: 500,
                  color: look.primary,
                  whiteSpace: "nowrap",
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                }}
              >
                {t.live.title}
              </div>
              <div
                style={{
                  position: "absolute",
                  left: dp(60),
                  top: dp(56),
                  right: dp(16),
                  fontSize: dp(14),
                  lineHeight: `${dp(20)}px`,
                  color: look.secondary,
                  whiteSpace: "nowrap",
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                }}
              >
                {step.text}
              </div>
              <div
                style={{
                  position: "absolute",
                  left: dp(60),
                  top: dp(86),
                  width: barW,
                  height: dp(6),
                  borderRadius: dp(3),
                  background: look.track,
                  overflow: "hidden",
                }}
              >
                {step.indeterminate ? (
                  <div
                    style={{
                      position: "absolute",
                      top: 0,
                      height: "100%",
                      width: barW * segment,
                      left: (sweep * (1 + segment) - segment) * barW,
                      borderRadius: dp(3),
                      background: look.bar,
                    }}
                  />
                ) : null}
              </div>
              <div style={{ position: "absolute", left: dp(60), top: dp(106), display: "flex", gap: dp(24) }}>
                {step.actions.map((action) => (
                  <span key={action} style={{ fontSize: dp(14), lineHeight: `${dp(20)}px`, fontWeight: 500, color: look.action }}>
                    {action}
                  </span>
                ))}
              </div>
            </div>
  );
};

/**
 * The lock screen the phone wakes to when the run's notification updates: the wallpaper, the clock, the date and the
 * notification under them, and the lock at the foot.
 */
const LockScreen: React.FC<{ take: TakeId; f: number; clock?: string; runFor: number }> = ({ take, f, clock: time, runFor }) => {
  const t = takes[take];
  const dp = (v: number) => v * pxPerDp(take);
  const look = SHADE_LOOK.dark;
  const clock = time ?? t.clock[f] ?? "9:41";
  return (
    <>
      <div style={{ position: "absolute", inset: 0, background: "radial-gradient(120% 70% at 30% 18%, #1d2230 0%, #0c0e14 55%, #050608 100%)" }} />
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          top: dp(96),
          textAlign: "center",
          fontFamily: SYSTEM,
          fontWeight: 400,
          fontSize: dp(88),
          lineHeight: 1,
          letterSpacing: dp(-1.5),
          color: look.primary,
          fontVariantNumeric: "tabular-nums",
        }}
      >
        {clock}
      </div>
      <div style={{ position: "absolute", left: 0, right: 0, top: dp(196), textAlign: "center", fontFamily: SYSTEM, fontSize: dp(15), fontWeight: 500, color: look.secondary }}>
        {DATE}
      </div>
      <LiveCard take={take} f={f} top={dp(244)} runFor={runFor} />
      <svg width={dp(20)} height={dp(24)} viewBox="0 0 20 24" style={{ position: "absolute", left: t.width / 2 - dp(10), top: t.height - dp(64) }}>
        <rect x="2" y="10" width="16" height="12" rx="3" fill="none" stroke={look.secondary} strokeWidth="1.8" />
        <path d="M6 10 V7 a4 4 0 0 1 8 0 V10" fill="none" stroke={look.secondary} strokeWidth="1.8" />
      </svg>
    </>
  );
};

/** The notification's expand button: a chevron in a pill. */
const Chevron: React.FC<{ x: number; y: number; w: number; h: number; color: string; track: string }> = ({ x, y, w, h, color, track }) => (
  <div style={{ position: "absolute", left: x, top: y, width: w, height: h, borderRadius: h / 2, background: track }}>
    <svg width={w} height={h} viewBox="0 0 28 20" style={{ display: "block" }}>
      <path d="M9.5 8 L14 12.5 L18.5 8" fill="none" stroke={color} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  </div>
);

/** A press in a take: the frame the finger went down and the one it lifted on. */
type Press = { down: number; up: number };

/** Frames a lifted finger's mark takes to fade. */
const LIFT_FRAMES = 14;

const pressCache = new Map<TakeId, Press[]>();

function pressesOf(take: TakeId): Press[] {
  const cached = pressCache.get(take);
  if (cached) return cached;
  const out: Press[] = [];
  let wasDown = false;
  takes[take].touch.forEach((touch, i) => {
    const isDown = touch?.[2] === 1;
    if (isDown && !wasDown) out.push({ down: i, up: Number.POSITIVE_INFINITY });
    const last = out[out.length - 1];
    if (!isDown && wasDown && last) last.up = i;
    wasDown = isDown;
  });
  pressCache.set(take, out);
  return out;
}

/** The take's finger, as a soft disc under it that follows it while it is down and swells and fades as it lifts. */
const Finger: React.FC<{ take: TakeId; f: number }> = ({ take, f }) => {
  let press: Press | undefined;
  for (const p of pressesOf(take)) if (f >= p.down) press = p;
  if (!press || f >= press.up + LIFT_FRAMES) return null;
  const at = takes[take].touch[Math.min(f, press.up - 1)];
  if (!at) return null;
  const landed = easeOut(Math.min(1, (f - press.down + 1) / 6));
  const lifted = f >= press.up ? (f - press.up + 1) / LIFT_FRAMES : 0;
  return <FingerDisc take={take} x={at[0]} y={at[1]} scale={(0.6 + 0.4 * landed) * (1 + 0.4 * lifted)} opacity={landed * (1 - lifted)} />;
};

/** A finger's disc at [x, y] of the take's pixels: light on a dark screen, dark on a light one. */
const FingerDisc: React.FC<{ take: TakeId; x: number; y: number; scale: number; opacity: number }> = ({ take, x, y, scale, opacity }) => {
  const r = 24 * pxPerDp(take);
  const night = takes[take].night;
  return (
    <div
      style={{
        position: "absolute",
        left: x - r,
        top: y - r,
        width: 2 * r,
        height: 2 * r,
        borderRadius: "50%",
        background: night ? "rgba(255,255,255,0.28)" : "rgba(20,18,11,0.14)",
        boxShadow: `inset 0 0 0 ${1.5 * pxPerDp(take)}px ${night ? "rgba(255,255,255,0.6)" : "rgba(20,18,11,0.36)"}`,
        transform: `scale(${scale})`,
        opacity,
      }}
    />
  );
};
