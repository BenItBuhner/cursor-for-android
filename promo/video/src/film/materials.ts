import * as THREE from "three";

/**
 * The film's surfaces, drawn once per canvas into textures from seeded noise, so every frame and every render tab has
 * the same stone, the same weave and the same smudges on the glass.
 */

/** A seeded hash of a lattice point, 0 to 1. */
export function hash(x: number, y: number, seed: number): number {
  let h = (x * 374761393 + y * 668265263 + seed * 2147483647) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

/** Smooth value noise at [x], [y] in lattice cells, repeating every [period] cells so a texture tiles without seams. */
export function noise(x: number, y: number, seed: number, period: number): number {
  const xi = Math.floor(x);
  const yi = Math.floor(y);
  const xf = x - xi;
  const yf = y - yi;
  const u = xf * xf * (3 - 2 * xf);
  const v = yf * yf * (3 - 2 * yf);
  const p = Math.max(1, Math.round(period));
  const w = (n: number) => ((n % p) + p) % p;
  const a = hash(w(xi), w(yi), seed);
  const b = hash(w(xi + 1), w(yi), seed);
  const c = hash(w(xi), w(yi + 1), seed);
  const d = hash(w(xi + 1), w(yi + 1), seed);
  return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}

/** Fractal noise over [octaves], the first [cell] pixels across, on a [size]-pixel tile. */
function fbm(x: number, y: number, size: number, cell: number, octaves: number, seed: number): number {
  let sum = 0;
  let amp = 1;
  let norm = 0;
  for (let o = 0; o < octaves; o++) {
    const c = cell / 2 ** o;
    sum += amp * noise(x / c, y / c, seed + o * 17, size / c);
    norm += amp;
    amp *= 0.5;
  }
  return sum / norm;
}

/** A small seeded generator, for scattering. */
export function rng(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

type Px = { size: number; data: Float32Array };
const field = (size: number, fill = 0): Px => ({ size, data: new Float32Array(size * size).fill(fill) });

/** [px] as a texture: grey, or tinted by [rgb] (0 to 1 a channel at a value of 1). */
function texture(channels: Px[], srgb: boolean, repeat = 1): THREE.CanvasTexture {
  const size = channels[0]!.size;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext("2d")!;
  const img = ctx.createImageData(size, size);
  for (let i = 0; i < size * size; i++) {
    for (let c = 0; c < 3; c++) img.data[i * 4 + c] = Math.max(0, Math.min(255, Math.round(255 * channels[Math.min(c, channels.length - 1)]!.data[i]!)));
    img.data[i * 4 + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);
  const t = new THREE.CanvasTexture(canvas);
  t.colorSpace = srgb ? THREE.SRGBColorSpace : THREE.NoColorSpace;
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.repeat.set(repeat, repeat);
  t.anisotropy = 16;
  t.generateMipmaps = true;
  t.minFilter = THREE.LinearMipmapLinearFilter;
  return t;
}

/** Stamp a soft disc of [value] into [px] at [x], [y] (wrapping), mixed by its edge, through [op]. */
function stamp(px: Px, x: number, y: number, r: number, op: (old: number, k: number) => number) {
  const s = px.size;
  const R = Math.ceil(r + 1);
  for (let dy = -R; dy <= R; dy++) {
    for (let dx = -R; dx <= R; dx++) {
      const d = Math.hypot(dx + (x % 1), dy + (y % 1));
      const k = Math.max(0, Math.min(1, r + 0.5 - d));
      if (k <= 0) continue;
      const ix = (((Math.floor(x) + dx) % s) + s) % s;
      const iy = (((Math.floor(y) + dy) % s) + s) % s;
      const i = iy * s + ix;
      px.data[i] = op(px.data[i]!, k);
    }
  }
}

/**
 * Honed limestone, a tile [size] pixels square that the film lays [tile] centimetres across: a warm cream that clouds
 * gently over tens of centimetres, a few stylolites (the grey, jagged seams pressure leaves in limestone) running
 * roughly along one direction, a hair of white calcite vein, shell fragments, and a honed finish that is a little
 * glossier where the table's been used. The colour and the roughness; the fine pits and grain are [limestoneDetail].
 */
export function limestone(size = 2048): { color: THREE.CanvasTexture; roughness: THREE.CanvasTexture } {
  const r = field(size);
  const g = field(size);
  const b = field(size);
  const rough = field(size);
  const tone = field(size);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = y * size + x;
      const cloud = fbm(x, y, size, size / 3, 4, 11);
      const mottle = fbm(x, y, size, size / 24, 3, 29);
      tone.data[i] = 0.93 + 0.085 * (cloud - 0.5) * 2 + 0.03 * (mottle - 0.5) * 2;
      const wear = fbm(x, y, size, size / 2, 2, 41);
      rough.data[i] = 0.6 + 0.12 * (mottle - 0.5) - 0.1 * Math.max(0, wear - 0.55) * 3;
    }
  }
  // Stylolites: a seam's height across the tile is a slow wander plus a fine jagged tooth, both periodic in x.
  const darken = field(size, 0);
  const random = rng(7);
  const seams = 3;
  for (let k = 0; k < seams; k++) {
    const y0 = random() * size;
    const amp = 18 + 40 * random();
    const tooth = 3 + 4 * random();
    const strength = 0.04 + 0.06 * random();
    const seed = 100 + k;
    for (let x = 0; x < size; x++) {
      const wander = (fbm(x, 0, size, size / 4, 3, seed) - 0.5) * 2 * amp * 3;
      // Teeth are columns, flat-topped and uneven, a few millimetres apart, not a saw.
      const column = noise(x / 9, 0, seed + 50, size / 9);
      const jag = (Math.round(column * 3) / 3 - 0.5) * 2 * tooth + (noise(x / 3, 0, seed + 60, size / 3) - 0.5) * 0.6 * tooth;
      const y = y0 + wander + jag;
      const width = 0.5 + 0.7 * noise(x / 40, 0, seed + 70, size / 40);
      const fade = fbm(x, 0, size, size / 6, 2, seed + 80) ** 2.2 * 2.2;
      for (let dy = -6; dy <= 6; dy++) {
        const yy = Math.floor(y) + dy;
        const d = Math.abs(yy - y);
        const core = Math.max(0, 1 - d / width);
        const halo = Math.exp(-d / 3) * 0.3;
        const iy = ((yy % size) + size) % size;
        const i = iy * size + x;
        darken.data[i] = Math.max(darken.data[i]!, Math.min(0.14, strength * fade * (core + halo)));
      }
    }
  }
  // Calcite: two hairlines of white, each a random walk that drifts at a shallow angle across the tile.
  const lighten = field(size, 0);
  for (let k = 0; k < 2; k++) {
    let x = random() * size;
    let y = random() * size;
    let angle = (random() - 0.5) * 0.8 + (k ? Math.PI / 2 : 0);
    const length = size * (0.5 + 0.5 * random());
    for (let s = 0; s < length; s++) {
      angle += (random() - 0.5) * 0.18;
      x += Math.cos(angle);
      y += Math.sin(angle);
      stamp(lighten, x, y, 0.45 + 0.4 * random(), (old, a) => Math.max(old, 0.16 * a));
    }
  }
  // Shell fragments: small slivers a shade warmer or darker than the stone.
  const shell = field(size, 0);
  for (let k = 0; k < 900; k++) {
    const x = random() * size;
    const y = random() * size;
    const len = 2 + 9 * random() ** 2;
    const angle = random() * Math.PI;
    const v = (random() < 0.6 ? -1 : 1) * (0.04 + 0.08 * random());
    for (let s = 0; s < len; s++) stamp(shell, x + Math.cos(angle) * s, y + Math.sin(angle) * s, 0.5 + 0.6 * random(), (old, a) => old + v * a);
  }
  for (let i = 0; i < size * size; i++) {
    const t = tone.data[i]! * (1 - darken.data[i]!) + lighten.data[i]! + shell.data[i]!;
    const grey = darken.data[i]!;
    r.data[i] = (0.892 - 0.06 * grey) * t;
    g.data[i] = (0.858 - 0.04 * grey) * t;
    b.data[i] = (0.79 - 0.0 * grey) * t;
    rough.data[i] = Math.min(0.9, rough.data[i]! + 0.25 * grey);
  }
  return { color: texture([r, g, b], true), roughness: texture([rough], false) };
}

/**
 * The stone up close, a tile the film lays a few centimetres across over [limestone]: fine grain and the pits of a
 * honed face, as a multiplier on its colour (0.5 is none) and a height for the bump map.
 */
export function limestoneDetail(size = 1024): { color: THREE.CanvasTexture; bump: THREE.CanvasTexture } {
  const color = field(size, 0.5);
  const bump = field(size, 0.6);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = y * size + x;
      const grain = noise(x / 1.6, y / 1.6, 3, size / 1.6) - 0.5;
      const sand = noise(x / 6, y / 6, 4, size / 6) - 0.5;
      color.data[i] = 0.5 + 0.035 * grain + 0.03 * sand;
      bump.data[i] = 0.6 + 0.09 * grain + 0.08 * sand;
    }
  }
  const random = rng(31);
  for (let k = 0; k < 520; k++) {
    const x = random() * size;
    const y = random() * size;
    const r = 0.5 + 2.2 * random() ** 4;
    const depth = 0.12 + 0.22 * random();
    stamp(bump, x, y, r, (old, a) => old - depth * a);
    stamp(color, x, y, r * 0.9, (old, a) => old - 0.11 * a);
  }
  return { color: texture([color], false), bump: texture([bump], false) };
}

