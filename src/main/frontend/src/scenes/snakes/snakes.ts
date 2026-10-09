import './snakes.css';
import { PALETTE } from '../../palette';
import type { Scene, SceneContext, SceneModule } from '../../scene';
import type { SnakeDef, SnakeOptions, WorldState } from '../../types';
import {
  assignColors, formatValue, normalize, pickGuests, shade, SnakeBody, speedFor, targetLength, type Dir, type ShadeGlyph,
} from './model';

/** Text-mode width; cells are twice as tall as wide, like 80×25 characters. */
const COLUMNS = 80;
/** Each snake segment is two characters side by side (██ ▓▓ ▒▒ ░░), so snakes move on a half-width grid. */
const SEGMENT_COLUMNS = COLUMNS / 2;
const UNAVAILABLE_COLOR = PALETTE['dark-gray'];

interface Entry {
  def: SnakeDef;
  color: string;
  body: SnakeBody;
  value: number | null;
  available: boolean;
  swatch: HTMLElement;
  text: HTMLElement;
}

export const snakesModule: SceneModule = {
  type: 'snakes',
  isEligible: (config) => config.snakes.pinned.length + config.snakes.guests.length > 0,
  create: () => new SnakesScene(),
};

class SnakesScene implements Scene {
  private canvas!: HTMLCanvasElement;
  private g!: CanvasRenderingContext2D;
  private options!: SnakeOptions;
  private entries: Entry[] = [];
  private random: () => number = Math.random;
  private rows = 1;
  private cellWidth = 1;
  private cellHeight = 2;
  private offsetY = 0;
  private fontFamily = 'monospace';
  /** Pre-rendered cell-sized glyphs, keyed by glyph and color. */
  private sprites = new Map<string, HTMLCanvasElement>();
  private frameId = 0;
  private lastFrame = 0;

  mount(container: HTMLElement, ctx: SceneContext): void {
    this.options = ctx.config.snakes;
    this.random = ctx.random;
    container.classList.add('snakes-scene');
    const field = document.createElement('div');
    field.className = 'field';
    this.canvas = document.createElement('canvas');
    field.append(this.canvas);
    const legend = document.createElement('div');
    legend.className = 'legend';
    container.append(field, legend);
    // Draw body glyphs in the legend's font so they match its swatches.
    this.fontFamily = getComputedStyle(legend).fontFamily;

    const chosen = [...this.options.pinned, ...pickGuests(this.options.guests, this.options.guestCount, ctx.random)];
    const colors = assignColors(chosen);
    const items = chosen.map((def) => {
      const item = document.createElement('span');
      const swatch = document.createElement('span');
      swatch.className = 'swatch';
      swatch.textContent = '█▓▒░';
      const text = document.createElement('span');
      item.append(swatch, text);
      legend.append(item);
      return { def, swatch, text, color: PALETTE[colors.get(def.id) ?? 'light-gray'] };
    });

    // Fill the legend before measuring, so the snake field gets the remaining height.
    this.entries = items.map((item) => ({ ...item, body: new SnakeBody({ x: 0, y: 0 }, 0), value: null, available: false }));
    this.applyState(ctx.state);
    this.layout();
    for (const entry of this.entries) {
      entry.body = new SnakeBody(
        { x: Math.floor(this.random() * SEGMENT_COLUMNS), y: Math.floor(this.random() * this.rows) },
        Math.floor(this.random() * 4) as Dir,
      );
    }
    this.lastFrame = performance.now();
    this.frameId = requestAnimationFrame((time) => this.frame(time));
  }

  update(state: WorldState): void {
    this.applyState(state);
  }

  unmount(): void {
    cancelAnimationFrame(this.frameId);
  }

  private applyState(state: WorldState): void {
    for (const entry of this.entries) {
      const current = state.snakes.find((s) => s.id === entry.def.id);
      entry.value = current?.value ?? null;
      entry.available = !!current && current.available && current.value !== null;
      const unit = entry.def.unit ?? current?.unit ?? null;
      entry.text.textContent = `${entry.def.label} (${formatValue(entry.value, unit, entry.def.precision, entry.available)})`;
      entry.swatch.style.color = entry.available ? entry.color : UNAVAILABLE_COLOR;
    }
  }

