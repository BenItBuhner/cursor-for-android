import type React from "react";
import { FOLD, INNER, PX_PER_MM, TABLET } from "../edit";
import { GRAPHITE, rotY, toCamera, type Turn } from "../light";
import { clamp01, lerp, progress, smooth } from "../math";
import { takes } from "../takes";
import { Body, Sheen } from "./Body";
import { Footage } from "./Footage";

/** DOM pixels to a millimetre of the handheld devices: the cover screen's footage lands a little under one to one. */
export const MM = 12;

const px = (mm: number) => mm * MM;

/** The inner screen's bezel on the Fold: its sides and its top and bottom. */
const FOLD_BEZEL = { x: FOLD.halfWidth - INNER.w / 2, y: (FOLD.height - INNER.h) / 2 };

const layer = (transform: string): React.CSSProperties => ({
  position: "absolute",
  left: 0,
  top: 0,
  transformStyle: "preserve-3d",
  transform,
});

/** A lens behind the glass: a dark disc with a faint ring and a glint. */
const Lens: React.FC<{ x: number; y: number; d: number }> = ({ x, y, d }) => (
  <div
    style={{
      position: "absolute",
      left: px(x - d / 2),
      top: px(y - d / 2),
      width: px(d),
      height: px(d),
      borderRadius: "50%",
      background: "radial-gradient(circle at 36% 34%, #2a3244 0%, #0c0e14 38%, #020203 72%)",
      boxShadow: `0 0 0 ${px(0.18)}px #1a1b1f`,
    }}
  />
);

/**
 * Pixel Fold on its hinge, [fold] 0 shut to 1 flat. The device's origin is the hinge's middle; the right half never
 * moves and the left one swings about the hinge, its back (the cover screen) facing the camera when shut. [cover] and
 * [inner] are the frames of the phone and large takes the two screens show; the inner one wakes as it opens.
 */
export const HingedFold: React.FC<{ turn: Turn; fold: number; cover: number; inner: number }> = ({ turn, fold, cover, inner }) => {
  const w = FOLD.halfWidth;
  const h = FOLD.height;
  const depth = FOLD.halfDepth;
  const r = FOLD.radius;
  // The hinge's ends are rounded off shut and close up square as it opens flat.
  const hinge = 5.5 * (1 - smooth(progress(fold, 0.3, 1)));
  const swing = 180 * (1 - fold);
  const innerOn = smooth(progress(fold, 0.1, 0.5));
  const coverOn = 1 - smooth(progress(fold, 0.03, 0.3));
  const open = fold > 0.001;
  const innerScale = MM / PX_PER_MM.inner;
  const coverScale = MM / PX_PER_MM.cover;
  const coverSize = { w: takes["hero-phone"].width / PX_PER_MM.cover, h: takes["hero-phone"].height / PX_PER_MM.cover };
  const screenHalf = INNER.w / 2;

  /**
   * A half of the inner screen, drawn on its half's face up to the hinge edge. Not a plane of its own: one lying a hair
   * over the glass is near enough coplanar with the faces that the compositor's depth sort puts it over the cover.
   */
  const innerHalf = (side: "left" | "right") =>
    open ? (
      <div
        style={{
          position: "absolute",
          left: px(side === "left" ? FOLD_BEZEL.x : 0),
          top: px(FOLD_BEZEL.y),
          width: px(screenHalf),
          height: px(INNER.h),
          overflow: "hidden",
          background: "#000",
          borderRadius:
            side === "left"
              ? `${px(FOLD.innerRadius)}px 0 0 ${px(FOLD.innerRadius)}px`
              : `0 ${px(FOLD.innerRadius)}px ${px(FOLD.innerRadius)}px 0`,
        }}
      >
        <Footage
          take="hero-large"
          frame={inner}
          scale={innerScale}
          x={side === "left" ? 0 : screenHalf * PX_PER_MM.inner}
          w={screenHalf * PX_PER_MM.inner}
          bars="inner"
        />
        <div style={{ position: "absolute", inset: 0, background: "#000", opacity: 1 - innerOn }} />
        <Sheen turn={turn} strength={0.8} />
      </div>
    ) : null;

  const coverFace = (
    <>
      <div
        style={{
          position: "absolute",
          left: px((w - coverSize.w) / 2),
          top: px(FOLD.cover.y),
          width: px(coverSize.w),
          height: px(coverSize.h),
          overflow: "hidden",
          borderRadius: px(FOLD.coverRadius),
          background: "#000",
        }}
      >
        <Footage take="hero-phone" frame={cover} scale={coverScale} bars="cover" />
        <div style={{ position: "absolute", inset: 0, background: "#000", opacity: 1 - coverOn }} />
      </div>
      <Sheen turn={turn} />
    </>
  );

  return (
    <>
      <div style={layer(`translateX(${px(w / 2)}px)`)}>
        <Body
          w={w}
          h={h}
          depth={depth}
          radii={[hinge, r, r, hinge]}
          unit={MM}
          metal={GRAPHITE}
          camera={toCamera(turn)}
          front={
            <>
              {innerHalf("right")}
              <Lens x={w - 12.5} y={FOLD_BEZEL.y / 2} d={2.3} />
            </>
          }
          backColor="#1b1c1f"
        />
      </div>
      <div style={layer(`rotateY(${swing}deg) translateX(${px(-w / 2)}px)`)}>
        <Body
          w={w}
          h={h}
          depth={depth}
          radii={[r, hinge, hinge, r]}
          unit={MM}
          metal={GRAPHITE}
          camera={toCamera(turn, (n) => rotY(n, swing))}
          front={innerHalf("left")}
          back={coverFace}
          backColor="#050505"
        />
      </div>
    </>
  );
};

