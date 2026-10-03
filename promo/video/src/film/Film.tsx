import { useThree } from "@react-three/fiber";
import { DepthOfField, EffectComposer, Noise, ToneMapping, Vignette } from "@react-three/postprocessing";
import { ThreeCanvas } from "@remotion/three";
import { DepthOfFieldEffect, ToneMappingMode } from "postprocessing";
import type React from "react";
import { useLayoutEffect, useMemo, useRef } from "react";
import { AbsoluteFill, cancelRender, continueRender, delayRender, useCurrentFrame, useVideoConfig } from "remotion";
import * as THREE from "three";
import { AppIcon } from "../components/AppIcon";
import { DISCLAIMER, REPO } from "../components/EndCard";
import { ROLE, typeStyle } from "../concept/type";
import type { DeviceId } from "../takes";
import { LOCKED, lockedAt, liftAt, phoneTake, screenOn, SHOT, typeAt, WORDS, type Words } from "./edit";
import { Hardware, outerSize, type Lift } from "./Hardware";
import { reelSrc, type ReelId } from "./reels";
import { Backdrop, Dust, Lamp, LIGHT, ScreenSpill, SetLight, Stone, Window } from "./Room";
import { cameraAt, LINEUP, P0, setAt, type Cam, type Framing } from "./shots";

const INK = { day: "#191612", night: "#EEF0F6" };
const NEAR = 1;
const FAR = 800;

/** The diff card's band of the phone's screen at the take frames it lifts in, as fractions of the screen. */
const DIFF_CARD = { top: 0.222, bottom: 0.593, inset: 0.038 };
const LIFT_BY = 0.45;

/**
 * The still of [id] at take frame [take] as a texture: loaded and decoded before the frame is let through, and the
 * scene drawn again once it's on the glass (the canvas has already drawn this frame by the time the image arrives).
 */
function useReel(id: ReelId, take: number): THREE.Texture {
  const advance = useThree((s) => s.advance);
  const texture = useMemo(() => {
    const t = new THREE.Texture();
    t.colorSpace = THREE.SRGBColorSpace;
    t.anisotropy = 16;
    return t;
  }, []);
  const src = reelSrc(id, take);
  useLayoutEffect(() => {
    if (texture.userData.src === src) return undefined;
    const handle = delayRender(`Loading ${src}`);
    let current = true;
    const image = new Image();
    image.src = src;
    image.decode().then(
      () => {
        if (current) {
          texture.image = image;
          texture.needsUpdate = true;
          texture.userData.src = src;
          advance(performance.now());
        }
        continueRender(handle);
      },
      (error: unknown) => cancelRender(error),
    );
    return () => {
      current = false;
    };
  }, [src, texture, advance]);
  return texture;
}

/** The canvas's own camera, set in place every frame from [cam], its subject moved to [cam.frame] by a lens shift. */
const Rig: React.FC<{ cam: Cam }> = ({ cam }) => {
  const camera = useThree((s) => s.camera) as THREE.PerspectiveCamera;
  const { width, height } = useThree((s) => s.size);
  const az = (cam.az * Math.PI) / 180;
  const el = (cam.el * Math.PI) / 180;
  const out = new THREE.Vector3(Math.cos(el) * Math.cos(az), Math.cos(el) * Math.sin(az), Math.sin(el));
  const up = new THREE.Vector3(-Math.sin(el) * Math.cos(az), -Math.sin(el) * Math.sin(az), Math.cos(el));
  if (cam.roll) up.applyAxisAngle(out, cam.roll);
  camera.position.set(...cam.at).addScaledVector(out, cam.dist);
  camera.up.copy(up);
  camera.lookAt(...cam.at);
  camera.fov = cam.fov;
  camera.near = NEAR;
  camera.far = FAR;
  camera.setViewOffset(width, height, -(cam.frame[0] - 0.5) * width, -(cam.frame[1] - 0.5) * height, width, height);
  camera.updateProjectionMatrix();
  camera.updateMatrixWorld();
  return null;
};

/** The focus distance along the lens's axis to [cam]'s focus point. */
function focusDistance(cam: Cam): number {
  const at = new THREE.Vector3(...cam.at);
  const az = (cam.az * Math.PI) / 180;
  const el = (cam.el * Math.PI) / 180;
  const out = new THREE.Vector3(Math.cos(el) * Math.cos(az), Math.cos(el) * Math.sin(az), Math.sin(el));
  const eye = at.clone().addScaledVector(out, cam.dist);
  return new THREE.Vector3(...(cam.focus ?? cam.at)).sub(eye).dot(out.negate());
}

/**
 * The lens and the grade: depth of field on [cam]'s focus (set on the effect in place, since a changed prop would make
 * a new effect every frame), Khronos PBR Neutral tone mapping, a faint vignette and grain.
 */
