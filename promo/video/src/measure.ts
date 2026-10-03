import { SANS, TRACK } from "./theme";

const cache = new Map<string, number>();

/**
 * How wide [text] runs at [size] pixels in the display face at weight [wght], tracked as headlines are, in pixels: as
 * the browser measures it once the face has loaded (theme.ts holds the render until it has), or, with no browser to
 * ask, the face's average advance.
 */
export function textWidth(text: string, size: number, wght = 500): number {
  const key = `${text}|${size}|${wght}`;
  const hit = cache.get(key);
  if (hit !== undefined) return hit;
  let width = text.length * size * 0.56;
  let loaded = false;
  if (typeof document !== "undefined") {
    const font = `${wght} ${size}px "${SANS}"`;
    const ctx = document.createElement("canvas").getContext("2d");
    if (ctx && document.fonts.check(font)) {
      ctx.font = font;
      width = ctx.measureText(text).width;
      loaded = true;
    }
  }
  width += (text.length - 1) * parseFloat(TRACK.display) * size;
  // Before the face has loaded the browser measures its fallback, which is not worth keeping.
  if (loaded) cache.set(key, width);
  return width;
}
