import { RoundedBox } from "@react-three/drei";
import type React from "react";
import { useMemo } from "react";
import * as THREE from "three";
import { takes, type DeviceId } from "../takes";
import { smudges } from "./materials";

/**
 * The three devices as hardware, in centimetres, lying screen up along +z with the top of the screen along +y: a
 * machined frame swept around the outline (a polished chamfer either side of a satin, faintly crowned band), the cover
 * glass with its rolled edge, the take on the screen under it, and what a real body has: the buttons in their slots,
 * the ports, the antenna lines, the punch-hole camera, the earpiece, the rear camera visor the phone rests on.
 *
 * Phone and foldable are ported from Bennett's visual-engine Galaxy S26 Ultra and Z Fold8 Ultra (open). The tablet is a
 * Galaxy Tab S10 Ultra body with the S26 Ultra's corner radius and graphite finishes — visual-engine has no Tab SKU.
 */

type Spec = {
  /** The screen across, and the glass's reach past it, the corner radius and the depth of the body. */
  screen: number;
  bezel: { x: number; top: number; bottom: number };
  radius: number;
  depth: number;
  /** The frame's chamfers, and how far the glass sits in from the frame's outer edge. */
  chamfer: number;
  lip: number;
  crown: number;
};

export const SPEC: Record<DeviceId, Spec> = {
  // Galaxy S26 Ultra: 78.1 × 163.6 × 7.9 mm, R 6, 6.9" 73.4 × 159.1 mm active. Screen height follows the take.
  phone: { screen: 7.34, bezel: { x: 0.235, top: 0.225, bottom: 0.225 }, radius: 0.6, depth: 0.79, chamfer: 0.05, lip: 0.11, crown: 0.008 },
  // Galaxy Z Fold8 Ultra, open: 143.2 × 158.4 × 4.1 mm, inner 137.0 × 152.0 mm, R 4, crease 2.4 mm.
  foldable: { screen: 13.7, bezel: { x: 0.31, top: 0.815, bottom: 0.815 }, radius: 0.4, depth: 0.41, chamfer: 0.06, lip: 0.12, crown: 0.006 },
  // Galaxy Tab S10 Ultra, landscape: 326.4 × 208.6 × 5.4 mm, 14.6" 16:10. Corners and materials match the S26 Ultra.
  tablet: { screen: 31.45, bezel: { x: 0.595, top: 0.602, bottom: 0.602 }, radius: 0.6, depth: 0.54, chamfer: 0.05, lip: 0.11, crown: 0.008 },
};

export const screenSize = (device: DeviceId) => {
  const t = takes[device];
  const w = SPEC[device].screen;
  return { w, h: (w * t.height) / t.width };
};

export function outerSize(device: DeviceId) {
  const s = SPEC[device];
  const { w, h } = screenSize(device);
  return { w: w + 2 * s.bezel.x, h: h + s.bezel.top + s.bezel.bottom, screenY: (s.bezel.bottom - s.bezel.top) / 2 };
}

/**
 * The S26 Ultra's rear island, from visual-engine: a 21 × 57.7 mm stadium 1.55 mm proud, cameras stacked to 4.05 mm,
 * 4.55 mm in from the left and 4.15 from the top. The phone rests on those stacks and its bottom edge.
 */
const VISOR = { stand: 0.405, fromTop: 3.3, height: 5.77, width: 2.1, left: 0.455, step: 0.155 };

/** The phone rests on its visor and its bottom edge, so it lies a fraction of a degree off the table, top up. */
export function restTilt(device: DeviceId): number {
  if (device !== "phone") return 0;
  const { h } = outerSize(device);
  return Math.atan2(VISOR.stand, h - VISOR.fromTop);
}

/**
 * Where a device lies: its centre at [x], [y] on the table, turned [turn] radians about +z. The matrix takes its own
 * coordinates (origin at the body's centre, on the back, the glass at z = depth) to the world's.
 */
export type Pose = { device: DeviceId; x: number; y: number; turn: number };