/** Bookcloth linen: a plain weave of slubbed threads in [rgb], a tile [size] pixels square, [threads] each way. */
export function linen(rgb: [number, number, number], size = 1024, threads = 128): { color: THREE.CanvasTexture; bump: THREE.CanvasTexture } {
  const ch = [field(size), field(size), field(size)];
  const bump = field(size);
  const pitch = size / threads;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = y * size + x;
      const ti = Math.floor(x / pitch);
      const tj = Math.floor(y / pitch);
      const over = (ti + tj) % 2 === 0;
      const along = over ? (y % pitch) / pitch : (x % pitch) / pitch;
      const across = over ? (x % pitch) / pitch : (y % pitch) / pitch;
      const slub = 0.85 + 0.3 * noise(over ? ti : tj, (over ? y : x) / 40, over ? 5 : 6, over ? threads : threads);
      const profile = Math.sin(Math.PI * Math.min(1, across * slub + (1 - slub) / 2)) * Math.sin(Math.PI * along) ** 0.3;
      const fuzz = noise(x / 1.3, y / 1.3, 8, size / 1.3) - 0.5;
      const thread = 0.9 + 0.12 * (hash(over ? ti : tj, over ? 1 : 2, 9) - 0.5);
      const shade = (0.72 + 0.28 * profile) * thread + 0.04 * fuzz;
      for (let c = 0; c < 3; c++) ch[c]!.data[i] = rgb[c]! * shade;
      bump.data[i] = 0.3 + 0.6 * profile + 0.05 * fuzz;
    }
  }
  return { color: texture(ch, true), bump: texture([bump], false) };
}

