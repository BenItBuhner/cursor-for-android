import { useThree } from "@react-three/fiber";
import { DepthOfField, EffectComposer, Noise, ToneMapping, Vignette } from "@react-three/postprocessing";
import { ThreeCanvas } from "@remotion/three";
import { ToneMappingMode } from "postprocessing";
import type React from "react";
import { useMemo } from "react";
import { AbsoluteFill, useVideoConfig } from "remotion";
import * as THREE from "three";
import { Device3D, screenSize } from "./Device3D";
import { LIGHT, ScreenSpill, SetLight, Stone, type Light } from "./Set";
import { ROLE, typeStyle } from "./type";

const INK = { day: "#191612", night: "#EEF0F6" };

/** Where the camera is, what it looks at, and, if not that, the point the lens is focused on. */
type View = { position: [number, number, number]; target: [number, number, number]; fov: number; focus?: [number, number, number] };

/**
 * The canvas's own camera, up the +z axis, looking from [view.position] at [view.target]. Set in place during render
 * rather than swapped for a new default, so the effect composer can never have captured a different camera first.
 */
const Camera: React.FC<{ view: View }> = ({ view }) => {
  const camera = useThree((s) => s.camera) as THREE.PerspectiveCamera;
  camera.up.set(0, 0, 1);
  camera.position.set(...view.position);
  camera.fov = view.fov;
  camera.near = 0.5;
  camera.far = 600;
  camera.lookAt(...view.target);
  camera.updateProjectionMatrix();
  return null;
};

/**
 * A window between the sun and the set: a wall [distance] centimetres back along the sun's ray through [through], two
 * panes cut in it either side of a mullion, invisible itself and there only for the shadow it throws, so the sun falls
 * across the stone as a bright, sharp-edged band through the glass rather than everywhere at once.
 */
const Window: React.FC<{ light: Light; through: [number, number, number]; distance: number; panes: { w: number; h: number; mullion: number } }> = ({
  light,
  through,
  distance,
  panes,
}) => {
  const sun = light.sun!;
  const a = (sun.azimuth * Math.PI) / 180;
  const e = (sun.elevation * Math.PI) / 180;
  const dir = new THREE.Vector3(Math.cos(e) * Math.cos(a), Math.cos(e) * Math.sin(a), Math.sin(e));
  const at = new THREE.Vector3(...through).addScaledVector(dir, distance);
  const shape = useMemo(() => {
    const s = new THREE.Shape();
    s.moveTo(-300, -300);
    s.lineTo(300, -300);
    s.lineTo(300, 300);
    s.lineTo(-300, 300);
    s.lineTo(-300, -300);
    for (const side of [-1, 1]) {
      const x0 = side < 0 ? -panes.mullion / 2 - panes.w : panes.mullion / 2;
      const hole = new THREE.Path();
      hole.moveTo(x0, -panes.h / 2);
      hole.lineTo(x0 + panes.w, -panes.h / 2);
      hole.lineTo(x0 + panes.w, panes.h / 2);
      hole.lineTo(x0, panes.h / 2);
      hole.lineTo(x0, -panes.h / 2);
      s.holes.push(hole);
    }
    return new THREE.ShapeGeometry(s);
  }, [panes]);
  return (
    <mesh
      geometry={shape}
      position={at}
      castShadow
      onUpdate={(m) => {
        m.up.set(0, 0, 1);
        m.lookAt(at.clone().add(dir));
      }}
    >
      <meshBasicMaterial colorWrite={false} depthWrite={false} side={THREE.DoubleSide} />
    </mesh>
  );
};

/**
 * The lens and the grade: focus held on [view.target] with [depth] centimetres either side of it sharp, then Khronos PBR
 * Neutral tone mapping, which leaves colours below about 0.8 untouched so the app's own palette reaches the frame as
 * designed, a faint vignette, and film grain.
 */
