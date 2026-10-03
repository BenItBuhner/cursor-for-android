import { RoundedBox } from "@react-three/drei";
import type React from "react";
import { useMemo } from "react";
import * as THREE from "three";
import { linen, oak, stoneware } from "./materials";

/**
 * What else is on the table, each telling the time or the story without taking the eye: the coffee going down through
 * the day, the notebook and pencil, the keys dropped there that night. All in centimetres, standing on the stone.
 */

/** The cup's inside wall, radius against height, from the floor of the cup to the rim. */
const INSIDE: [number, number][] = [
  [0, 0.72],
  [3.3, 0.75],
  [3.7, 1.0],
  [3.95, 4.6],
  [4.08, 8.9],
];
const OUTSIDE: [number, number][] = [
  [0, 0.18],
  [3.25, 0.18],
  [3.42, 0.0],
  [3.72, 0.04],
  [3.92, 0.55],
  [4.2, 4.5],
  [4.36, 8.62],
  [4.33, 8.96],
  [4.2, 9.02],
];
const RIM = 8.96;

function insideRadius(h: number): number {
  for (let i = 1; i < INSIDE.length; i++) {
    const [r0, h0] = INSIDE[i - 1]!;
    const [r1, h1] = INSIDE[i]!;
    if (h <= h1) return r0 + ((r1 - r0) * (h - h0)) / (h1 - h0);
  }
  return INSIDE[INSIDE.length - 1]![0];
}

/** The coffee's surface: dark and glassy, the meniscus a shade of crema where it climbs the wall. */
function coffeeTexture(): THREE.CanvasTexture {
  const c = document.createElement("canvas");
  c.width = c.height = 256;
  const ctx = c.getContext("2d")!;
  const g = ctx.createRadialGradient(128, 128, 0, 128, 128, 128);
  g.addColorStop(0, "#1e0f07");
  g.addColorStop(0.86, "#26140a");
  g.addColorStop(0.95, "#5a3820");
  g.addColorStop(1, "#7a5232");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 256, 256);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

/** What an emptied cup leaves on its floor: a dried ring and a film. */
function stainTexture(): THREE.CanvasTexture {
  const c = document.createElement("canvas");
  c.width = c.height = 256;
  const ctx = c.getContext("2d")!;
  ctx.fillStyle = "#e6e0d6";
  ctx.fillRect(0, 0, 256, 256);
  const g = ctx.createRadialGradient(128, 128, 60, 128, 128, 128);
  g.addColorStop(0, "rgba(120,80,50,0.18)");
  g.addColorStop(0.8, "rgba(110,70,40,0.32)");
  g.addColorStop(0.93, "rgba(80,48,26,0.75)");
  g.addColorStop(1, "rgba(80,48,26,0.2)");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 256, 256);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

/** A stoneware cup at [x], [y], its handle at [turn], [fill] of the way up with coffee (0 is drunk). */
export const Cup: React.FC<{ x: number; y: number; turn: number; fill: number }> = ({ x, y, turn, fill }) => {
  const parts = useMemo(() => {
    const profile = [...OUTSIDE, ...[...INSIDE].reverse()].map(([r, h]) => new THREE.Vector2(r, h));
    const body = new THREE.LatheGeometry(profile, 96);
    body.computeVertexNormals();
    const glaze = stoneware(512);
    glaze.wrapS = glaze.wrapT = THREE.RepeatWrapping;
    glaze.repeat.set(3, 1);
    return {
      body,
      glaze: new THREE.MeshPhysicalMaterial({ map: glaze, roughness: 0.42, clearcoat: 0.55, clearcoatRoughness: 0.18, side: THREE.DoubleSide }),
      handle: new THREE.TorusGeometry(2.05, 0.42, 20, 48, Math.PI),
      coffee: new THREE.MeshPhysicalMaterial({ map: coffeeTexture(), roughness: 0.05, clearcoat: 1, clearcoatRoughness: 0.02 }),
      stain: new THREE.MeshStandardMaterial({ map: stainTexture(), roughness: 0.45 }),
    };
  }, []);
  const level = 0.75 + Math.max(0, Math.min(1, fill)) * (RIM - 1.15 - 0.75);
  return (
    <group position={[x, y, 0]} rotation={[0, 0, turn]} scale={0.84}>
      <group>
        <mesh geometry={parts.body} material={parts.glaze} rotation={[Math.PI / 2, 0, 0]} castShadow receiveShadow />
        {/* The handle's open ends sit inside the wall's thickness (4.0 to 4.3 cm out), short of the inside. */}
        <mesh geometry={parts.handle} material={parts.glaze} position={[4.45, 0, 4.9]} rotation={[Math.PI / 2, 0, -Math.PI / 2]} castShadow />
        {fill > 0.01 ? (
          <mesh position={[0, 0, level]} material={parts.coffee}>
            <circleGeometry args={[insideRadius(level), 96]} />
          </mesh>
        ) : (
          <mesh position={[0, 0, 0.74]} material={parts.stain}>
            <circleGeometry args={[3.3, 64]} />
          </mesh>
        )}
      </group>
    </group>
  );
};

