/** Colour tokens from docs/ui-design.md §4.1 (mirrors src/index.css; kept in sync by contrast.test.ts). */
export const tokens = {
  light: {
    bg: '#F5F8FC',
    surface: '#FFFFFF',
    'surface-raised': '#EDF2FA',
    border: '#C9D5E8',
    text: '#0A1838',
    'text-muted': '#44557A',
    primary: '#1F5EFF',
    accent: '#007C99',
    teal: '#0B7A6B',
    purple: '#6B3FD4',
    warn: '#9A5B00',
    danger: '#B3261E',
    'on-primary': '#FFFFFF',
  },
  dark: {
    bg: '#07122B',
    surface: '#0D1D40',
    'surface-raised': '#14295A',
    border: '#274079',
    text: '#EAF1FF',
    'text-muted': '#A9BBDE',
    primary: '#5C8DFF',
    accent: '#22D3EE',
    teal: '#2DD4BF',
    purple: '#A78BFA',
    warn: '#FBBF24',
    danger: '#FF7A70',
    'on-primary': '#07122B',
  },
} as const;

export type ThemeName = keyof typeof tokens;
export type TokenName = keyof (typeof tokens)['light'];
