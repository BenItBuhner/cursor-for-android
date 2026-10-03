import { RoundedBox, useTexture } from "@react-three/drei";
import type React from "react";
import { useMemo } from "react";
import { staticFile } from "remotion";
import * as THREE from "three";
import { takes, type DeviceId } from "../takes";

/** A device's body, in centimetres: how far its glass reaches past the screen, its corner radius, its depth. */
const BODY: Record<DeviceId, { bezel: number; radius: number; depth: number; frame: number }> = {
  phone: { bezel: 0.13, radius: 0.92, depth: 0.82, frame: 0.06 },
  foldable: { bezel: 0.16, radius: 0.55, depth: 0.58, frame: 0.06 },
  tablet: { bezel: 0.62, radius: 1.1, depth: 0.62, frame: 0.07 },
};

/** The app's background, left showing where a lifted band of the screen came away from. */
const SCREEN_GROUND = "#141414";

/**
 * A lifted band steps up in surface tone, as Material's elevated surfaces do, and its edge shows as a hairline, so it
 * still separates from the dark screen it rose off.
 */
const LIFT_TONE = 1.32;
const LIFT_RIM = "#4a4a4c";

/** [color] as a texture, so a flat fill shares the screen's own shader rather than compiling another. */
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

/** Each device's screen across, in centimetres. */
const SCREEN_WIDTH: Record<DeviceId, number> = { phone: 6.86, foldable: 14.4, tablet: 25.2 };

/** A flat rounded rectangle [w] by [h] centred on the origin, its UVs running 0 to 1 across it. */
function roundedRect(w: number, h: number, r: number): THREE.ShapeGeometry {
  const s = new THREE.Shape();
  const x = -w / 2;
  const y = -h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  const g = new THREE.ShapeGeometry(s, 24);
  const uv = g.attributes.uv!;
  const pos = g.attributes.position!;
  for (let i = 0; i < uv.count; i++) uv.setXY(i, (pos.getX(i) - x) / w, (pos.getY(i) - y) / h);
  return g;
}

/** A soft shadow under a [w] by [h] card [blur] centimetres off the surface, on a plane [blur] wider each side. */
export function softShadow(w: number, h: number, blur: number, fill = "rgba(0,0,0,0.9)"): THREE.CanvasTexture {
  const scale = 120;
  const cw = Math.round((w + 2 * blur) * scale);
  const ch = Math.round((h + 2 * blur) * scale);
  const c = document.createElement("canvas");
  c.width = cw;
  c.height = ch;
  const ctx = c.getContext("2d")!;
  ctx.filter = `blur(${blur * scale * 0.45}px)`;
  ctx.fillStyle = fill;
  ctx.beginPath();
  ctx.roundRect(blur * scale, blur * scale, w * scale, h * scale, 0.25 * scale);
  ctx.fill();
  return new THREE.CanvasTexture(c);
}

export const screenSize = (device: DeviceId) => {
  const t = takes[device];
  const w = SCREEN_WIDTH[device];
  return { w, h: (w * t.height) / t.width };
};

/**
 * [device] lying with its glass up the +z axis, its screen showing [plate] (a still of its take, see Plate): a graphite
 * frame, black glass to the frame's edge, the screen unlit by the scene and so exactly the take's colours, and over it a
 * clear coat that only adds what the set reflects. [lift], if given, raises a band of the screen off the glass, as
 * fractions of its height from the top, by [lift.by] centimetres, with its own shadow on the glass under it.
 */
