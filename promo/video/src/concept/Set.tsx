import { Environment, Lightformer } from "@react-three/drei";
import { useThree } from "@react-three/fiber";
import type React from "react";
import { useMemo } from "react";
import * as THREE from "three";
import { softShadow } from "./Device3D";

/** A seeded hash, so the stone's grain is the same on every frame and every render. */
function hash(x: number, y: number, seed: number): number {
  let h = (x * 374761393 + y * 668265263 + seed * 2147483647) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

/** Smooth value noise at [x], [y] in lattice cells, repeating every [period] cells so the stone tiles without seams. */
function valueNoise(x: number, y: number, seed: number, period: number): number {
  const xi = Math.floor(x);
  const yi = Math.floor(y);
  const xf = x - xi;
  const yf = y - yi;
  const u = xf * xf * (3 - 2 * xf);
  const v = yf * yf * (3 - 2 * yf);
  const wrap = (n: number) => ((n % period) + period) % period;
  const a = hash(wrap(xi), wrap(yi), seed);
  const b = hash(wrap(xi + 1), wrap(yi), seed);
  const c = hash(wrap(xi), wrap(yi + 1), seed);
  const d = hash(wrap(xi + 1), wrap(yi + 1), seed);
  return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}

/**
 * Honed limestone, as a colour map and a bump map [size] pixels square: a warm, slightly uneven base, a soft cloud of
 * tone a few centimetres across, fine pitting, and the odd fossil fleck, so the surface reads as stone at macro range.
 */
function stone(size: number): { color: THREE.CanvasTexture; bump: THREE.CanvasTexture } {
  const color = document.createElement("canvas");
  const bump = document.createElement("canvas");
  color.width = color.height = bump.width = bump.height = size;
  const c = color.getContext("2d")!;
  const b = bump.getContext("2d")!;
  const ci = c.createImageData(size, size);
  const bi = b.createImageData(size, size);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const octave = (cell: number, seed: number) => valueNoise(x / cell, y / cell, seed, size / cell);
      const cloud = 0.55 * octave(256, 1) + 0.3 * octave(64, 2) + 0.15 * octave(16, 3);
      const pit = hash(x, y, 9);
      const fleck = pit > 0.9975 ? 1 : 0;
      const pore = pit > 0.97 ? 1 : 0;
      const grain = octave(2, 5);
      const tone = 0.88 + 0.1 * cloud - 0.1 * fleck - 0.03 * pore - 0.05 * grain;
      const i = (y * size + x) * 4;
      ci.data[i] = 234 * tone;
      ci.data[i + 1] = 229 * tone;
      ci.data[i + 2] = 220 * tone;
      ci.data[i + 3] = 255;
      const height = 190 - 90 * fleck - 50 * pore + 50 * grain + 20 * cloud;
      bi.data[i] = bi.data[i + 1] = bi.data[i + 2] = height;
      bi.data[i + 3] = 255;
    }
  }
  c.putImageData(ci, 0, 0);
  b.putImageData(bi, 0, 0);
  const ct = new THREE.CanvasTexture(color);
  ct.colorSpace = THREE.SRGBColorSpace;
  const bt = new THREE.CanvasTexture(bump);
  for (const t of [ct, bt]) {
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.repeat.set(4, 4);
    t.anisotropy = 16;
  }
  return { color: ct, bump: bt };
}

/** The ground: a slab of the stone [extent] centimetres across under everything, taking the shadows. */
export const Stone: React.FC<{ extent?: number; tint?: string }> = ({ extent = 160, tint = "#ffffff" }) => {
  const { color, bump } = useMemo(() => {
    const t = stone(1024);
    for (const m of [t.color, t.bump]) m.repeat.set(extent / 40, extent / 40);
    return t;
  }, [extent]);
  return (
    <mesh receiveShadow position={[0, 0, 0]}>
      <planeGeometry args={[extent, extent]} />
      <meshStandardMaterial map={color} bumpMap={bump} bumpScale={0.6} roughness={0.8} metalness={0} color={tint} />
    </mesh>
  );
};

/** A time of day: where the sun is and how it burns, the sky's fill, and the cards the glass and metal reflect. */
export type Light = {
  sun: { azimuth: number; elevation: number; color: string; intensity: number; softness: number } | null;
  sky: { color: string; ground: string; intensity: number };
  cards: { position: [number, number, number]; scale: [number, number, number]; color: string; intensity: number }[];
  /** The room the glass and metal see beyond the cards. */
  background: string;
  exposure: number;
};