const Lens: React.FC<{ cam: Cam }> = ({ cam }) => {
  const ref = useRef<DepthOfFieldEffect>(null);
  const distance = focusDistance(cam);
  useLayoutEffect(() => {
    const dof = ref.current;
    if (!dof) return;
    dof.cocMaterial.worldFocusDistance = distance;
    dof.cocMaterial.worldFocusRange = cam.depth;
    dof.bokehScale = cam.bokeh;
  });
  return (
    <EffectComposer multisampling={4} frameBufferType={THREE.HalfFloatType}>
      <DepthOfField ref={ref} worldFocusDistance={20} worldFocusRange={5} bokehScale={5} resolutionScale={1} />
      <ToneMapping mode={ToneMappingMode.NEUTRAL} />
      <Vignette offset={0.38} darkness={0.28} />
      <Noise opacity={0.028} premultiply />
    </EffectComposer>
  );
};

/** One device in the lineup with the locked take frame on its glass. */
const Locked: React.FC<{ device: DeviceId; smudgeSeed: number }> = ({ device, smudgeSeed }) => {
  const screen = useReel(device, LOCKED);
  return <Hardware pose={LINEUP[device]} screen={screen} glow={1} smudgeSeed={smudgeSeed} />;
};

const World: React.FC<{ f: number; framing: Framing; probe?: Partial<Cam> }> = ({ f, framing, probe }) => {
  const cam = { ...cameraAt(f, framing), ...probe };
  const set = setAt(f);
  const light = LIGHT[set.time];
  const locked = lockedAt(f);
  const phone = useReel(locked ? "phone-lock" : "phone", set.layout === "story" ? phoneTake(f) : LOCKED);
  const glow = set.layout === "story" ? screenOn(f) : 1;
  const lift: Lift | null = f >= SHOT.code.from && f < SHOT.code.to ? { ...DIFF_CARD, by: LIFT_BY * liftAt(f) } : null;
  const outer = outerSize("phone");
  const focus = focusDistance(cam);
  return (
    <>
      <Rig cam={cam} />
      <SetLight id={set.time} center={set.center} span={set.span} shadowSize={4096} />
      <Stone />
      <Window
        id={set.time}
        through={set.glazing.through}
        distance={set.glazing.distance}
        panes={set.glazing.panes}
        roll={set.glazing.roll}
        plant={set.glazing.plant}
        f={f}
      />
      <Backdrop id={set.time} />
      {set.dust ? (
        <Dust
          id={set.time}
          through={set.glazing.through}
          distance={set.glazing.distance}
          panes={set.glazing.panes}
          roll={set.glazing.roll}
          center={[set.glazing.through[0], set.glazing.through[1], 11]}
          size={[64, 54, 22]}
          count={700}
          f={f}
          focus={focus}
          aperture={0.02 * cam.bokeh}
        />
      ) : null}
      {set.layout === "story" ? (
        <>
          <Hardware pose={P0} screen={phone} glow={glow} lift={lift} sun={light.sun} />
          {set.time === "night" ? (
            <ScreenSpill
              x={P0.x}
              y={P0.y}
              turn={P0.turn}
              size={{ w: outer.w, h: outer.h }}
              color="#b3bacb"
              strength={0.16 * glow}
              reach={9}
            />
          ) : null}
        </>
      ) : null}
      {set.layout === "lineup" ? (
        <>
          {set.devices.includes("phone") ? <Hardware pose={LINEUP.phone} screen={phone} glow={1} /> : null}
          {set.devices.includes("foldable") ? <Locked device="foldable" smudgeSeed={5} /> : null}
          {set.devices.includes("tablet") ? <Locked device="tablet" smudgeSeed={7} /> : null}
        </>
      ) : null}
      <Lamp on={set.lamp} position={[-60, 30, 50]} target={[-14, 6, 0]} />
      <Lens cam={cam} />
    </>
  );
};

/** How far a word has settled onto the frame: 0 at its first frame, 1 once the ease has run. */
function settle(p: number): number {
  const u = Math.max(0, Math.min(1, p));
  return u * u * (3 - 2 * u);
}

const Line: React.FC<{
  words: Words;
  f: number;
  framing: Framing;
  width: number;
  height: number;
}> = ({ words, f, framing, width, height }) => {
  const size = words.size[framing];
  const [x, y] = words.at[framing];
  const centred = words.align === "center";
  const shown = typeAt(f, words);
  if (shown <= 0.001) return null;
  return (
    <div
      style={{
        position: "absolute",
        left: centred ? 0 : x * width,
        right: centred ? 0 : undefined,
        top: y * height,
        color: INK[words.ink],
        textAlign: centred ? "center" : "left",
        whiteSpace: "pre",
        opacity: shown,
        transform: `translate3d(0, ${(1 - shown) * 10}px, 0)`,
        ...typeStyle(ROLE.display, size),
      }}
    >
      {words.text[framing]}
    </div>
  );
};