const Lens: React.FC<{ view: View; depth: number; bokeh: number }> = ({ view, depth, bokeh }) => {
  const distance = new THREE.Vector3(...view.position).distanceTo(new THREE.Vector3(...(view.focus ?? view.target)));
  return (
    <EffectComposer multisampling={4} frameBufferType={THREE.HalfFloatType}>
      <DepthOfField worldFocusDistance={distance} worldFocusRange={depth} bokehScale={bokeh} resolutionScale={1} />
      <ToneMapping mode={ToneMappingMode.NEUTRAL} />
      <Vignette offset={0.32} darkness={0.42} />
      <Noise opacity={0.035} premultiply />
    </EffectComposer>
  );
};

const Frame: React.FC<{
  view: View;
  light: Light;
  span?: number;
  depth?: number;
  bokeh?: number;
  children: React.ReactNode;
  type: React.ReactNode;
}> = ({ view, light, span, depth = 14, bokeh = 5, children, type }) => {
  const { width, height } = useVideoConfig();
  return (
    <AbsoluteFill style={{ background: "#000" }}>
      <ThreeCanvas
        width={width}
        height={height}
        shadows="percentage"
        gl={{ antialias: false, toneMapping: THREE.NoToneMapping, preserveDrawingBuffer: true }}
        dpr={1}
      >
        <Camera view={view} />
        <SetLight light={light} span={span} />
        {children}
        <Lens view={view} depth={depth} bokeh={bokeh} />
      </ThreeCanvas>
      {type}
    </AbsoluteFill>
  );
};

/** One line of display type at [size] px with its top left at [x], [y] in the frame's pixels. */
const Display: React.FC<{ text: string; size: number; x: number; y: number; color: string; align?: "left" | "center" }> = ({
  text,
  size,
  x,
  y,
  color,
  align = "left",
}) => (
  <div style={{ position: "absolute", left: align === "center" ? 0 : x, right: align === "center" ? 0 : undefined, top: y, color, textAlign: align, whiteSpace: "pre", ...typeStyle(ROLE.display, size) }}>
    {text}
  </div>
);

const phone = screenSize("phone");

/**
 * The world point on the glass of a device lying at [at], turned [turn] radians about +z, that is [u], [v] across its
 * screen as fractions from the top left (as read off its plate), lifted [above] centimetres off the glass.
 */
function onScreen(at: [number, number, number], turn: number, size: { w: number; h: number }, u: number, v: number, above = 0): [number, number, number] {
  const lx = (u - 0.5) * size.w;
  const ly = (0.5 - v) * size.h;
  const c = Math.cos(turn);
  const s = Math.sin(turn);
  return [at[0] + lx * c - ly * s, at[1] + lx * s + ly * c, at[2] + 0.42 + above];
}

/** [from] moved [by] (in a device's own across, up-the-screen, and up-off-the-glass centimetres) once it is turned [turn]. */
function offset(from: [number, number, number], turn: number, by: [number, number, number]): [number, number, number] {
  const c = Math.cos(turn);
  const s = Math.sin(turn);
  return [from[0] + by[0] * c - by[1] * s, from[1] + by[0] * s + by[1] * c, from[2] + by[2]];
}

/** Morning, "Say it.": the phone in a band of low sun through a window across honed limestone, the words just landed. */
export const StyleframeSay: React.FC = () => {
  const at: [number, number, number] = [1.2, 0.6, 0.41];
  const turn = 0.36;
  const composer = onScreen(at, turn, phone, 0.5, 0.36);
  return (
    <Frame
      view={{
        position: offset(composer, turn, [-5.5, -15.5, 11.5]),
        target: offset(composer, turn, [-3.1, 0.4, 0]),
        focus: composer,
        fov: 24,
      }}
      light={LIGHT.morning}
      span={110}
      depth={4.6}
      bokeh={6}
      type={<Display text="Say it." size={168} x={128} y={118} color={INK.day} />}
    >
      <Stone />
      <Window light={LIGHT.morning} through={[1, 1, 0]} distance={70} panes={{ w: 26, h: 54, mullion: 4.5 }} />
      <Device3D device="phone" plate="plate-phone-440" position={at} rotation={[0, 0, turn]} />
    </Frame>
  );
};