/** Flat-sawn oak, its grain running along u, a tile [size] pixels square. */
export function oak(size = 1024): { color: THREE.CanvasTexture; bump: THREE.CanvasTexture } {
  const ch = [field(size), field(size), field(size)];
  const bump = field(size);
  const early: [number, number, number] = [0.74, 0.58, 0.4];
  const late: [number, number, number] = [0.5, 0.35, 0.21];
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = y * size + x;
      const warp = (fbm(x, y, size, size / 2, 3, 61) - 0.5) * 90 + (noise(x / 30, y / 3, 62, size / 30) - 0.5) * 6;
      const ring = (((y + warp) / (size / 22)) % 1 + 1) % 1;
      const latewood = Math.pow(Math.max(0, Math.sin(Math.PI * ring)), 6);
      const pore = noise(x / 5, y / 0.9, 63, size / 5) > 0.82 ? 1 : 0;
      const ray = noise(x / 2, y / 22, 64, size / 2) > 0.9 ? 0.5 : 0;
      for (let c = 0; c < 3; c++) ch[c]!.data[i] = (early[c]! + (late[c]! - early[c]!) * latewood) * (1 - 0.18 * pore + 0.06 * ray);
      bump.data[i] = 0.6 - 0.3 * pore + 0.1 * latewood;
    }
  }
  return { color: texture(ch, true), bump: texture([bump], false) };
}

