/** The 16-color text-mode palette, by the names used in configuration. */
export const PALETTE = {
  black: '#000000',
  blue: '#0000AA',
  green: '#00AA00',
  cyan: '#00AAAA',
  red: '#AA0000',
  magenta: '#AA00AA',
  brown: '#AA5500',
  'light-gray': '#AAAAAA',
  'dark-gray': '#555555',
  'light-blue': '#5555FF',
  'light-green': '#55FF55',
  'light-cyan': '#55FFFF',
  'light-red': '#FF5555',
  'light-magenta': '#FF55FF',
  yellow: '#FFFF55',
  white: '#FFFFFF',
} as const;

export type PaletteName = keyof typeof PALETTE;

/**
 * Order in which unassigned snakes get colors: bright colors first, then the
 * darker ones, and last the two that are hard to see on black. Black is the
 * background and is never a snake color.
 */
export const SNAKE_COLOR_PREFERENCE: readonly PaletteName[] = [
  'light-green', 'light-cyan', 'light-red', 'yellow', 'light-magenta', 'white', 'light-blue',
  'green', 'cyan', 'red', 'magenta', 'brown', 'light-gray',
  'blue', 'dark-gray',
];