export function poseMatrix(pose: Pose): THREE.Matrix4 {
  const { h } = outerSize(pose.device);
  const tilt = restTilt(pose.device);
  const m = new THREE.Matrix4().makeTranslation(pose.x, pose.y, pose.device === "phone" ? 0 : 0.02);
  m.multiply(new THREE.Matrix4().makeRotationZ(pose.turn));
  m.multiply(new THREE.Matrix4().makeTranslation(0, -h / 2, 0));
  m.multiply(new THREE.Matrix4().makeRotationX(tilt));
  m.multiply(new THREE.Matrix4().makeTranslation(0, h / 2, 0));
  return m;
}

/** The point [by] in a device's own centimetres, in the world. */
export function toWorld(pose: Pose, by: [number, number, number]): [number, number, number] {
  const v = new THREE.Vector3(...by).applyMatrix4(poseMatrix(pose));
  return [v.x, v.y, v.z];
}

/** The point on a device's glass [u], [v] across its screen from the top left (as fractions), [above] off it. */
export function onGlass(pose: Pose, u: number, v: number, above = 0): [number, number, number] {
  const { w, h } = screenSize(pose.device);
  const { screenY } = outerSize(pose.device);
  return toWorld(pose, [(u - 0.5) * w, screenY + (0.5 - v) * h, SPEC[pose.device].depth + 0.006 + above]);
}

type OutlinePoint = { x: number; y: number; nx: number; ny: number; s: number };

/** A rounded rectangle [w] by [h], corner radius [r], counter-clockwise from the middle of the right side. */
function outline(w: number, h: number, r: number, perCorner = 18): OutlinePoint[] {
  const pts: OutlinePoint[] = [];
  const corners: [number, number, number][] = [
    [w / 2 - r, h / 2 - r, 0],
    [-w / 2 + r, h / 2 - r, Math.PI / 2],
    [-w / 2 + r, -h / 2 + r, Math.PI],
    [w / 2 - r, -h / 2 + r, (3 * Math.PI) / 2],
  ];
  for (const [cx, cy, a0] of corners) {
    for (let i = 0; i <= perCorner; i++) {
      const a = a0 + (i / perCorner) * (Math.PI / 2);
      pts.push({ x: cx + r * Math.cos(a), y: cy + r * Math.sin(a), nx: Math.cos(a), ny: Math.sin(a), s: 0 });
    }
  }
  let s = 0;
  for (let i = 0; i < pts.length; i++) {
    if (i) s += Math.hypot(pts[i]!.x - pts[i - 1]!.x, pts[i]!.y - pts[i - 1]!.y);
    pts[i]!.s = s;
  }
  return pts;
}

/** A run of the frame's cross-section: points out from the outline ([d]) and up ([z]), in one material. */
type Run = { pts: [number, number][]; material: number };

/**
 * The frame: each run of [profile] swept around [path], its normals the outline's turned by the profile's own, so the
 * band's crown and the chamfers catch light as machined metal does; u runs along the outline (the anodizing's brushing).
 */
