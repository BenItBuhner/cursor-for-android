import { Environment, Lightformer } from "@react-three/drei";
import { useThree } from "@react-three/fiber";
import type React from "react";
import { useMemo } from "react";
import * as THREE from "three";
import { softShadow } from "./Hardware";
import { hash, limestone, limestoneDetail, rng, windowView, withDetail, type View } from "./materials";

/** A time of day: where the sun (or the moon) is and how it burns, the sky's fill, and what the glass and metal see. */
export type Light = {
  sun: { azimuth: number; elevation: number; color: string; intensity: number };
  sky: { color: string; ground: string; intensity: number };
  /** The window as the glass reflects it, and the room's other light sources as cards. */
  view: View;
  window: { position: [number, number, number]; scale: [number, number]; intensity: number };
  cards: { position: [number, number, number]; scale: [number, number, number]; color: string; intensity: number }[];
  background: string;
  /** The colour dust in the beam scatters, and how much. */
  dust: { color: string; strength: number };
};

export type TimeOfDay = "morning" | "noon" | "night" | "next";

export const LIGHT: Record<TimeOfDay, Light> = {
  /** Low, warm sun through the window off the top of the table, a cool skylit shade. */
  morning: {
    sun: { azimuth: 108, elevation: 14, color: "#ffdcb4", intensity: 7.2 },
    sky: { color: "#a9bfdc", ground: "#5e5a55", intensity: 1.05 },
    view: "morning",
    window: { position: [-6, 60, 26], scale: [56, 40], intensity: 2.2 },
    cards: [
      { position: [40, -20, 40], scale: [30, 30, 1], color: "#cfdcf0", intensity: 0.6 },
      { position: [-40, -30, 30], scale: [60, 16, 1], color: "#f3eee6", intensity: 0.35 },
    ],
    background: "#57524c",
    dust: { color: "#ffd8a8", strength: 1 },
  },
  /** High, near-white sun, short crisp shadows, a broad soft top. */
  noon: {
    sun: { azimuth: 135, elevation: 32, color: "#fff2e2", intensity: 4.4 },
    sky: { color: "#c9d6e8", ground: "#8c867c", intensity: 0.8 },
    view: "noon",
    window: { position: [-20, 50, 45], scale: [70, 46], intensity: 2.0 },
    cards: [
      { position: [0, 10, 70], scale: [80, 30, 1], color: "#ffffff", intensity: 0.9 },
      { position: [50, -20, 24], scale: [22, 40, 1], color: "#fff0dc", intensity: 0.7 },
    ],
    background: "#59606b",
    dust: { color: "#fff4e6", strength: 0.6 },
  },
  /** A moonlit window off the other side, almost no fill; the lamp and the screen do the rest. */
  night: {
    sun: { azimuth: -30, elevation: 34, color: "#aebcd8", intensity: 0.75 },
    sky: { color: "#2a3146", ground: "#14161c", intensity: 0.09 },
    view: "night",
    window: { position: [44, -14, 30], scale: [30, 46], intensity: 0.5 },
    cards: [{ position: [-60, 30, 50], scale: [14, 14, 1], color: "#ffe2c2", intensity: 0.9 }],
    background: "#0a0b0f",
    dust: { color: "#b8c4e0", strength: 0.18 },
  },
  /** The next morning: the same window, the sun lower and further round, warmer. */
  next: {
    sun: { azimuth: 72, elevation: 12, color: "#ffd6a6", intensity: 7.6 },
    sky: { color: "#b3c3db", ground: "#5e5a55", intensity: 1.0 },
    view: "next",
    window: { position: [30, 56, 24], scale: [56, 40], intensity: 2.3 },
    cards: [
      { position: [-40, -20, 36], scale: [30, 30, 1], color: "#d3def0", intensity: 0.55 },
      { position: [30, -36, 30], scale: [60, 16, 1], color: "#f3eee6", intensity: 0.35 },
    ],
    background: "#57524c",
    dust: { color: "#ffd2a0", strength: 1 },
  },
};