export const LIGHT = {
  /** Low, warm sun raking from the left behind; a cool sky fill; one long window card for the glass to catch. */
  morning: {
    sun: { azimuth: 150, elevation: 17, color: "#ffe0bc", intensity: 7.5, softness: 0.006 },
    sky: { color: "#a7bddc", ground: "#5e5a55", intensity: 1.1 },
    cards: [
      { position: [-30, 18, 26], scale: [40, 9, 1], color: "#fff1dc", intensity: 2.4 },
      { position: [30, -10, 40], scale: [30, 30, 1], color: "#cfdcf0", intensity: 0.9 },
      { position: [-6, 40, 30], scale: [70, 16, 1], color: "#f3eee6", intensity: 0.45 },
    ],
    background: "#2b3240",
    exposure: 1.0,
  },
  /** High, near-white sun a little off the top, short crisp shadows; a broad soft top card. */
  noon: {
    sun: { azimuth: 160, elevation: 50, color: "#fff4e6", intensity: 2.6, softness: 0.005 },
    sky: { color: "#c9d6e8", ground: "#8c867c", intensity: 0.7 },
    cards: [
      { position: [0, 10, 60], scale: [80, 30, 1], color: "#ffffff", intensity: 1.6 },
      { position: [-50, -20, 20], scale: [20, 40, 1], color: "#fff0dc", intensity: 1.2 },
      { position: [26, 46, 22], scale: [70, 14, 1], color: "#f4f7fb", intensity: 0.45 },
    ],
    background: "#59606b",
    exposure: 0.95,
  },
  /** No sun: a faint moonlit window off the right, and whatever the screen itself throws onto the stone. */
  night: {
    sun: { azimuth: -30, elevation: 28, color: "#c3ccdf", intensity: 1.7, softness: 0.008 },
    sky: { color: "#1b2742", ground: "#0b0f19", intensity: 0.02 },
    cards: [{ position: [40, -10, 30], scale: [12, 40, 1], color: "#9db4e8", intensity: 0.5 }],
    background: "#05070c",
    exposure: 1.0,
  },
} satisfies Record<string, Light>;

const toVector = (azimuth: number, elevation: number, distance: number): [number, number, number] => {
  const a = (azimuth * Math.PI) / 180;
  const e = (elevation * Math.PI) / 180;
  return [distance * Math.cos(e) * Math.cos(a), distance * Math.cos(e) * Math.sin(a), distance * Math.sin(e)];
};

/** [light] on the set: the sun as a shadow-casting key over [span] centimetres, the sky's fill, and the reflected cards. */
export const SetLight: React.FC<{ light: Light; span?: number }> = ({ light, span = 60 }) => {
  const { gl } = useThree();
  gl.toneMappingExposure = light.exposure;
  return (
    <>
      <hemisphereLight args={[light.sky.color, light.sky.ground, light.sky.intensity]} />
      {light.sun ? (
        <directionalLight
          position={toVector(light.sun.azimuth, light.sun.elevation, 120)}
          color={light.sun.color}
          intensity={light.sun.intensity}
          castShadow
          shadow-mapSize={[4096, 4096]}
          shadow-bias={-0.0004}
          shadow-normalBias={0.02}
          shadow-radius={light.sun.softness * 400}
          shadow-camera-left={-span}
          shadow-camera-right={span}
          shadow-camera-top={span}
          shadow-camera-bottom={-span}
          shadow-camera-near={1}
          shadow-camera-far={300}
        />
      ) : null}
      <Environment resolution={512} frames={1}>
        <color attach="background" args={[light.background]} />
        {light.cards.map((card, i) => (
          <Lightformer key={i} form="rect" position={card.position} scale={card.scale} color={card.color} intensity={card.intensity} target={[0, 0, 0]} />
        ))}
      </Environment>
    </>
  );
};

/**
 * The light a face-up screen at [position] lets onto the stone: not a lamp over it, which would glint in its own glass,
 * but a soft halo hugging the device's outline [size] and fading out over [reach] centimetres, in the screen's [color].
 */
export const ScreenSpill: React.FC<{
  position: [number, number, number];
  turn: number;
  size: { w: number; h: number };
  color: string;
  strength: number;
  reach: number;
}> = ({ position, turn, size, color, strength, reach }) => {
  const glow = useMemo(() => softShadow(size.w, size.h, reach, "#ffffff"), [size.w, size.h, reach]);
  return (
    <mesh position={[position[0], position[1], 0.01]} rotation={[0, 0, turn]}>
      <planeGeometry args={[size.w + 2 * reach, size.h + 2 * reach]} />
      <meshBasicMaterial map={glow} color={color} transparent opacity={strength} blending={THREE.AdditiveBlending} depthWrite={false} toneMapped={false} />
    </mesh>
  );
};
