/** WCAG 2.2 relative luminance and contrast ratio for #RRGGBB colours. */
export function hexToRgb(hex: string): [number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m?.[1]) throw new Error(`Not a #RRGGBB colour: ${hex}`);
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function channel(c: number): number {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}

export function luminance(hex: string): number {
  const [r, g, b] = hexToRgb(hex);
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

export function contrast(a: string, b: string): number {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** sRGB mix equivalent to CSS `color-mix(in srgb, fg p%, bg)`. */
export function mix(fg: string, bg: string, p: number): string {
  const f = hexToRgb(fg);
  const g = hexToRgb(bg);
  const out = f.map((v, i) => Math.round(v * p + (g[i] ?? 0) * (1 - p)));
  return `#${out.map((v) => v.toString(16).padStart(2, '0')).join('')}`;
}