export const sunDirection = (sun: { azimuth: number; elevation: number }) => {
  const a = (sun.azimuth * Math.PI) / 180;
  const e = (sun.elevation * Math.PI) / 180;
  return new THREE.Vector3(Math.cos(e) * Math.cos(a), Math.cos(e) * Math.sin(a), Math.sin(e));
};

/**
 * [light] on the set: the sun as a shadow-casting key over [span] centimetres about [center], the sky's fill, and the
 * reflections: the window's view (the glass carries it, so it changes with the time of day) and the room's cards.
 */
export const SetLight: React.FC<{ id: TimeOfDay; center: [number, number]; span: number; shadowSize?: number }> = ({ id, center, span, shadowSize = 2048 }) => {
  const light = LIGHT[id];
  const { gl } = useThree();
  gl.toneMappingExposure = 1;
  const dir = sunDirection(light.sun);
  const view = useMemo(() => windowView(light.view), [light.view]);
  const target = useMemo(() => new THREE.Object3D(), []);
  target.position.set(center[0], center[1], 0);
  target.updateMatrixWorld();
  return (
    <>
      <hemisphereLight args={[light.sky.color, light.sky.ground, light.sky.intensity]} />
      <primitive object={target} />
      <directionalLight
        position={[center[0] + dir.x * 150, center[1] + dir.y * 150, dir.z * 150]}
        target={target}
        color={light.sun.color}
        intensity={light.sun.intensity}
        castShadow
        shadow-mapSize={[shadowSize, shadowSize]}
        shadow-bias={-0.0003}
        shadow-normalBias={0.015}
        shadow-radius={2.4}
        shadow-camera-left={-span}
        shadow-camera-right={span}
        shadow-camera-top={span}
        shadow-camera-bottom={-span}
        shadow-camera-near={1}
        shadow-camera-far={320}
      />
      <Environment key={id} resolution={256} frames={1}>
        <color attach="background" args={[light.background]} />
        <Lightformer form="rect" position={light.window.position} scale={[...light.window.scale, 1]} map={view} intensity={light.window.intensity} target={[0, 0, 0]} />
        {light.cards.map((card, i) => (
          <Lightformer key={i} form="rect" position={card.position} scale={card.scale} color={card.color} intensity={card.intensity} target={[0, 0, 0]} />
        ))}
      </Environment>
    </>
  );
};

/** A window: two panes either side of a mullion, a transom across, [w] by [h] each, in the window's own plane. */
export type Panes = { w: number; h: number; mullion: number };

/** A trailing plant on the sill: a stem of heart-shaped leaves hanging into the lower left pane. */
function leafShape(size: number): THREE.Shape {
  const s = new THREE.Shape();
  s.moveTo(0, 0);
  s.bezierCurveTo(size * 0.55, size * 0.1, size * 0.65, size * 0.75, 0, size);
  s.bezierCurveTo(-size * 0.65, size * 0.75, -size * 0.55, size * 0.1, 0, 0);
  return s;
}

const LEAVES = (() => {
  const random = rng(19);
  const out: { x: number; y: number; size: number; angle: number; phase: number }[] = [];
  let x = -14;
  let y = 30;
  for (let i = 0; i < 9; i++) {
    x += 1.6 + random() * 1.4;
    y -= 3.8 + random() * 1.6;
    out.push({ x: x + (random() - 0.5) * 3, y, size: 5 + random() * 3.5, angle: Math.PI + (random() - 0.5) * 1.6, phase: random() * 6 });
  }
  return out;
})();

/**
 * The window between the sun and the table: a wall [distance] centimetres back along the sun's ray through [through],
 * with [panes] cut in it and (if [plant]) the plant hanging in the left one, there only for the shadow it throws (the
 * camera never sees it), so the sun falls across the stone as sharp-edged bands with the leaves in them, and they stir.
 */
