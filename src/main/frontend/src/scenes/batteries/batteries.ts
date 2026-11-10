import './batteries.css';
import type { Scene, SceneContext, SceneModule } from '../../scene';
import type { LowBattery, WorldState } from '../../types';
import { batteryLabel, fillFraction, layout, PER_ROW } from './model';

const SVG_NS = 'http://www.w3.org/2000/svg';
const DEAD_COLOR = '#8a93a6';
const LOW_COLOR = '#cfd6e4';
const FILL_COLOR = '#ffb547';
/** Tallest fill, inside the body outline; its bottom edge is y=128. */
const FILL_HEIGHT = 110;
const FILL_BOTTOM = 128;

export const lowBatteriesModule: SceneModule = {
  type: 'low-batteries',
  isEligible: (_, state) => state.lowBatteries.length > 0,
  create: () => new LowBatteriesScene(),
};

class LowBatteriesScene implements Scene {
  private root!: HTMLElement;
  private batteries: LowBattery[] = [];

  mount(container: HTMLElement, ctx: SceneContext): void {
    this.batteries = ctx.state.lowBatteries;
    this.root = el('div', 'low-batteries-scene');
    container.append(this.root);
    this.render();
  }

  update(state: WorldState): void {
    // An empty list keeps the last one on screen until the scene rotates away.
    if (state.lowBatteries.length === 0) return;
    this.batteries = state.lowBatteries;
    this.render();
  }

  unmount(): void {
    // The host removes the scene's DOM.
  }

  private render(): void {
    const { shown, rows, more } = layout(this.batteries.length);
    const grid = el('div', 'grid');
    grid.style.setProperty('--count', String(Math.min(shown, PER_ROW)));
    grid.classList.toggle('two-rows', rows > 1);
    for (const battery of this.batteries.slice(0, shown)) {
      const cell = el('div', 'battery');
      cell.append(drawing(battery), el('div', 'name', battery.name));
      grid.append(cell);
    }
    this.root.replaceChildren(el('h1', 'heading', 'Running low'), grid);
    if (more > 0) {
      this.root.append(el('div', 'more', `and ${more} more`));
    }
  }
}

function drawing(battery: LowBattery): SVGElement {
  const dead = battery.status === 'dead';
  const color = dead ? DEAD_COLOR : LOW_COLOR;
  const svg = svgEl('svg', { viewBox: '0 0 80 140' });
  svg.append(
    svgEl('rect', { x: 28, y: 2, width: 24, height: 12, rx: 3, fill: color }),
    svgEl('rect', {
      x: 6, y: 12, width: 68, height: 124, rx: 12, fill: 'none', stroke: color, 'stroke-width': 6,
      ...(dead ? { 'stroke-dasharray': '10 7' } : {}),
    }),
  );
  if (dead) {
    svg.append(
      glyph('z', { x: 40, y: 66, 'font-size': 22, fill: DEAD_COLOR }),
      glyph('z', { x: 52, y: 52, 'font-size': 16, fill: DEAD_COLOR }),
      glyph(batteryLabel(battery), { x: 40, y: 104, 'font-size': 15, fill: '#e8e8e8', 'font-weight': 800, 'letter-spacing': 1.5 }),
    );
  } else {
    const height = fillFraction(battery) * FILL_HEIGHT;
    svg.append(
      svgEl('rect', { x: 15, y: FILL_BOTTOM - height, width: 50, height, rx: 2, fill: FILL_COLOR }),
      glyph(batteryLabel(battery), { x: 40, y: 84, 'font-size': 24, fill: '#ffffff' }),
    );
  }
  return svg;
}

function glyph(text: string, attrs: Record<string, string | number>): SVGElement {
  const node = svgEl('text', { 'text-anchor': 'middle', 'font-weight': 700, ...attrs });
  node.textContent = text;
  return node;
}

function svgEl(tag: string, attrs: Record<string, string | number>): SVGElement {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [name, value] of Object.entries(attrs)) node.setAttribute(name, String(value));
  return node;
}

function el(tag: string, className: string, text?: string): HTMLElement {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
