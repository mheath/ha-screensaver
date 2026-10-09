import './snakes.css';
import { PALETTE } from '../../palette';
import type { Scene, SceneContext, SceneModule } from '../../scene';
import type { SnakeDef, SnakeOptions, WorldState } from '../../types';
import { assignColors, formatValue, normalize, pickGuests, shade, SnakeBody, speedFor, targetLength, type Dir } from './model';

/** Text-mode width; cells are twice as tall as wide, like 80×25 characters. */
const COLUMNS = 80;
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
        { x: Math.floor(this.random() * COLUMNS), y: Math.floor(this.random() * this.rows) },
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
        COLUMNS,
        this.rows,
        this.random,
      );
    }
    this.draw();
    this.frameId = requestAnimationFrame((next) => this.frame(next));
  }

  private draw(): void {
    const g = this.g;
    g.globalAlpha = 1;
    g.fillStyle = '#000000';
    g.fillRect(0, 0, this.canvas.width, this.canvas.height);
    for (const entry of this.entries) {
      g.fillStyle = entry.available ? entry.color : UNAVAILABLE_COLOR;
      const length = entry.body.cells.length;
      entry.body.cells.forEach((cell, index) => {
        g.globalAlpha = shade(index, length);
        g.fillRect(
          Math.floor(cell.x * this.cellWidth),
          Math.floor(this.offsetY + cell.y * this.cellHeight),
          Math.ceil(this.cellWidth),
          Math.ceil(this.cellHeight),
        );
      });
    }
    g.globalAlpha = 1;
  }
}
