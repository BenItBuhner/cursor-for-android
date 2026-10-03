import type React from "react";
import { clamp01, easeInOut, easeOut, lerp, progress } from "../math";
import { textWidth } from "../measure";
import { COLOR, SANS, TRACK, WGHT } from "../theme";

/** A line's height, in the type's size: a block of n lines stands n * size * LEADING tall. */
export const LEADING = 1.04;

/** The reveal: frames a glyph takes to rise into place, and the frames between one glyph's start and the next's. */
const RISE = 20;
const STAGGER = 1.1;
/** A second line's reveal and waves run this many frames behind the first's, so a sweep crosses the block on a diagonal. */
const LAG = 6;
/** The exit: frames a glyph takes to wipe up out of its line, and the frames between one glyph's start and the next's. */
const CLEAR = 12;
const CLEAR_STAGGER = 0.5;
/** The tracking, in ems, tightening from the first value to [TRACK.display] as the type lands. */
const TRACK_FROM = -0.008;
/** The line mask's feathered top edge: clear at the edge, solid by 0.15em down, just over where the tallest glyph stands. */
const FEATHER = "linear-gradient(to bottom, rgba(0,0,0,0) 0, #000 0.15em, #000 100%)";

/**
 * A weight wave: a crest in the type's weight that sweeps across the text from the left edge to the right over [frames]
 * from [at], peaking at [crest] on the face's `wght` axis, with a shallow trough trailing it. Its width is [SIGMA] of
 * the text's width either side of the crest; it starts and ends off the text's ends, so the type is at [REST] before
 * and after.
 */
export type Wave = { at: number; frames: number; crest: number };
const SIGMA = 0.17;
const REST = 500;
const TROUGH = 430;
/** The crest of the wave each headline rides in on, and of the gentler one it breathes before it clears. */
const ENTRANCE: Omit<Wave, "at"> = { frames: 56, crest: WGHT.max };
const BREATH: Omit<Wave, "at"> = { frames: 64, crest: 610 };
/** A text has room for the breath only if it stands at least this many frames. */
const BREATHING_ROOM = 150;

/** The weight at [u] across the text (0 its left edge, 1 its right) at frame [f] under [waves]. */
export function weightAt(u: number, f: number, waves: readonly Wave[]): number {
  let w = REST;
  for (const wave of waves) {
    const p = progress(f, wave.at, wave.at + wave.frames);
    if (p <= 0 || p >= 1) continue;
    const c = lerp(-3 * SIGMA, 1 + 3 * SIGMA, p);
    const d = (u - c) / SIGMA;
    const trail = (u - c + 1.7 * SIGMA) / SIGMA;
    w += (wave.crest - REST) * Math.exp(-d * d) - (REST - TROUGH) * Math.exp(-trail * trail);
  }
  return Math.max(WGHT.min, Math.min(WGHT.max, w));
}

/** The waves a text standing from [at] to [until] rides: the entrance, and the breath if it has the room. */
export function wavesOf(at: number, until?: number): Wave[] {
  const waves: Wave[] = [{ at: at + 2, ...ENTRANCE }];
  if (until !== undefined && until - at >= BREATHING_ROOM) waves.push({ at: until - BREATH.frames - CLEAR - 4, ...BREATH });
  return waves;
}

type Glyph = { ch: string; u: number; start: number; waves: readonly Wave[] };

/**
 * [lines] of kinetic type at [size] pixels, each glyph rising into place from under its line at frame [at] (or, given
 * [wordAt], each word from its own frame), one after another from left to right and the second line a little behind
 * the first: it comes up out of a mask, blurred, light and loose, and lands sharp, at its weight and tracked in, as a
 * weight wave sweeps across the line behind the landing glyphs; a gentler wave breathes across it before, given
 * [until], it wipes up and out of its line glyph by glyph, done on [until]'s frame. Each glyph stands centred in a cell
 * as wide as it runs at rest, so the line holds still as the waves swell and thin the glyphs. The block is laid out as
 * its parent has it; [align] sets the lines' alignment within it.
 */