  private layout(): void {
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.max(1, Math.round(this.canvas.clientWidth * dpr));
    this.canvas.height = Math.max(1, Math.round(this.canvas.clientHeight * dpr));
    this.g = this.canvas.getContext('2d')!;
    this.cellWidth = this.canvas.width / COLUMNS;
    this.cellHeight = this.cellWidth * 2;
    this.rows = Math.max(1, Math.floor(this.canvas.height / this.cellHeight));
    this.offsetY = (this.canvas.height - this.rows * this.cellHeight) / 2;
    this.sprites.clear();
  }

  /** `glyph` in `color`, stretched so a full block exactly fills one cell. */
  private sprite(glyph: ShadeGlyph, color: string): HTMLCanvasElement {
    const key = glyph + color;
    let sprite = this.sprites.get(key);
    if (!sprite) {
      sprite = document.createElement('canvas');
      sprite.width = Math.ceil(this.cellWidth);
      sprite.height = Math.ceil(this.cellHeight);
      const g = sprite.getContext('2d')!;
      g.fillStyle = color;
      this.sprites.set(key, sprite);
      // A font's full block has anti-aliased edges that leave seams between cells; fill it exactly.
      if (glyph === '█') {
        g.fillRect(0, 0, sprite.width, sprite.height);
        return sprite;
      }
      g.font = `100px ${this.fontFamily}`;
      const block = g.measureText('█');
      const blockWidth = block.actualBoundingBoxLeft + block.actualBoundingBoxRight;
      const blockHeight = block.actualBoundingBoxAscent + block.actualBoundingBoxDescent;
      g.scale(sprite.width / blockWidth, sprite.height / blockHeight);
      g.fillText(glyph, block.actualBoundingBoxLeft, block.actualBoundingBoxAscent);
    }
    return sprite;
  }

  private frame(time: number): void {
    const seconds = Math.min(0.25, (time - this.lastFrame) / 1000);
    this.lastFrame = time;
    for (const entry of this.entries) {
      // Unavailable snakes freeze where they are.
      if (!entry.available || entry.value === null) continue;
      const t = normalize(entry.value, entry.def.min, entry.def.max);
      entry.body.advance(
        seconds,
        speedFor(t, this.options.minSpeed, this.options.maxSpeed),
        targetLength(t, this.options.minLength, this.options.maxLength),
        SEGMENT_COLUMNS,
        this.rows,
        this.random,
      );
    }
    this.draw();
    this.frameId = requestAnimationFrame((next) => this.frame(next));
  }

  private draw(): void {
    const g = this.g;
    g.fillStyle = '#000000';
    g.fillRect(0, 0, this.canvas.width, this.canvas.height);
    // Paint every snake's segments tail-first, so a segment nearer its head is never covered by one nearer a tail.
    const segments = this.entries.flatMap((entry) => {
      const color = entry.available ? entry.color : UNAVAILABLE_COLOR;
      const length = entry.body.cells.length;
      return entry.body.cells.map((cell, index) => ({ cell, color, glyph: shade(index, length), rank: index / length }));
    });
    segments.sort((a, b) => b.rank - a.rank);
    for (const { cell, color, glyph } of segments) {
      // Clear the segment first so a ░ over another snake shows black gaps, as in text mode.
      const left = Math.floor(cell.x * 2 * this.cellWidth);
      const middle = Math.floor((cell.x * 2 + 1) * this.cellWidth);
      const y = Math.floor(this.offsetY + cell.y * this.cellHeight);
      g.fillStyle = '#000000';
      g.fillRect(left, y, Math.ceil(2 * this.cellWidth), Math.ceil(this.cellHeight));
      const sprite = this.sprite(glyph, color);
      g.drawImage(sprite, left, y);
      g.drawImage(sprite, middle, y);
    }
  }
}