export const Window: React.FC<{ id: TimeOfDay; through: [number, number, number]; distance: number; panes: Panes; f: number; roll?: number; plant?: boolean }> = ({
  id,
  through,
  distance,
  panes,
  f,
  roll = 0,
  plant = true,
}) => {
  const frame = useWindowFrame(id, through, distance, roll);
  const wall = useMemo(() => {
    const s = new THREE.Shape();
    s.moveTo(-400, -400);
    s.lineTo(400, -400);
    s.lineTo(400, 400);
    s.lineTo(-400, 400);
    s.lineTo(-400, -400);
    for (const side of [-1, 1]) {
      const x0 = side < 0 ? -panes.mullion / 2 - panes.w : panes.mullion / 2;
      for (const [y0, y1] of [
        [-panes.h / 2, panes.h * 0.12 - panes.mullion * 0.4],
        [panes.h * 0.12 + panes.mullion * 0.4, panes.h / 2],
      ] as const) {
        const hole = new THREE.Path();
        hole.moveTo(x0, y0);
        hole.lineTo(x0 + panes.w, y0);
        hole.lineTo(x0 + panes.w, y1);
        hole.lineTo(x0, y1);
        hole.lineTo(x0, y0);
        s.holes.push(hole);
      }
    }
    return new THREE.ShapeGeometry(s);
  }, [panes]);
  const leaves = useMemo(() => LEAVES.map((l) => new THREE.ShapeGeometry(leafShape(l.size), 12)), []);
  const t = f / 60;
  return (
    <group matrixAutoUpdate={false} matrix={frame.matrix}>
      <mesh geometry={wall} castShadow>
        <meshBasicMaterial colorWrite={false} depthWrite={false} side={THREE.DoubleSide} />
      </mesh>
      {(plant ? LEAVES : []).map((l, i) => (
        <mesh
          key={i}
          geometry={leaves[i]}
          position={[l.x + 0.25 * Math.sin(t * 0.9 + l.phase), l.y, 0.5]}
          rotation={[0, 0, l.angle + 0.04 * Math.sin(t * 1.3 + l.phase)]}
          castShadow
        >
          <meshBasicMaterial colorWrite={false} depthWrite={false} side={THREE.DoubleSide} />
        </mesh>
      ))}
    </group>
  );
};

/** The window wall's frame: its plane faces the sun along the ray through [through], [distance] back, turned [roll]. */
export function useWindowFrame(id: TimeOfDay, through: [number, number, number], distance: number, roll = 0) {
  return useMemo(() => {
    const dir = sunDirection(LIGHT[id].sun);
    const at = new THREE.Vector3(...through).addScaledVector(dir, distance);
    const z = dir.clone();
    const x = new THREE.Vector3(0, 0, 1).cross(z).normalize();
    const y = z.clone().cross(x);
    const m = new THREE.Matrix4().makeBasis(x, y, z).multiply(new THREE.Matrix4().makeRotationZ(roll));
    m.setPosition(at);
    return { matrix: m, at, dir, x: x.applyAxisAngle(z, roll), y: y.applyAxisAngle(z, roll) };
  }, [id, through, distance, roll]);
}

/**
 * Dust in the air over the table, [count] motes drifting through a box [size] about [center]: each lit only where the
 * sun reaches it through the panes (so the beam itself shows, and nothing outside it), dim outside, and drawn larger
 * and fainter the further it is from the plane in focus, as a lens would.
 */