/** A speckled stoneware glaze: warm off-white with the iron that comes through it as dark specks. */
export function stoneware(size = 512): THREE.CanvasTexture {
  const ch = [field(size), field(size), field(size)];
  const random = rng(77);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const i = y * size + x;
      const pool = fbm(x, y, size, size / 4, 3, 71);
      ch[0]!.data[i] = 0.9 - 0.05 * pool;
      ch[1]!.data[i] = 0.885 - 0.05 * pool;
      ch[2]!.data[i] = 0.85 - 0.06 * pool;
    }
  }
  for (let k = 0; k < 500; k++) {
    const x = random() * size;
    const y = random() * size;
    const r = 0.4 + 1.3 * random() ** 3;
    for (let c = 0; c < 3; c++) stamp(ch[c]!, x, y, r, (old, a) => old * (1 - 0.6 * a));
  }
  return texture(ch, true);
}

/**
 * The glass's roughness: polished almost everywhere, a thumb's smudge or two where a phone is picked up (whorled ridges
 * in a soft oval), and the faint hairlines a glass picks up in a pocket. Scaled to a [aspect] (width over height) face.
 */
export function smudges(seed: number, aspect: number, size = 1024): THREE.CanvasTexture {
  const rough = field(size, 0.035);
  const random = rng(seed);
  const prints = 2 + Math.floor(random() * 2);
  for (let k = 0; k < prints; k++) {
    const cx = (0.2 + 0.6 * random()) * size;
    const cy = (0.25 + 0.6 * random()) * size;
    const rx = (0.05 + 0.03 * random()) * size;
    const ry = rx * (1.3 + 0.2 * random()) * aspect;
    const angle = random() * Math.PI;
    const strength = 0.1 + 0.12 * random();
    const R = Math.ceil(Math.max(rx, ry) * 1.6);
    for (let dy = -R; dy <= R; dy++) {
      for (let dx = -R; dx <= R; dx++) {
        const x = Math.floor(cx) + dx;
        const y = Math.floor(cy) + dy;
        if (x < 0 || y < 0 || x >= size || y >= size) continue;
        const u = (dx * Math.cos(angle) + dy * Math.sin(angle)) / rx;
        const v = (-dx * Math.sin(angle) + dy * Math.cos(angle)) / ry;
        const d = Math.hypot(u, v);
        if (d > 1.6) continue;
        const ridges = 0.5 + 0.5 * Math.sin(d * 46 + 3 * noise(u * 3 + 9, v * 3, seed, 64));
        const falloff = Math.exp(-((d / 0.9) ** 4));
        const i = y * size + x;
        rough.data[i] = Math.max(rough.data[i]!, 0.035 + strength * falloff * (0.55 + 0.45 * ridges));
      }
    }
  }
  for (let k = 0; k < 14; k++) {
    let x = random() * size;
    let y = random() * size;
    const angle = random() * Math.PI;
    const length = size * (0.03 + 0.12 * random());
    for (let s = 0; s < length; s++) {
      x += Math.cos(angle);
      y += Math.sin(angle);
      stamp(rough, x, y, 0.4, (old, a) => Math.max(old, 0.035 + 0.12 * a));
    }
  }
  return texture([rough], false);
}

