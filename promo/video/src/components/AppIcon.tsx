import type React from "react";
import { COLOR } from "../theme";

/**
 * The app's launcher icon, from app/src/main/res/drawable/ic_launcher_foreground.xml on its #14120B background: the
 * Cursor cube's paths and face colours as the vector has them, cropped to the adaptive icon's visible 72 of 108 dp.
 */
export const AppIcon: React.FC<{ size: number; glow?: number }> = ({ size, glow = 0 }) => (
  <div
    style={{
      width: size,
      height: size,
      borderRadius: size * 0.235,
      overflow: "hidden",
      background: COLOR.launcher,
      boxShadow: `inset 0 0 0 ${Math.max(1, size * 0.008)}px rgba(255,255,255,0.09)${
        glow > 0 ? `, 0 ${size * 0.08}px ${size * 0.45}px rgba(0,0,0,${0.55 * glow})` : ""
      }`,
    }}
  >
    <svg width={size} height={size} viewBox="18 18 72 72" style={{ display: "block" }}>
      <g transform="translate(31.4788 28.2656) scale(0.096506)">
        <path
          fill="#72716D"
          d="M233.37,266.66l231.16,133.46c-1.42,2.46-3.48,4.56-6.03,6.03l-216.06,124.74c-5.61,3.24-12.53,3.24-18.14,0L8.24,406.15c-2.55-1.47-4.61-3.57-6.03-6.03l231.16-133.46h0Z"
        />
        <path
          fill="#55544F"
          d="M233.37,0v266.66L2.21,400.12c-1.42-2.46-2.21-5.3-2.21-8.24v-250.44c0-5.89,3.14-11.32,8.24-14.27L224.29,2.43c2.81-1.62,5.94-2.43,9.07-2.43h.01Z"
        />
        <path
          fill="#43413C"
          d="M464.52,133.2c-1.42-2.46-3.48-4.56-6.03-6.03L242.43,2.43c-2.8-1.62-5.93-2.43-9.06-2.43v266.66l231.16,133.46c1.42-2.46,2.21-5.3,2.21-8.24v-250.44c0-2.95-.78-5.77-2.21-8.24h-.01Z"
        />
        <path
          fill="#D6D5D2"
          d="M448.35,142.54c1.31,2.26,1.49,5.16,0,7.74l-209.83,363.42c-1.41,2.46-5.16,1.45-5.16-1.38v-239.48c0-1.91-.51-3.75-1.44-5.36l216.42-124.95h.01Z"
        />
        <path
          fill="#FFFFFF"
          d="M448.35,142.54l-216.42,124.95c-.92-1.6-2.26-2.96-3.92-3.92L20.62,143.83c-2.46-1.41-1.45-5.16,1.38-5.16h419.65c2.98,0,5.4,1.61,6.7,3.87Z"
        />
      </g>
    </svg>
  </div>
);