export const Dust: React.FC<{
  id: TimeOfDay;
  through: [number, number, number];
  distance: number;
  panes: Panes;
  roll?: number;
  center: [number, number, number];
  size: [number, number, number];
  count: number;
  f: number;
  focus: number;
  aperture: number;
}> = ({ id, through, distance, panes, roll = 0, center, size, count, f, focus, aperture }) => {
  const frame = useWindowFrame(id, through, distance, roll);
  const { size: viewport, camera } = useThree();
  const geometry = useMemo(() => {
    const g = new THREE.BufferGeometry();
    g.setAttribute("position", new THREE.Float32BufferAttribute(new Float32Array(count * 3), 3));
    g.setAttribute("bright", new THREE.Float32BufferAttribute(new Float32Array(count), 1));
    g.setAttribute("radius", new THREE.Float32BufferAttribute(new Float32Array(count), 1));
    return g;
  }, [count]);
  const material = useMemo(
    () =>
      new THREE.ShaderMaterial({
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
        uniforms: { color: { value: new THREE.Color() }, scale: { value: 1 }, focus: { value: 30 }, aperture: { value: 0.3 } },
        vertexShader: `
          attribute float bright; attribute float radius;
          uniform float scale; uniform float focus; uniform float aperture;
          varying float vAlpha;
          void main() {
            vec4 mv = modelViewMatrix * vec4(position, 1.0);
            float z = -mv.z;
            float core = radius * scale / z;
            float coc = aperture * scale * abs(z - focus) / (z * focus);
            float px = max(1.2, max(core, coc));
            vAlpha = bright * (core * core + 1.0) / (px * px + 1.0);
            gl_PointSize = px * 2.0;
            gl_Position = projectionMatrix * mv;
          }`,
        fragmentShader: `
          uniform vec3 color; varying float vAlpha;
          void main() {
            float d = length(gl_PointCoord - 0.5) * 2.0;
            float disc = smoothstep(1.0, 0.7, d);
            gl_FragColor = vec4(color * vAlpha * disc, 1.0);
          }`,
      }),
    [],
  );
  const light = LIGHT[id];
  const perspective = camera as THREE.PerspectiveCamera;
  material.uniforms.color!.value.set(light.dust.color).multiplyScalar(light.dust.strength * 0.9);
  material.uniforms.scale!.value = viewport.height / (2 * Math.tan((perspective.fov * Math.PI) / 360));
  material.uniforms.focus!.value = focus;
  material.uniforms.aperture!.value = aperture;
  const pos = geometry.attributes.position as THREE.BufferAttribute;
  const bright = geometry.attributes.bright as THREE.BufferAttribute;
  const radius = geometry.attributes.radius as THREE.BufferAttribute;
  const t = f / 60;
  const p = new THREE.Vector3();
  const local = new THREE.Vector3();
  for (let i = 0; i < count; i++) {
    const r = (k: number) => hash(i, k, 404);
    const wrap = (v: number, s: number) => ((((v / s + 0.5) % 1) + 1) % 1 - 0.5) * s;
    p.set(
      center[0] + wrap((r(1) - 0.5) * size[0] + t * (0.25 + 0.5 * r(4)) + 0.3 * Math.sin(t * (0.3 + r(7)) + 9 * r(8)), size[0]),
      center[1] + wrap((r(2) - 0.5) * size[1] + t * (0.15 * (r(5) - 0.5)) + 0.3 * Math.sin(t * (0.25 + r(9)) + 9 * r(10)), size[1]),
      Math.max(0.3, center[2] + wrap((r(3) - 0.5) * size[2] - t * (0.08 + 0.12 * r(6)), size[2])),
    );
    pos.setXYZ(i, p.x, p.y, p.z);
    const s = local.copy(frame.at).sub(p).dot(frame.dir);
    local.copy(p).addScaledVector(frame.dir, s).sub(frame.at);
    const lx = local.dot(frame.x);
    const ly = local.dot(frame.y);
    const ax = Math.abs(lx);
    const soft = (v: number) => Math.max(0, Math.min(1, v / 0.4 + 0.5));
    const inPane = soft(ax - panes.mullion / 2) * soft(panes.mullion / 2 + panes.w - ax) * soft(ly + panes.h / 2) * soft(panes.h / 2 - ly);
    bright.setX(i, inPane * (0.3 + 0.7 * r(11)) ** 2);
    radius.setX(i, 0.006 + 0.014 * r(12) ** 2);
  }
  pos.needsUpdate = true;
  bright.needsUpdate = true;
  radius.needsUpdate = true;
  return <points geometry={geometry} material={material} frustumCulled={false} renderOrder={5} />;
};

/** The macro tile the stone's colour and roughness repeat on, and the fine one its pits and grain repeat on, in cm. */
const STONE_TILE = 120;
const GRAIN_TILE = 7;

