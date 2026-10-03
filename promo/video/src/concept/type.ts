import type React from "react";
import { continueRender, delayRender, staticFile } from "remotion";

/**
 * The film's one family: Roboto Flex, Android's own variable face, self-hosted so every axis is there to set. Weight and
 * width are fixed per role; optical size always follows the size the type is drawn at; grade is the only axis that moves.
 */
export const FLEX = "Roboto Flex";

if (typeof document !== "undefined" && typeof FontFace !== "undefined") {
  const handle = delayRender("Loading Roboto Flex");
  const face = new FontFace(FLEX, `url(${staticFile("fonts/RobotoFlex.woff2")}) format("woff2")`, { weight: "100 1000", stretch: "25% 151%" });
  face
    .load()
    .then((loaded) => {
      (document.fonts as unknown as { add(face: FontFace): void }).add(loaded);
      continueRender(handle);
    })
    .catch((error: unknown) => {
      throw error;
    });
}

/**
 * A role in the type system: its weight and width, its leading, and its tracking in ems at 24 px and at 144 px, eased
 * between by size. Roboto Flex already tightens its own spacing as optical size rises, so display tracking only has a
 * little left to take out; any more and "it." closes up at headline sizes.
 */
export type Role = { wght: number; wdth: number; tracking: [number, number]; leading: number };

export const ROLE = {
  /** Headlines: one weight, never animated through a middle one. */
  display: { wght: 600, wdth: 100, tracking: [0, -0.01], leading: 1.06 },
  /** The few words that sit small beside the product: a caption's weight and open tracking. */
  caption: { wght: 450, wdth: 100, tracking: [0.006, 0.0], leading: 1.25 },
} satisfies Record<string, Role>;

/** [role]'s tracking at [size] px. */
export function trackingAt(role: Role, size: number): number {
  const u = Math.max(0, Math.min(1, (size - 24) / 120));
  const k = u * u * (3 - 2 * u);
  return role.tracking[0] + (role.tracking[1] - role.tracking[0]) * k;
}

/**
 * The CSS for [role] at [size] pixels on screen, at [grade] (0 to 100: weight without width, so a line never reflows).
 * Optical size is the on-screen size, clamped to the font's range, so a line pushed in on by the camera redraws for
 * the size it reaches, as the type does on the device.
 */
export function typeStyle(role: Role, size: number, grade = 0): React.CSSProperties {
  const opsz = Math.max(8, Math.min(144, size));
  return {
    fontFamily: FLEX,
    fontSize: size,
    lineHeight: role.leading,
    letterSpacing: `${trackingAt(role, size).toFixed(4)}em`,
    fontVariationSettings: `"wght" ${role.wght}, "wdth" ${role.wdth}, "opsz" ${opsz.toFixed(1)}, "GRAD" ${grade.toFixed(1)}`,
    fontOpticalSizing: "none",
    fontKerning: "normal",
    fontSynthesis: "none",
    WebkitFontSmoothing: "antialiased",
    textRendering: "optimizeLegibility",
  };
}
