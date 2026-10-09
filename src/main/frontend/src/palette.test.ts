import { describe, expect, it } from 'vitest';
import { PALETTE, SNAKE_COLOR_PREFERENCE } from './palette';

describe('palette', () => {
  it('has the 16 text-mode colors', () => {
    expect(Object.keys(PALETTE)).toHaveLength(16);
    expect(PALETTE.black).toBe('#000000');
    expect(PALETTE['dark-gray']).toBe('#555555');
    expect(PALETTE.yellow).toBe('#FFFF55');
  });

  it('offers 15 distinct snake colors, never black', () => {
    expect(SNAKE_COLOR_PREFERENCE).toHaveLength(15);
    expect(new Set(SNAKE_COLOR_PREFERENCE).size).toBe(15);
    expect(SNAKE_COLOR_PREFERENCE).not.toContain('black');
  });

  it('assigns the hard-to-see colors last', () => {
    expect(SNAKE_COLOR_PREFERENCE.slice(-2)).toEqual(['blue', 'dark-gray']);
  });
});