export type View = "morning" | "noon" | "night" | "next";

/**
 * What the window looks out on at each time of day, as the glass and the metal see it reflected: the sky, a line of
 * trees and a roof across the bottom, the frame's mullion and transom, and at night a few lit windows across the way.
 */
export function windowView(view: View, w = 512, h = 640): THREE.CanvasTexture {
  const canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext("2d")!;
  const sky = ctx.createLinearGradient(0, 0, 0, h);
  const stops: Record<View, [string, string, string]> = {
    morning: ["#9fb6d6", "#f1e2cc", "#ffd9a8"],
    noon: ["#8fb2e0", "#cfe0f3", "#eef4fa"],
    night: ["#05070d", "#0d1424", "#1a2236"],
    next: ["#a9bad3", "#f4e6d2", "#ffe2b8"],
  };
  const [top, mid, low] = stops[view];
  sky.addColorStop(0, top);
  sky.addColorStop(0.62, mid);
  sky.addColorStop(1, low);
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, w, h);
  const random = rng(view === "night" ? 5 : 3);
  const night = view === "night";
  ctx.fillStyle = night ? "#020304" : view === "noon" ? "#4c5a48" : "#5d5446";
  ctx.beginPath();
  ctx.moveTo(0, h);
  for (let x = 0; x <= w; x += 8) ctx.lineTo(x, h * 0.74 - 50 * noise(x / 60, 0, 13, 100) - 40 * noise(x / 17, 0, 14, 100));
  ctx.lineTo(w, h);
  ctx.fill();
  ctx.fillStyle = night ? "#040506" : view === "noon" ? "#6b6f74" : "#776c62";
  ctx.fillRect(w * 0.55, h * 0.8, w * 0.45, h * 0.2);
  if (night) {
    for (let k = 0; k < 7; k++) {
      ctx.fillStyle = k % 3 ? "#ffcf8a" : "#ffe7c2";
      ctx.globalAlpha = 0.6 + 0.4 * random();
      ctx.fillRect(w * (0.58 + 0.38 * random()), h * (0.83 + 0.12 * random()), 7, 9);
    }
    ctx.globalAlpha = 1;
  }
  ctx.fillStyle = night ? "#000" : "#2a2622";
  ctx.fillRect(w / 2 - 7, 0, 14, h);
  ctx.fillRect(0, h * 0.38 - 6, w, 12);
  ctx.lineWidth = 22;
  ctx.strokeStyle = ctx.fillStyle;
  ctx.strokeRect(0, 0, w, h);
  const t = new THREE.CanvasTexture(canvas);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

/**
 * [material] with a detail texture over its own map: [detail]'s colour (0.5 is none) multiplies the surface's, tiled
 * [repeat] times as often as the map, so the stone keeps its large features over the whole table and its pits up close.
 */
export function withDetail<M extends THREE.MeshStandardMaterial>(material: M, detail: THREE.Texture, repeat: number): M {
  material.onBeforeCompile = (shader) => {
    shader.uniforms.detailMap = { value: detail };
    shader.uniforms.detailRepeat = { value: repeat };
    shader.fragmentShader = shader.fragmentShader
      .replace("#include <map_pars_fragment>", "#include <map_pars_fragment>\nuniform sampler2D detailMap;\nuniform float detailRepeat;")
      .replace("#include <map_fragment>", "#include <map_fragment>\ndiffuseColor.rgb *= 2.0 * texture2D( detailMap, vMapUv * detailRepeat ).r;");
  };
  material.customProgramCacheKey = () => `detail-${repeat}`;
  return material;
}