/** The table: honed limestone [extent] centimetres across, taking the shadows. */
export const Stone: React.FC<{ extent?: number; bump?: number }> = ({ extent = 720, bump = 0.5 }) => {
  const material = useMemo(() => {
    const macro = limestone(2048);
    const detail = limestoneDetail(1024);
    for (const t of [macro.color, macro.roughness]) t.repeat.set(extent / STONE_TILE, extent / STONE_TILE);
    detail.bump.repeat.set(extent / GRAIN_TILE, extent / GRAIN_TILE);
    const m = new THREE.MeshStandardMaterial({ map: macro.color, roughnessMap: macro.roughness, roughness: 1, bumpMap: detail.bump, bumpScale: bump, metalness: 0, envMapIntensity: 0.55 });
    return withDetail(m, detail.color, STONE_TILE / GRAIN_TILE);
  }, [extent, bump]);
  return (
    <mesh receiveShadow material={material}>
      <planeGeometry args={[extent, extent]} />
    </mesh>
  );
};

/**
 * The lamp at night, off the left of the table: a warm pool that comes up over a few frames as the bulb does, with a
 * soft shadow of whatever stands in it.
 */
export const Lamp: React.FC<{ on: number; position: [number, number, number]; target: [number, number, number] }> = ({ on, position, target }) => {
  const object = useMemo(() => new THREE.Object3D(), []);
  object.position.set(...target);
  object.updateMatrixWorld();
  if (on <= 0) return null;
  return (
    <>
      <primitive object={object} />
      <spotLight
        position={position}
        target={object}
        color="#ffe6cc"
        intensity={on * 2800}
        distance={0}
        decay={2}
        angle={0.5}
        penumbra={1}
        castShadow
        shadow-mapSize={[1024, 1024]}
        shadow-bias={-0.0004}
        shadow-radius={6}
      />
    </>
  );
};

/**
 * The light a face-up screen at [pose]'s centre lets onto the stone: a soft halo hugging the device's outline [size],
 * fading out over [reach] centimetres, in the screen's [color].
 */
export const ScreenSpill: React.FC<{ x: number; y: number; turn: number; size: { w: number; h: number }; color: string; strength: number; reach: number }> = ({
  x,
  y,
  turn,
  size,
  color,
  strength,
  reach,
}) => {
  const glow = useMemo(() => softShadow(size.w, size.h, reach, "#ffffff", 0.9), [size.w, size.h, reach]);
  if (strength <= 0) return null;
  return (
    <mesh position={[x, y, 0.015]} rotation={[0, 0, turn]}>
      <planeGeometry args={[size.w + 2 * reach, size.h + 2 * reach]} />
      <meshBasicMaterial map={glow} color={color} transparent opacity={strength} blending={THREE.AdditiveBlending} depthWrite={false} toneMapped={false} />
    </mesh>
  );
};

/**
 * The room past the table's far end, as a lens focused a hand's width away sees it: a wall all round in the light the
 * window lets in, a little darker up high, never sharp enough to read as anything but somewhere.
 */
export const Backdrop: React.FC<{ id: TimeOfDay }> = ({ id }) => {
  const texture = useMemo(() => {
    const c = document.createElement("canvas");
    c.width = 4;
    c.height = 256;
    const ctx = c.getContext("2d")!;
    const g = ctx.createLinearGradient(0, 0, 0, 256);
    const [top, bottom] = BACKDROP[id];
    g.addColorStop(0, top);
    g.addColorStop(1, bottom);
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, 4, 256);
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }, [id]);
  return (
    <mesh position={[0, 0, 120]} rotation={[Math.PI / 2, 0, 0]}>
      <cylinderGeometry args={[340, 340, 240, 64, 1, true]} />
      <meshBasicMaterial map={texture} side={THREE.BackSide} toneMapped />
    </mesh>
  );
};

const BACKDROP: Record<TimeOfDay, [string, string]> = {
  morning: ["#4a443e", "#7d746a"],
  noon: ["#6b6863", "#a29b91"],
  night: ["#08090c", "#15161a"],
  next: ["#4c443c", "#83776a"],
};