/** Page edges: fine lines of paper, a little uneven. */
function pagesTexture(): THREE.CanvasTexture {
  const c = document.createElement("canvas");
  c.width = 64;
  c.height = 256;
  const ctx = c.getContext("2d")!;
  ctx.fillStyle = "#efe8da";
  ctx.fillRect(0, 0, 64, 256);
  for (let y = 0; y < 256; y += 2) {
    ctx.fillStyle = `rgba(120,105,85,${0.05 + 0.08 * ((y * 7919) % 13) / 13})`;
    ctx.fillRect(0, y, 64, 1);
  }
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.repeat.set(1, 3);
  return t;
}

/** A linen-bound notebook at [x], [y], turned [turn], with its elastic and a cedar pencil laid across it. */
export const Notebook: React.FC<{ x: number; y: number; turn: number; pencil?: boolean }> = ({ x, y, turn, pencil = true }) => {
  const parts = useMemo(() => {
    const cloth = linen([0.29, 0.33, 0.39], 1024, 160);
    cloth.color.repeat.set(2.4, 3.4);
    cloth.bump.repeat.set(2.4, 3.4);
    const wood = oak(512);
    return {
      cover: new THREE.MeshStandardMaterial({ map: cloth.color, bumpMap: cloth.bump, bumpScale: 0.5, roughness: 0.92 }),
      pages: new THREE.MeshStandardMaterial({ map: pagesTexture(), roughness: 0.9 }),
      band: new THREE.MeshStandardMaterial({ color: "#1b1b1d", roughness: 0.7 }),
      cedar: new THREE.MeshPhysicalMaterial({ map: wood.color, color: "#f0d7b8", roughness: 0.35, clearcoat: 0.6, clearcoatRoughness: 0.25 }),
      bare: new THREE.MeshStandardMaterial({ color: "#c79a6b", roughness: 0.8 }),
      graphite: new THREE.MeshStandardMaterial({ color: "#2a2a2c", roughness: 0.35, metalness: 0.5 }),
    };
  }, []);
  const W = 14.8;
  const H = 21;
  return (
    <group position={[x, y, 0]} rotation={[0, 0, turn]}>
      <RoundedBox args={[W + 0.4, H + 0.4, 0.26]} radius={0.1} smoothness={3} position={[0, 0, 0.13]} material={parts.cover} castShadow receiveShadow />
      <mesh position={[0.1, 0, 0.26 + 0.45]} material={parts.pages} castShadow receiveShadow>
        <boxGeometry args={[W - 0.2, H - 0.2, 0.9]} />
      </mesh>
      <RoundedBox args={[W + 0.4, H + 0.4, 0.26]} radius={0.1} smoothness={3} position={[0, 0, 1.16 + 0.13]} material={parts.cover} castShadow receiveShadow />
      <RoundedBox args={[0.55, H + 0.5, 1.5]} radius={0.05} smoothness={2} position={[W / 2 - 1.6, 0, 0.71]} material={parts.band} castShadow />
      {pencil ? (
        <group position={[-1.2, -0.6, 1.42 + 0.36]} rotation={[0, 0, 1.22]}>
          <mesh material={parts.cedar} rotation={[0, 0, Math.PI / 2]} castShadow>
            <cylinderGeometry args={[0.36, 0.36, 15.5, 6]} />
          </mesh>
          <mesh material={parts.bare} position={[7.75 + 0.9, 0, 0]} rotation={[0, 0, -Math.PI / 2]} castShadow>
            <cylinderGeometry args={[0.07, 0.34, 1.8, 6]} />
          </mesh>
          <mesh material={parts.graphite} position={[7.75 + 1.9, 0, 0]} rotation={[0, 0, -Math.PI / 2]}>
            <cylinderGeometry args={[0.0, 0.075, 0.25, 12]} />
          </mesh>
        </group>
      ) : null}
    </group>
  );
};