/** How far the large take's window has grown from the Fold's inner screen toward the tablet's, 0 to 1, at [frame]. */
export function growthAt(frame: number): number {
  const take = takes["hero-large"];
  const [cw] = take.content[Math.max(0, Math.min(take.frames - 1, Math.round(frame)))] ?? [take.width];
  const first = take.content[0]?.[0] ?? take.width;
  return clamp01((cw - first) / (take.width - first));
}

/**
 * The open Fold as one slab, and the tablet it grows into: its screen is the large take's window at [frame], whatever
 * size that is, and the bezels, corners, thickness and camera go from the Fold's to the tablet's as it grows.
 */
export const Slab: React.FC<{ turn: Turn; frame: number }> = ({ turn, frame }) => {
  const take = takes["hero-large"];
  const f = Math.max(0, Math.min(take.frames - 1, Math.round(frame)));
  const [cw, ch] = take.content[f] ?? [take.width, take.height];
  const g = growthAt(f);
  const sw = cw / PX_PER_MM.inner;
  const sh = ch / PX_PER_MM.inner;
  const bx = lerp(FOLD_BEZEL.x, TABLET.bezel.x, g);
  const by = lerp(FOLD_BEZEL.y, TABLET.bezel.y, g);
  const w = sw + 2 * bx;
  const h = sh + 2 * by;
  const r = lerp(FOLD.radius, TABLET.radius, g);
  const face = (
    <>
      <div
        style={{
          position: "absolute",
          left: px(bx),
          top: px(by),
          width: px(sw),
          height: px(sh),
          overflow: "hidden",
          borderRadius: px(lerp(FOLD.innerRadius, TABLET.screenRadius, g)),
          background: "#000",
        }}
      >
        <Footage take="hero-large" frame={f} scale={MM / PX_PER_MM.inner} bars="inner" />
      </div>
      {/* The Fold's inner camera sits in the right of its top bezel; the tablet's in the middle of its long one. */}
      <Lens x={lerp(w - 12.5, w / 2, g)} y={by / 2} d={lerp(2.3, 2.6, g)} />
      <Sheen turn={turn} strength={0.8} />
    </>
  );
  return (
    <Body
      w={w}
      h={h}
      depth={lerp(FOLD.halfDepth, TABLET.depth, g)}
      radii={[r, r, r, r]}
      unit={MM}
      metal={GRAPHITE}
      camera={toCamera(turn)}
      front={face}
      backColor="#1b1c1f"
    />
  );
};

/** A soft shadow a little behind a device [w] x [h] mm centred at [x], [y], that grounds it in the room. */
export const Shadow: React.FC<{ x: number; y: number; w: number; h: number; z?: number; strength?: number }> = ({
  x,
  y,
  w,
  h,
  z = -70,
  strength = 0.6,
}) => (
  <div
    style={{
      position: "absolute",
      left: px(x - w * 0.8),
      top: px(y - h * 0.75),
      width: px(w * 1.6),
      height: px(h * 1.6),
      transform: `translateZ(${px(z)}px)`,
      background: `radial-gradient(closest-side, rgba(0,0,0,${strength}) 0%, rgba(0,0,0,${strength * 0.45}) 55%, rgba(0,0,0,0) 100%)`,
    }}
  />
);