function sweep(path: OutlinePoint[], profile: Run[]): THREE.BufferGeometry {
  const pos: number[] = [];
  const nor: number[] = [];
  const uv: number[] = [];
  const idx: number[] = [];
  const geometry = new THREE.BufferGeometry();
  for (const run of profile) {
    const start = idx.length;
    const base = pos.length / 3;
    const n = run.pts.length;
    const normals = run.pts.map((_, j) => {
      const a = run.pts[Math.max(0, j - 1)]!;
      const b = run.pts[Math.min(n - 1, j + 1)]!;
      const td = b[0] - a[0];
      const tz = b[1] - a[1];
      const len = Math.hypot(td, tz) || 1;
      return [tz / len, -td / len] as const;
    });
    let along = 0;
    const vs = run.pts.map((p, j) => (j ? (along += Math.hypot(p[0] - run.pts[j - 1]![0], p[1] - run.pts[j - 1]![1])) : 0));
    for (const o of path) {
      run.pts.forEach(([d, z], j) => {
        const [nd, nz] = normals[j]!;
        pos.push(o.x + o.nx * d, o.y + o.ny * d, z);
        nor.push(o.nx * nd, o.ny * nd, nz);
        uv.push(o.s, vs[j]!);
      });
    }
    const m = path.length;
    for (let i = 0; i < m; i++) {
      const i2 = (i + 1) % m;
      for (let j = 0; j < n - 1; j++) {
        const a = base + i * n + j;
        const b = base + i2 * n + j;
        const c = base + i2 * n + j + 1;
        const d = base + i * n + j + 1;
        idx.push(a, b, c, a, c, d);
      }
    }
    geometry.addGroup(start, idx.length - start, run.material);
  }
  geometry.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
  geometry.setAttribute("normal", new THREE.Float32BufferAttribute(nor, 3));
  geometry.setAttribute("uv", new THREE.Float32BufferAttribute(uv, 2));
  geometry.setIndex(idx);
  return geometry;
}

function roundedShape(w: number, h: number, r: number): THREE.Shape {
  const s = new THREE.Shape();
  const x = -w / 2;
  const y = -h / 2;
  r = Math.min(r, w / 2, h / 2);
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.absarc(x + w - r, y + r, r, -Math.PI / 2, 0, false);
  s.lineTo(x + w, y + h - r);
  s.absarc(x + w - r, y + h - r, r, 0, Math.PI / 2, false);
  s.lineTo(x + r, y + h);
  s.absarc(x + r, y + h - r, r, Math.PI / 2, Math.PI, false);
  s.lineTo(x, y + r);
  s.absarc(x + r, y + r, r, Math.PI, (3 * Math.PI) / 2, false);
  return s;
}

/** A flat rounded rectangle with UVs running 0 to 1 across it. */
export function roundedRect(w: number, h: number, r: number): THREE.ShapeGeometry {
  const g = new THREE.ShapeGeometry(roundedShape(w, h, r), 24);
  const uv = g.attributes.uv!;
  const pos = g.attributes.position!;
  for (let i = 0; i < uv.count; i++) uv.setXY(i, (pos.getX(i) + w / 2) / w, (pos.getY(i) + h / 2) / h);
  return g;
}

/**
 * The cover glass's reflecting face: a grid [w] by [h] with UVs 0 to 1, its corners left to an alpha map, and for the
 * foldable a dip of a third of a millimetre along the crease, so reflections bend across it as they do on the real one.
 */
function coatGeometry(w: number, h: number, crease: boolean): THREE.BufferGeometry {
  const g = new THREE.PlaneGeometry(w, h, crease ? 96 : 1, 1);
  if (crease) {
    const pos = g.attributes.position!;
    for (let i = 0; i < pos.count; i++) pos.setZ(i, -0.03 * Math.exp(-((pos.getX(i) / 0.24) ** 2)));
    g.computeVertexNormals();
  }
  return g;
}

function roundedMask(w: number, h: number, r: number): THREE.CanvasTexture {
  const scale = 512 / Math.max(w, h);
  const c = document.createElement("canvas");
  c.width = Math.round(w * scale);
  c.height = Math.round(h * scale);
  const ctx = c.getContext("2d")!;
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, c.width, c.height);
  ctx.fillStyle = "#fff";
  ctx.beginPath();
  ctx.roundRect(0, 0, c.width, c.height, r * scale);
  ctx.fill();
  return new THREE.CanvasTexture(c);
}

