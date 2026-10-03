import { continueRender, delayRender, staticFile } from "remotion";
import { FLEX } from "./concept/type";

/**
 * Everything the video says, set in Instrument Sans as cursor.com sets its own type, from the face's variable file
 * (public/fonts, under the OFL) so a glyph's weight can be driven along the face's real `wght` axis, 400 to 700, by
 * font-variation-settings rather than snapped between static cuts.
 */
export const SANS = "Instrument Sans Variable";
export const WGHT = { min: 400, max: 700 } as const;

if (typeof document !== "undefined" && typeof FontFace !== "undefined") {
  const handle = delayRender("Loading Instrument Sans");
  const face = new FontFace(SANS, `url(${staticFile("fonts/InstrumentSans-Variable.ttf")})`, {
    weight: `${WGHT.min} ${WGHT.max}`,
    style: "normal",
    display: "block",
  });
  face
    .load()
    .then((loaded) => {
      // TypeScript's DOM lib types FontFaceSet without add(), which every browser has.
      (document.fonts as unknown as { add(face: FontFace): void }).add(loaded);
      continueRender(handle);
    })
    .catch((e: unknown) => {
      throw new Error(`Instrument Sans did not load: ${String(e)}`);
    });
}

/** A device's system UI (the status bar, the notification shade, the lock screen), in Android's own face. */
export const SYSTEM = FLEX;

export const TRACK = {
  /** Headlines, the wordmark, the end card's name, at rest. */
  display: "-0.03em",
  /** A line of small print or a URL. */
  text: "-0.005em",
};

export const COLOR = {
  /** The stage: warm near-black, lit a little at its centre ([STAGE]). */
  stage: "#0B0A09",
  stageLit: "#1C1916",
  /** Ink over the stage: warm off-white, and a muted second colour for small print. */
  ink: "#F2EFE8",
  inkSoft: "#8A857B",
  /** The one accent: amber, for the hot spot of a weight wave and the end card's bloom. */
  accent: "#F5A524",
  android: "#3DDC84",
  /** Ink over the green card. */
  onAndroid: "#0B1A10",
  /** The launcher icon's tile, behind the cube. */
  launcher: "#14120B",
  /** A device's glass and frame, graphite. */
  body: "#141416",
  rim: "#3C3C42",
  /** The status bar's clock and icons over a dark screen, and over a light one. */
  statusText: "#F2F2F2",
  statusTextOnLight: "#1B1B1F",
};