/** The opening lockup over the title: the cube, then the name, held as a splash rather than type alone. */
const TitleSplash: React.FC<{ f: number; framing: Framing; height: number }> = ({ f, framing, height }) => {
  if (f < SHOT.title.from || f >= SHOT.title.to) return null;
  const wide = framing === "wide";
  const icon = wide ? 124 : 140;
  const shown = settle((f - SHOT.title.from + 1) / 10) * settle((SHOT.title.to - f) / 8);
  const top = height * (wide ? 0.175 : 0.24);
  return (
    <div
      style={{
        position: "absolute",
        left: 0,
        right: 0,
        top,
        display: "flex",
        justifyContent: "center",
        opacity: shown,
        transform: `translate3d(0, ${(1 - shown) * 12}px, 0)`,
        filter: `drop-shadow(0 ${icon * 0.06}px ${icon * 0.18}px rgba(40,28,14,0.28))`,
      }}
    >
      <AppIcon size={icon} />
    </div>
  );
};

/** The close, cut in on the beat over the stone: the icon and name, then where to get it, then the small print. */
const EndCard: React.FC<{
  f: number;
  framing: Framing;
  width: number;
  height: number;
}> = ({ f, framing, width, height }) => {
  if (f < SHOT.end.from) return null;
  const wide = framing === "wide";
  const icon = wide ? 156 : 176;
  const title = wide ? 84 : 80;
  const url = wide ? 32 : 34;
  const small = wide ? 20 : 22;
  const soft = "rgba(25,22,18,0.72)";
  const lockup = settle((f - SHOT.end.from + 1) / 14);
  const repo = settle((f - (SHOT.end.from + 30) + 1) / 10);
  const note = settle((f - (SHOT.end.from + 60) + 1) / 10);
  return (
    <AbsoluteFill>
      <div
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          top: height * (wide ? 0.24 : 0.3),
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          opacity: lockup,
          transform: `translate3d(0, ${(1 - lockup) * 16}px, 0)`,
        }}
      >
        <div style={{ filter: `drop-shadow(0 ${icon * 0.05}px ${icon * 0.12}px rgba(40,28,14,0.32))` }}>
          <AppIcon size={icon} />
        </div>
        <div
          style={{
            marginTop: icon * 0.28,
            color: INK.day,
            whiteSpace: "nowrap",
            ...typeStyle(ROLE.display, title),
          }}
        >
          Cursor for Android
        </div>
        <div
          style={{
            width: wide ? 72 : 64,
            height: 1,
            marginTop: title * 0.42,
            background: "rgba(25,22,18,0.22)",
            opacity: repo,
          }}
        />
        <div
          style={{
            marginTop: title * 0.28,
            color: soft,
            whiteSpace: "nowrap",
            opacity: repo,
            ...typeStyle(ROLE.caption, url),
          }}
        >
          {REPO}
        </div>
      </div>
      <div
        style={{
          position: "absolute",
          left: width * 0.08,
          right: width * 0.08,
          bottom: height * (wide ? 0.07 : 0.06),
          textAlign: "center",
          color: soft,
          opacity: note,
          ...typeStyle(ROLE.caption, small),
        }}
      >
        {DISCLAIMER}
      </div>
    </AbsoluteFill>
  );
};

/** The film: one canvas for the whole of it, the type over it, and black for the opening beats. */
export const Film: React.FC<{ framing: Framing; at?: number; probe?: Partial<Cam> }> = ({ framing, at, probe }) => {
  const frame = useCurrentFrame();
  const f = at ?? frame;
  const { width, height } = useVideoConfig();
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      <ThreeCanvas
        width={width}
        height={height}
        shadows="percentage"
        gl={{
          antialias: false,
          toneMapping: THREE.NoToneMapping,
          preserveDrawingBuffer: true,
        }}
        dpr={1}
      >
        <World f={f} framing={framing} probe={probe} />
      </ThreeCanvas>
      <TitleSplash f={f} framing={framing} height={height} />
      {WORDS.filter((w) => f >= w.from && f < w.to).map((w) => (
        <Line key={w.from} words={w} f={f} framing={framing} width={width} height={height} />
      ))}
      <EndCard f={f} framing={framing} width={width} height={height} />
      {f < SHOT.black.to ? (
        <AbsoluteFill
          style={{
            background: "#000",
            opacity: f < SHOT.black.to - 12 ? 1 : Math.max(0, (SHOT.black.to - f) / 12),
          }}
        />
      ) : null}
    </AbsoluteFill>
  );
};

/**
 * Frames of the film picked out for review: frame [i] of this composition is frame [frames[i]] of the film, through
 * [probes[i]]'s changes to its camera if given, to try framings side by side.
 */
export const FilmSampler: React.FC<{ framing: Framing; frames: number[]; probes?: Partial<Cam>[] }> = ({ framing, frames, probes }) => {
  const i = Math.min(useCurrentFrame(), frames.length - 1);
  return <Film framing={framing} at={frames[i] ?? 0} probe={probes?.[i]} />;
};
