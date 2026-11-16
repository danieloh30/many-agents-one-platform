import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { contrast, mix } from './contrast';
import { tokens, type ThemeName, type TokenName } from './tokens';

const BODY = 4.5;
const UI = 3;

// [foreground, background, minimum] — text pairs need 4.5:1, UI component colours 3:1.
const pairs: Array<[TokenName, TokenName, number]> = [
  ['text', 'bg', BODY],
  ['text', 'surface', BODY],
  ['text', 'surface-raised', BODY],
  ['text-muted', 'bg', BODY],
  ['text-muted', 'surface', BODY],
  ['text-muted', 'surface-raised', BODY],
  ['primary', 'surface', BODY], // links and primary text
  ['primary', 'bg', BODY],
  ['on-primary', 'primary', BODY], // filled primary buttons
  ['accent', 'surface', UI], // focus ring, live indicator
  ['accent', 'bg', UI],
  ['teal', 'surface', BODY], // status text in pills
  ['purple', 'surface', BODY],
  ['warn', 'surface', BODY],
  ['danger', 'surface', BODY],
  ['warn', 'bg', BODY],
  ['danger', 'bg', BODY],
  ['primary', 'surface-raised', UI],
  ['danger', 'surface-raised', UI],
  ['teal', 'surface-raised', UI],
];

// Pills render coloured text on an 8 % tint of the same colour over the surface (index.css .tint-*).
// Accent (cyan) is never used as pill text: accent pills use --text with a cyan icon.
const tinted: TokenName[] = ['teal', 'danger', 'warn', 'purple', 'primary'];

describe.each(['light', 'dark'] as ThemeName[])('%s theme contrast (WCAG 2.2 AA)', (theme) => {
  const t = tokens[theme];
  it.each(pairs)('%s on %s ≥ %d:1', (fg, bg, min) => {
    expect(contrast(t[fg], t[bg])).toBeGreaterThanOrEqual(min);
  });
  it.each(tinted)('%s text on its 8%% tint ≥ 4.5:1', (name) => {
    const bg = mix(t[name], t.surface, 0.08);
    expect(contrast(t[name], bg)).toBeGreaterThanOrEqual(BODY);
  });
});

describe('token sources agree', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
  const block = (selector: string) => {
    const start = css.indexOf(`${selector} {`);
    const end = css.indexOf('}', start);
    return css.slice(start, end).toLowerCase();
  };
  it.each([
    ['light', ':root'],
    ['dark', "[data-theme='dark']"],
  ] as const)('%s values in index.css match tokens.ts', (theme, selector) => {
    const text = block(selector);
    for (const [name, value] of Object.entries(tokens[theme])) {
      expect(text, `--${name}`).toContain(`--${name}: ${value.toLowerCase()};`);
    }
  });
});