/** A soft shadow under a [w] by [h] card [blur] centimetres off a surface, on a plane [blur] wider each side. */
export function softShadow(w: number, h: number, blur: number, fill = "rgba(0,0,0,0.9)", radius = 0.25): THREE.CanvasTexture {
  const scale = Math.min(120, 1800 / (Math.max(w, h) + 2 * blur));
  const cw = Math.round((w + 2 * blur) * scale);
  const ch = Math.round((h + 2 * blur) * scale);
  const c = document.createElement("canvas");
  c.width = cw;
  c.height = ch;
  const ctx = c.getContext("2d")!;
  ctx.filter = `blur(${blur * scale * 0.45}px)`;
  ctx.fillStyle = fill;
  ctx.beginPath();
  ctx.roundRect(blur * scale, blur * scale, w * scale, h * scale, radius * scale);
  ctx.fill();
  return new THREE.CanvasTexture(c);
}

function swatch(color: string): THREE.CanvasTexture {
  const c = document.createElement("canvas");
  c.width = c.height = 4;
  const ctx = c.getContext("2d")!;
  ctx.fillStyle = color;
  ctx.fillRect(0, 0, 4, 4);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

/** The finishes: anodized aluminium in graphite, its diamond-cut chamfers, the black glass, the dark plastics. */
function useFinishes() {
  return useMemo(() => {
    // Graphite from visual-engine's Fold8 Ultra / S26 family: Armor Aluminum frame, satin glass back, polished lips.
    const band = new THREE.MeshPhysicalMaterial({ color: "#4f5257", metalness: 1, roughness: 0.34, anisotropy: 0.65, envMapIntensity: 1.15 });
    const chamfer = new THREE.MeshPhysicalMaterial({ color: "#8a8d92", metalness: 1, roughness: 0.1, envMapIntensity: 1.35 });
    const gasket = new THREE.MeshStandardMaterial({ color: "#0b0b0c", roughness: 0.6 });
    const plastic = new THREE.MeshStandardMaterial({ color: "#26272a", roughness: 0.55, metalness: 0 });
    const hole = new THREE.MeshBasicMaterial({ color: "#020202" });
    const back = new THREE.MeshPhysicalMaterial({ color: "#5f6367", roughness: 0.52, metalness: 0, clearcoat: 0.35, clearcoatRoughness: 0.45 });
    const island = new THREE.MeshPhysicalMaterial({ color: "#6d7176", metalness: 0.35, roughness: 0.28, clearcoat: 0.45, clearcoatRoughness: 0.35, envMapIntensity: 1.05 });
    const glass = new THREE.MeshPhysicalMaterial({ color: "#020203", metalness: 0, roughness: 0.035, envMapIntensity: 1 });
    const lens = new THREE.MeshPhysicalMaterial({ color: "#05060a", metalness: 0, roughness: 0.02, clearcoat: 1, clearcoatRoughness: 0, iridescence: 0.55, iridescenceIOR: 1.6, envMapIntensity: 1.5 });
    const ring = new THREE.MeshPhysicalMaterial({ color: "#3a3c40", metalness: 1, roughness: 0.18, envMapIntensity: 1.2 });
    return { band, chamfer, gasket, plastic, hole, back, island, glass, lens, ring };
  }, []);
}

export type Lift = { top: number; bottom: number; by: number; inset: number };

/**
 * [device] at [pose] with [screen] on its glass, lit to [glow] of its brightness (0 is off: black glass). [lift], if
 * given, raises a band of the screen off the glass by [lift.by] centimetres, its shadow thrown away from [sun].
 */
export const Hardware: React.FC<{
  pose: Pose;
  screen: THREE.Texture;
  glow: number;
  lift?: Lift | null;
  sun?: { azimuth: number; elevation: number } | null;
  smudgeSeed?: number;
}> = ({ pose, screen, glow, lift, sun, smudgeSeed = 3 }) => {
  const device = pose.device;
  const spec = SPEC[device];
  const { w, h } = screenSize(device);
  const outer = outerSize(device);
  const D = spec.depth;
  const f = useFinishes();
  const matrix = useMemo(() => poseMatrix(pose), [pose]);
  const parts = useMemo(() => {
    const path = outline(outer.w, outer.h, spec.radius);
    const c = spec.chamfer;
    const band: [number, number][] = [];
    for (let i = 0; i <= 8; i++) {
      const t = i / 8;
      band.push([spec.crown * Math.sin(Math.PI * t), c + t * (D - 2 * c - 0.01)]);
    }
    const frame = sweep(path, [
      { pts: [[-c, 0.0], [0, c]], material: 1 },
      { pts: band, material: 0 },
      { pts: [[0, D - c - 0.01], [-c, D - 0.01]], material: 1 },
      { pts: [[-c, D - 0.01], [-spec.lip, D - 0.01]], material: 2 },
    ]);
    const inset = spec.lip;
    const glassShape = roundedShape(outer.w - 2 * inset - 0.03, outer.h - 2 * inset - 0.03, spec.radius - inset - 0.015);
    const glass = new THREE.ExtrudeGeometry(glassShape, { depth: 0.006, bevelEnabled: true, bevelThickness: 0.012, bevelSize: 0.015, bevelSegments: 4, curveSegments: 24 });
    const backCap = new THREE.ShapeGeometry(roundedShape(outer.w - 2 * c, outer.h - 2 * c, spec.radius - c), 24);
    const screenR = Math.max(0.05, spec.radius - Math.max(spec.bezel.x, spec.bezel.top));
    return {
      frame,
      glass,
      backCap,
      screen: roundedRect(w, h, screenR),
      coat: coatGeometry(w, h, device === "foldable"),
      mask: roundedMask(w, h, screenR),
      contact: softShadow(outer.w, outer.h, 0.8, "rgba(0,0,0,0.85)", spec.radius),
    };
  }, [device, outer.w, outer.h, spec, w, h, D]);
  const smudge = useMemo(() => smudges(smudgeSeed, w / h), [smudgeSeed, w, h]);
  const glowColor = useMemo(() => new THREE.Color(glow, glow, glow), [glow]);
  const glassTop = D;
  return (
    <>
      <mesh position={[pose.x, pose.y, 0.02]} rotation={[0, 0, pose.turn]} renderOrder={-1}>
        <planeGeometry args={[outer.w + 1.6, outer.h + 1.6]} />
        <meshBasicMaterial map={parts.contact} transparent opacity={0.6} toneMapped={false} depthWrite={false} />
      </mesh>
    <group matrixAutoUpdate={false} matrix={matrix}>
      <mesh geometry={parts.frame} material={[f.band, f.chamfer, f.gasket]} castShadow receiveShadow />
      <mesh geometry={parts.backCap} material={f.back} rotation={[Math.PI, 0, 0]} position={[0, 0, 0.001]} castShadow />
      <mesh geometry={parts.glass} material={f.glass} position={[0, 0, glassTop - 0.018]} castShadow receiveShadow />
      <mesh geometry={parts.screen} position={[0, outer.screenY, glassTop + 0.003]}>
        <meshBasicMaterial map={screen} toneMapped={false} color={glowColor} polygonOffset polygonOffsetFactor={-1} polygonOffsetUnits={-4} />
      </mesh>
      {lift ? <Lifted device={device} screen={screen} glowColor={glowColor} lift={lift} sun={sun ?? null} turn={pose.turn} top={glassTop} /> : null}
      <Details device={device} f={f} top={glassTop} />
      <mesh geometry={parts.coat} position={[0, outer.screenY, glassTop + 0.007]} renderOrder={2}>
        <meshStandardMaterial
          color="#000"
          metalness={0}
          roughness={1}
          roughnessMap={smudge}
          alphaMap={parts.mask}
          blending={THREE.AdditiveBlending}
          transparent
          depthWrite={false}
          envMapIntensity={0.18}
          polygonOffset
          polygonOffsetFactor={-2}
          polygonOffsetUnits={-8}
        />
      </mesh>
    </group>
    </>
  );
};

/** A camera well: the satin ring in the island metal, a black cylinder, the coated glass inside. */
const Stack: React.FC<{
  x: number;
  y: number;
  z: number;
  ringR: number;
  cylR: number;
  cylH: number;
  glassR: number;
  f: ReturnType<typeof useFinishes>;
}> = ({ x, y, z, ringR, cylR, cylH, glassR, f }) => (
  <group position={[x, y, z]}>
    <mesh material={f.ring} rotation={[Math.PI / 2, 0, 0]} position={[0, 0, -0.02]} castShadow>
      <cylinderGeometry args={[ringR, ringR, 0.075, 48]} />
    </mesh>
    <mesh material={f.hole} rotation={[Math.PI / 2, 0, 0]} position={[0, 0, -cylH / 2]} castShadow>
      <cylinderGeometry args={[cylR, cylR, cylH, 48]} />
    </mesh>
    <mesh material={f.lens} position={[0, 0, -cylH + 0.012]}>
      <circleGeometry args={[glassR, 48]} />
    </mesh>
  </group>
);

/** What's cut into the body and printed on its glass. */
const Details: React.FC<{ device: DeviceId; f: ReturnType<typeof useFinishes>; top: number }> = ({ device, f, top }) => {
  const spec = SPEC[device];
  const outer = outerSize(device);
  const D = spec.depth;
  const mid = D / 2;
  const side = outer.w / 2;
  const end = outer.h / 2;
  const ring = useMemo(() => new THREE.RingGeometry(0.075, 0.13, 48), []);
  const disc = (r: number) => <circleGeometry args={[r, 48]} />;
  const button = (y: number, length: number, x = side) => (
    <group key={`${x}-${y}`}>
      <mesh position={[x - Math.sign(x) * 0.015, y, mid]} material={f.hole}>
        <boxGeometry args={[0.05, length + 0.06, 0.22]} />
      </mesh>
      <RoundedBox args={[0.1, length, 0.18]} radius={0.04} smoothness={4} position={[x + Math.sign(x) * 0.025, y, mid]} castShadow>
        <meshPhysicalMaterial color="#3a3c40" metalness={1} roughness={0.17} anisotropy={0.5} envMapIntensity={0.55} />
      </RoundedBox>
    </group>
  );
  const antenna = (x: number, y: number, along: "x" | "y") => (
    <mesh key={`a${x}${y}`} position={[x, y, mid]} material={f.plastic}>
      <boxGeometry args={along === "y" ? [0.012, 0.07, D - 0.14] : [0.07, 0.012, D - 0.14]} />
    </mesh>
  );
  const camera = (x: number, y: number, scale = 1) => (
    <group position={[x, y, top + 0.0045]} scale={scale}>
      <mesh material={f.hole}>{disc(0.18)}</mesh>
      <mesh geometry={ring} position={[0, 0, 0.0004]}>
        <meshPhysicalMaterial color="#191a1e" metalness={1} roughness={0.25} />
      </mesh>
      <mesh material={f.lens} position={[0, 0, 0.0008]}>
        {disc(0.08)}
      </mesh>
    </group>
  );
  if (device === "phone") {
    const ix = -side + VISOR.left + VISOR.width / 2;
    const iy = end - VISOR.fromTop;
    return (
      <>
        {button(end - 4.0, 2.02)}
        {button(end - 6.63, 1.35)}
        {[antenna(side, end - 2.1, "y"), antenna(side, -end + 2.1, "y"), antenna(-side, end - 2.1, "y"), antenna(-side, -end + 2.1, "y"), antenna(-1.6, end, "x"), antenna(1.6, -end, "x")]}
        {camera(0, end - 0.645, 0.85)}
        <mesh position={[0, -end - 0.002, mid]} rotation={[Math.PI / 2, 0, 0]} material={f.hole}>
          <shapeGeometry args={[roundedShape(0.85, 0.28, 0.13), 12]} />
        </mesh>
        <mesh position={[0, -end - 0.004, mid]} rotation={[Math.PI / 2, 0, 0]} material={f.plastic}>
          <planeGeometry args={[0.56, 0.07]} />
        </mesh>
        {Array.from({ length: 7 }, (_, i) => (
          <mesh key={`s${i}`} position={[1.02 + (i * 1.43) / 6, -end - 0.002, mid]} rotation={[Math.PI / 2, 0, 0]} material={f.hole}>
            {disc(0.06)}
          </mesh>
        ))}
        <mesh position={[-2.2, -end - 0.002, mid]} rotation={[Math.PI / 2, 0, 0]} material={f.hole}>
          {disc(0.04)}
        </mesh>
        <RoundedBox args={[VISOR.width, VISOR.height, VISOR.step]} radius={VISOR.width / 2 - 0.02} smoothness={6} position={[ix, iy, -VISOR.step / 2]} material={f.island} castShadow />
        <Stack x={-side + 1.505} y={end - 1.465} z={-VISOR.step} ringR={0.875} cylR={0.67} cylH={0.25} glassR={0.615} f={f} />
        <Stack x={-side + 1.505} y={end - 3.3} z={-VISOR.step} ringR={0.875} cylR={0.67} cylH={0.25} glassR={0.615} f={f} />
        <Stack x={-side + 1.505} y={end - 5.135} z={-VISOR.step} ringR={0.875} cylR={0.67} cylH={0.25} glassR={0.615} f={f} />
        <mesh position={[-side + 3.28, end - 1.48, -VISOR.step - 0.02]} material={f.hole} rotation={[Math.PI / 2, 0, 0]}>
          <cylinderGeometry args={[0.47, 0.47, 0.155, 32]} />
        </mesh>
        <mesh position={[-side + 3.28, end - 3.3, -VISOR.step - 0.02]} material={f.hole} rotation={[Math.PI / 2, 0, 0]}>
          <cylinderGeometry args={[0.47, 0.47, 0.155, 32]} />
        </mesh>
        <mesh position={[-side + 3.28, end - 2.39, -VISOR.step]} material={f.lens}>
          {disc(0.2)}
        </mesh>
      </>
    );
  }
  if (device === "foldable") {
    return (
      <>
        {button(end - 4.0, 1.7, -side)}
        {button(end - 6.2, 1.3, -side)}
        {[end, -end].map((y) => (
          <mesh key={`h${y}`} position={[0, y, mid]} material={f.hole}>
            <boxGeometry args={[0.5, 0.14, D - 0.04]} />
          </mesh>
        ))}
        {[antenna(side, end - 1.8, "y"), antenna(-side, -end + 1.8, "y"), antenna(-3.4, end, "x"), antenna(3.4, -end, "x")]}
        {camera(side - 3.28, end - 0.52, 0.7)}
        <mesh position={[0, -end - 0.002, mid]} rotation={[Math.PI / 2, 0, 0]} material={f.hole}>
          <shapeGeometry args={[roundedShape(0.89, 0.32, 0.12), 12]} />
        </mesh>
      </>
    );
  }
  const topButton = (x: number, length: number) => (
    <group key={`t${x}`}>
      <mesh position={[x, end - 0.015, mid]} material={f.hole}>
        <boxGeometry args={[length + 0.06, 0.05, 0.22]} />
      </mesh>
      <RoundedBox args={[length, 0.1, 0.18]} radius={0.04} smoothness={4} position={[x, end + 0.02, mid]} castShadow>
        <meshPhysicalMaterial color="#3a3c40" metalness={1} roughness={0.17} anisotropy={0.5} envMapIntensity={0.55} />
      </RoundedBox>
    </group>
  );
  const speaker = (x: number, y: number) =>
    Array.from({ length: 6 }, (_, i) => (
      <mesh key={`p${x}${y}${i}`} position={[x, y + (i - 2.5) * 0.16, mid]} rotation={[0, x < 0 ? -Math.PI / 2 : Math.PI / 2, 0]} material={f.hole}>
        {disc(0.045)}
      </mesh>
    ));
  return (
    <>
      {topButton(-side + 3.4, 1.5)}
      {topButton(side - 3.8, 2.4)}
      {[antenna(-side, 4, "y"), antenna(side, -4, "y"), antenna(-8, end, "x")]}
      {camera(0, end - spec.bezel.top / 2, 0.8)}
      {[speaker(-side - 0.002, 6.2), speaker(-side - 0.002, -6.2), speaker(side + 0.002, 6.2), speaker(side + 0.002, -6.2)]}
    </>
  );
};

/** The app's background, left showing where a lifted band of the screen came away from. */
const SCREEN_GROUND = "#141414";
/** A lifted band steps up in tone, as Material's elevated surfaces do, and its edge shows as a hairline. */
const LIFT_TONE = 1.32;
const LIFT_RIM = "#4a4a4c";

const Lifted: React.FC<{
  device: DeviceId;
  screen: THREE.Texture;
  glowColor: THREE.Color;
  lift: Lift;
  sun: { azimuth: number; elevation: number } | null;
  turn: number;
  top: number;
}> = ({ device, screen, glowColor, lift, sun, turn, top }) => {
  const { w, h } = screenSize(device);
  const { screenY } = outerSize(device);
  const g = useMemo(() => {
    const lw = w * (1 - 2 * lift.inset);
    const lh = h * (lift.bottom - lift.top);
    const card = roundedRect(lw, lh, 0.18);
    const uv = card.attributes.uv!;
    for (let i = 0; i < uv.count; i++) uv.setXY(i, lift.inset + uv.getX(i) * (1 - 2 * lift.inset), 1 - lift.bottom + uv.getY(i) * (lift.bottom - lift.top));
    return { card, recess: roundedRect(lw, lh, 0.18), rim: roundedRect(lw + 0.05, lh + 0.05, 0.2), lw, lh, cy: screenY + h / 2 - (h * (lift.top + lift.bottom)) / 2 };
  }, [lift.inset, lift.top, lift.bottom, w, h, screenY]);
  const shadow = useMemo(() => softShadow(g.lw, g.lh, 0.2), [g.lw, g.lh]);
  const ground = useMemo(() => swatch(SCREEN_GROUND), []);
  const rim = useMemo(() => swatch(LIFT_RIM), []);
  // Below this the card is back on the glass: drawn there, it would sink under its own recess.
  if (lift.by <= 0.012) return null;
  const by = lift.by;
  let cast: [number, number] = [0.08 * by, -0.25 * by];
  if (sun) {
    const a = (sun.azimuth * Math.PI) / 180 - turn;
    const reach = by / Math.tan((Math.max(sun.elevation, 8) * Math.PI) / 180);
    cast = [-reach * Math.cos(a), -reach * Math.sin(a)];
  }
  const blur = 0.2 + 0.7 * by;
  return (
    <>
      <mesh geometry={g.recess} position={[0, g.cy, top + 0.0035]}>
        <meshBasicMaterial map={ground} toneMapped={false} color={glowColor} polygonOffset polygonOffsetFactor={-1} polygonOffsetUnits={-5} />
      </mesh>
      <mesh position={[cast[0], g.cy + cast[1], top + 0.004]} scale={[(g.lw + 2 * blur) / (g.lw + 0.4), (g.lh + 2 * blur) / (g.lh + 0.4), 1]}>
        <planeGeometry args={[g.lw + 0.4, g.lh + 0.4]} />
        <meshBasicMaterial map={shadow} transparent opacity={Math.min(0.8, 0.4 + by)} toneMapped={false} depthWrite={false} polygonOffset polygonOffsetFactor={-1} polygonOffsetUnits={-6} />
      </mesh>
      <mesh geometry={g.rim} position={[0, g.cy, top + by - 0.002]}>
        <meshBasicMaterial map={rim} toneMapped={false} color={glowColor} />
      </mesh>
      <mesh geometry={g.card} position={[0, g.cy, top + by]} castShadow>
        <meshBasicMaterial map={screen} toneMapped={false} color={glowColor.clone().multiplyScalar(LIFT_TONE)} />
      </mesh>
    </>
  );
};
