import { loadFont as loadInter } from "@remotion/google-fonts/Inter";
import { loadFont as loadMono } from "@remotion/google-fonts/JetBrainsMono";

export const SANS = loadInter("normal", { weights: ["400", "500", "600", "700", "800"], subsets: ["latin"] }).fontFamily;
export const MONO = loadMono("normal", { weights: ["400", "500"], subsets: ["latin"] }).fontFamily;

/** The app's own dark theme is a warm near-black; the video's room is a shade under it. */
export const COLOR = {
  room: "#0a0908",
  text: "#f4f1ea",
  dim: "#aaa59a",
  faint: "#6d695f",
  accent: "#e9b872",
  launcher: "#14120b",
};