/** "Watch it code.": in close on the glass, the edit's diff lifted off the screen on its own shadow as it streams. */
export const StyleframeCode: React.FC = () => {
  const at: [number, number, number] = [2.2, 1.2, 0.41];
  const turn = 0.36;
  const lift = { top: 0.222, bottom: 0.593, by: 0.45, inset: 0.038 };
  const diff = onScreen(at, turn, phone, 0.5, 0.42, lift.by);
  return (
    <Frame
      view={{
        position: offset(diff, turn, [-6.5, -12, 15]),
        target: offset(diff, turn, [-3.4, 0.6, -0.4]),
        focus: diff,
        fov: 26,
      }}
      light={LIGHT.morning}
      span={110}
      depth={5}
      bokeh={6}
      type={<Display text={"Watch it\ncode."} size={132} x={112} y={110} color={INK.day} />}
    >
      <Stone />
      <Window light={LIGHT.morning} through={[2, 2, 0]} distance={70} panes={{ w: 26, h: 54, mullion: 4.5 }} />
      <Device3D device="phone" plate="plate-phone-640" position={at} rotation={[0, 0, turn]} lift={lift} sun={LIGHT.morning.sun} />
    </Frame>
  );
};

/** Night, "Follow it live.": straight down on the same stone, no sun, lit by the phone's own notification shade. */
export const StyleframeLive: React.FC = () => {
  const at: [number, number, number] = [0.8, -2.2, 0.41];
  const turn = -0.2;
  const card = onScreen(at, turn, phone, 0.5, 0.165);
  return (
    <Frame
      view={{ position: [card[0] - 1, card[1] - 13, 40], target: [card[0] - 1, card[1] - 1.6, 0.8], focus: card, fov: 30 }}
      light={LIGHT.night}
      span={80}
      depth={6}
      bokeh={5}
      type={<Display text={"Follow it\nlive."} size={150} x={96} y={150} color={INK.night} />}
    >
      <Stone tint="#ffffff" />
      <Window light={LIGHT.night} through={[1, -1, 0]} distance={60} panes={{ w: 6.5, h: 60, mullion: 2.2 }} />
      <Device3D device="phone" plate="plate-phone-1480" position={at} rotation={[0, 0, turn]} />
      <ScreenSpill position={at} turn={turn} size={{ w: phone.w + 0.26, h: phone.h + 0.26 }} color="#9aa6c4" strength={0.3} reach={3} />
    </Frame>
  );
};

/**
 * Noon, "Phone. Foldable. Tablet.": the three on the stone in high sun, stepped back in depth on one heading, the same
 * edit open on each, focus on the foldable between them.
 */
export const StyleframeLineup: React.FC = () => {
  const fold = screenSize("foldable");
  const turn = 0.32;
  const tablet: [number, number, number] = [9, 15, 0.31];
  const foldable: [number, number, number] = [-6.5, 1.5, 0.29];
  const front: [number, number, number] = [-16, -11, 0.41];
  const focus = onScreen(foldable, turn, fold, 0.5, 0.5);
  return (
    <Frame
      view={{ position: [-32, -56, 21], target: [-3, 3.5, 0], focus, fov: 21 }}
      light={LIGHT.noon}
      span={90}
      depth={22}
      bokeh={4}
      type={<Display text="Phone. Foldable. Tablet." size={84} x={0} y={92} color={INK.day} align="center" />}
    >
      <Stone extent={480} />
      <Device3D device="tablet" plate="plate-tablet-640" position={tablet} rotation={[0, 0, turn]} />
      <Device3D device="foldable" plate="plate-foldable-640" position={foldable} rotation={[0, 0, turn]} />
      <Device3D device="phone" plate="plate-phone-640" position={front} rotation={[0, 0, turn]} />
    </Frame>
  );
};