export const Device3D: React.FC<{
  device: DeviceId;
  plate: string;
  position?: [number, number, number];
  rotation?: [number, number, number];
  glow?: number;
  lift?: { top: number; bottom: number; by: number; inset?: number };
  /** Where the sun is, in degrees, so a lifted band's shadow falls where that sun would throw it. */
  sun?: { azimuth: number; elevation: number };
}> = ({ device, plate, position = [0, 0, 0], rotation = [0, 0, 0], glow = 1, lift, sun }) => {
  const body = BODY[device];
  const { w, h } = screenSize(device);
  const outerW = w + 2 * body.bezel;
  const outerH = h + 2 * body.bezel;
  const radius = Math.min(body.radius, outerW / 2 - 0.01);
  const map = useTexture(staticFile(`concept/${plate}.png`));
  map.colorSpace = THREE.SRGBColorSpace;
  map.anisotropy = 16;
  const screenR = Math.max(0.05, radius - body.bezel);
  const glass = useMemo(() => roundedRect(outerW - 2 * body.frame, outerH - 2 * body.frame, radius - body.frame), [outerW, outerH, radius, body.frame]);
  const screen = useMemo(() => roundedRect(w, h, screenR), [w, h, screenR]);
  const lifted = useMemo(() => {
    if (!lift) return null;
    const inset = lift.inset ?? 0;
    const lw = w * (1 - 2 * inset);
    const lh = h * (lift.bottom - lift.top);
    const g = roundedRect(lw, lh, 0.18);
    const uv = g.attributes.uv!;
    for (let i = 0; i < uv.count; i++) uv.setXY(i, inset + uv.getX(i) * (1 - 2 * inset), 1 - lift.bottom + uv.getY(i) * (lift.bottom - lift.top));
    return { g, recess: roundedRect(lw, lh, 0.18), rim: roundedRect(lw + 0.05, lh + 0.05, 0.2), lw, lh, cy: h / 2 - h * (lift.top + lift.bottom) / 2 };
  }, [lift, w, h]);
  const shadow = useMemo(() => (lifted ? softShadow(lifted.lw, lifted.lh, 0.35 * lift!.by) : null), [lifted, lift]);
  const cast = useMemo((): [number, number] => {
    if (!lift) return [0, 0];
    if (!sun) return [0.08 * lift.by, -0.25 * lift.by];
    const a = (sun.azimuth * Math.PI) / 180 - rotation[2];
    const reach = lift.by / Math.tan((Math.max(sun.elevation, 8) * Math.PI) / 180);
    return [-reach * Math.cos(a), -reach * Math.sin(a)];
  }, [lift, sun, rotation]);
  const ground = useMemo(() => swatch(SCREEN_GROUND), []);
  const rim = useMemo(() => swatch(LIFT_RIM), []);
  const top = body.depth / 2;
  const contact = useMemo(() => softShadow(outerW, outerH, 0.9), [outerW, outerH]);
  return (
    <group position={position} rotation={rotation}>
      <mesh position={[0, 0, -top + 0.004]}>
        <planeGeometry args={[outerW + 1.8, outerH + 1.8]} />
        <meshBasicMaterial map={contact} transparent opacity={0.7} toneMapped={false} depthWrite={false} />
      </mesh>
      <RoundedBox args={[outerW, outerH, body.depth]} radius={Math.min(radius, body.depth / 2 - 0.001)} smoothness={6} castShadow receiveShadow>
        <meshPhysicalMaterial color="#2a2a2f" metalness={0.92} roughness={0.3} clearcoat={0.4} clearcoatRoughness={0.25} envMapIntensity={1.2} />
      </RoundedBox>
      <mesh geometry={glass} position={[0, 0, top + 0.002]}>
        <meshPhysicalMaterial color="#020203" metalness={0} roughness={0.06} clearcoat={1} clearcoatRoughness={0.02} envMapIntensity={1} />
      </mesh>
      <mesh geometry={screen} position={[0, 0, top + 0.004]}>
        <meshBasicMaterial map={map} toneMapped={false} color={new THREE.Color(glow, glow, glow)} />
      </mesh>
      {lifted ? (
        <>
          <mesh geometry={lifted.recess} position={[0, lifted.cy, top + 0.0045]}>
            <meshBasicMaterial map={ground} toneMapped={false} color={new THREE.Color(glow, glow, glow)} />
          </mesh>
          <mesh position={[cast[0], lifted.cy + cast[1], top + 0.0065]}>
            <planeGeometry args={[lifted.lw + 0.7 * lift!.by, lifted.lh + 0.7 * lift!.by]} />
            <meshBasicMaterial map={shadow} transparent opacity={0.8} toneMapped={false} depthWrite={false} />
          </mesh>
          <mesh geometry={lifted.rim} position={[0, lifted.cy, top + lift!.by - 0.002]}>
            <meshBasicMaterial map={rim} toneMapped={false} color={new THREE.Color(glow, glow, glow)} />
          </mesh>
          <mesh geometry={lifted.g} position={[0, lifted.cy, top + lift!.by]} castShadow>
            <meshBasicMaterial map={map} toneMapped={false} color={new THREE.Color(glow, glow, glow).multiplyScalar(LIFT_TONE)} />
          </mesh>
        </>
      ) : null}
      <mesh geometry={glass} position={[0, 0, top + 0.005]}>
        <meshStandardMaterial color="#000" metalness={0} roughness={0.05} blending={THREE.AdditiveBlending} transparent depthWrite={false} envMapIntensity={1} />
      </mesh>
    </group>
  );
};