export const Kinetic: React.FC<{
  lines: readonly string[];
  f: number;
  at: number;
  until?: number;
  size: number;
  align?: "left" | "center";
  color?: string;
  /** The frame each word starts rising at, in reading order, when not one after another from [at]. */
  wordAt?: readonly number[];
  /** The waves the type rides, when not the ones [wavesOf] gives it. */
  waves?: readonly Wave[];
}> = ({ lines, f, at, until, size, align = "left", color = COLOR.ink, wordAt, waves }) => {
  const glyphLines = layout(lines, at, until, wordAt, waves);
  const glyphs = glyphLines.flat();
  const n = glyphs.length;
  const last = Math.max(...glyphs.map((g) => g.start));
  const landed = easeOut(progress(f, at, last + RISE));
  const tracking = lerp(TRACK_FROM, parseFloat(TRACK.display), landed) * size;
  const clearFrom = until === undefined ? Infinity : until - CLEAR - CLEAR_STAGGER * (n - 1);
  let k = 0;
  return (
    <div style={{ fontFamily: SANS, fontSize: size, lineHeight: LEADING, color, textAlign: align, whiteSpace: "pre" }}>
      {glyphLines.map((line, i) => (
        <div
          key={lines[i]}
          style={{
            display: "block",
            overflow: "hidden",
            // The mask: the face's content area overruns a line of LEADING by 0.09em each way, its deepest descender
            // ends 0.055em under the line, and its tallest glyph 0.15em under the line's top. Its top edge is
            // feathered over the room above the tallest glyph, so a glyph wiping up dissolves rather than slices.
            padding: "0.02em 0.04em 0.08em",
            margin: "-0.02em -0.04em -0.08em",
            WebkitMaskImage: FEATHER,
            maskImage: FEATHER,
          }}
        >
          {line.map((g, j) => {
            const rise = easeOut(progress(f, g.start, g.start + RISE));
            const leaveAt = clearFrom + CLEAR_STAGGER * k;
            k++;
            const leave = easeInOut(progress(f, leaveAt, leaveAt + CLEAR));
            const wght = lerp(WGHT.min, weightAt(g.u, f, g.waves), rise);
            const y = (1 - rise) * 0.5 - leave * 0.6;
            const blur = (1 - rise) * 0.05 * size;
            const opacity = clamp01(rise ** 0.6) * (1 - leave);
            const last = j === line.length - 1;
            const cell = textWidth(g.ch, size, REST);
            // The glyph, wider or narrower than its cell at this weight, centred in it.
            const centre = (cell - textWidth(g.ch, size, Math.round(wght))) / 2;
            return (
              <span
                key={`${g.ch}-${j}`}
                style={{
                  display: "inline-block",
                  width: cell,
                  marginRight: last ? 0 : tracking,
                  textAlign: "left",
                  fontVariationSettings: `"wght" ${wght.toFixed(1)}`,
                  transform: `translateX(${centre.toFixed(2)}px) translateY(${(y * 100).toFixed(2)}%)`,
                  filter: blur > 0.15 ? `blur(${blur.toFixed(2)}px)` : undefined,
                  opacity,
                }}
              >
                {g.ch}
              </span>
            );
          })}
        </div>
      ))}
    </div>
  );
};

/** Each line's glyphs: their characters, their place across the line, the frame each starts rising at and their waves. */
function layout(lines: readonly string[], at: number, until: number | undefined, wordAt?: readonly number[], given?: readonly Wave[]): Glyph[][] {
  let word = 0;
  let g = 0;
  return lines.map((line, i) => {
    const chars = [...line];
    const lag = i * LAG;
    const lineWaves = given ?? wavesOf(at + lag, until);
    const out: Glyph[] = [];
    let inWord = 0;
    const words = line.split(" ");
    const wordStarts: number[] = [];
    let col = 0;
    for (const w of words) {
      wordStarts.push(col);
      col += [...w].length + 1;
    }
    let wordIndex = 0;
    chars.forEach((ch, j) => {
      if (ch === " ") {
        wordIndex++;
        word++;
        inWord = 0;
        out.push({ ch, u: (j + 0.5) / chars.length, start: -Infinity, waves: lineWaves });
        return;
      }
      const start = wordAt ? (wordAt[word] ?? at) + STAGGER * inWord : at + lag + STAGGER * g;
      const waves = wordAt ? [{ at: (wordAt[word] ?? at) + 2, frames: Math.round(ENTRANCE.frames * 0.75), crest: ENTRANCE.crest }] : lineWaves;
      out.push({
        ch,
        u: wordAt ? (j - wordStarts[wordIndex]! + 0.5) / [...words[wordIndex]!].length : (j + 0.5) / chars.length,
        start,
        waves,
      });
      inWord++;
      g++;
    });
    word++;
    return out;
  });
}

/**
 * [lines] of kinetic type ([Kinetic]) at [size] pixels, its top left at [x, y], or centred across the frame at [y]
 * with [align] "center".
 */
export const Headline: React.FC<{
  lines: readonly string[];
  f: number;
  at: number;
  until?: number;
  size: number;
  x: number;
  y: number;
  align?: "left" | "center";
  color?: string;
  wordAt?: readonly number[];
}> = ({ lines, f, at, until, size, x, y, align = "left", color, wordAt }) => (
  <div style={{ position: "absolute", left: align === "center" ? 0 : x, right: align === "center" ? 0 : undefined, top: y }}>
    <Kinetic lines={lines} f={f} at={at} until={until} size={size} align={align} color={color} wordAt={wordAt} />
  </div>
);