/** A key's outline: a bow with its hole, a shoulder, and the blade cut on one edge. */
function keyShape(length: number, seed: number): THREE.Shape {
  const s = new THREE.Shape();
  const bow = 1.05;
  s.absarc(0, 0, bow, 0.45, Math.PI * 2 - 0.45, false);
  s.lineTo(bow + 0.25, -0.42);
  s.lineTo(length, -0.42);
  s.lineTo(length + 0.25, -0.1);
  s.lineTo(length + 0.25, 0.1);
  // Cuts with sloped flanks and flat roots, as a key machine leaves them, not a saw's teeth.
  const cuts = 5;
  const pitch = (length - bow - 0.9) / cuts;
  s.lineTo(length - 0.1, 0.36);
  for (let i = 0; i < cuts; i++) {
    const x = length - 0.25 - i * pitch;
    const depth = 0.1 + 0.2 * (((seed * 31 + i * 17) % 7) / 7);
    s.lineTo(x - pitch * 0.3, 0.42 - depth);
    s.lineTo(x - pitch * 0.55, 0.42 - depth);
    s.lineTo(x - pitch, 0.4);
  }
  s.lineTo(bow + 0.25, 0.42);
  s.closePath();
  const hole = new THREE.Path();
  hole.absarc(-0.4, 0, 0.26, 0, Math.PI * 2, true);
  s.holes.push(hole);
  return s;
}

/** The keys, dropped on the table that night: two on a split ring at [x], [y], turned [turn]. */
export const Keys: React.FC<{ x: number; y: number; turn: number }> = ({ x, y, turn }) => {
  const parts = useMemo(() => {
    const extrude = (shape: THREE.Shape) => new THREE.ExtrudeGeometry(shape, { depth: 0.12, bevelEnabled: true, bevelThickness: 0.03, bevelSize: 0.03, bevelSegments: 2, curveSegments: 24 });
    return {
      brass: extrude(keyShape(5.4, 1)),
      steel: extrude(keyShape(4.6, 2)),
      brassMat: new THREE.MeshPhysicalMaterial({ color: "#c9a873", metalness: 1, roughness: 0.42, envMapIntensity: 1.6 }),
      steelMat: new THREE.MeshPhysicalMaterial({ color: "#c2c5ca", metalness: 1, roughness: 0.38, envMapIntensity: 1.6 }),
    };
  }, []);
  return (
    <group position={[x, y, 0]} rotation={[0, 0, turn]}>
      <mesh position={[0, 0, 0.09]} castShadow>
        <torusGeometry args={[1.25, 0.08, 10, 48]} />
        <meshPhysicalMaterial color="#b0b3b8" metalness={1} roughness={0.32} envMapIntensity={1.6} />
      </mesh>
      <mesh geometry={parts.brass} material={parts.brassMat} position={[1.6, 0.6, 0.04]} rotation={[0, 0, 0.35]} castShadow receiveShadow />
      <mesh geometry={parts.steel} material={parts.steelMat} position={[0.6, -1.5, 0.21]} rotation={[0, 0, -0.9]} castShadow receiveShadow />
    </group>
  );
};
