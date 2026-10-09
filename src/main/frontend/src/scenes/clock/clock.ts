import './clock.css';
import type { Scene, SceneContext, SceneModule } from '../../scene';
import { drift, formatDigital, handAngles, placeWithin, type Box, type Point } from './layout';

const SVG_NS = 'http://www.w3.org/2000/svg';

interface Face {
  element: HTMLElement;
  render(): void;
}

export const clockModule: SceneModule = {
  type: 'clock',
  isEligible: () => true,
  create: () => new ClockScene(),
};

class ClockScene implements Scene {
  private container!: HTMLElement;
  private face!: Face;
  private position: Point = { x: 0, y: 0 };
  private random: () => number = Math.random;
  private readonly timers: number[] = [];

  mount(container: HTMLElement, ctx: SceneContext): void {
    this.container = container;
    this.random = ctx.random;
    container.classList.add('clock-scene');
    this.face = ctx.random() < 0.5 ? analogFace() : digitalFace(ctx.config.clock.use24Hour);
    container.appendChild(this.face.element);
    this.face.render();
    this.position = placeWithin(this.area(), this.faceBox(), this.random);
    this.applyPosition();
    this.timers.push(window.setInterval(() => this.face.render(), 1000));
    this.timers.push(
      window.setInterval(() => {
        this.position = drift(this.position, this.area(), this.faceBox(), this.random);
        this.applyPosition();
      }, 60_000),
    );
  }

  unmount(): void {
    this.timers.forEach((id) => window.clearInterval(id));
  }

  private area(): Box {
    return { width: this.container.clientWidth, height: this.container.clientHeight };
  }

  private faceBox(): Box {
    return { width: this.face.element.offsetWidth, height: this.face.element.offsetHeight };
  }

  private applyPosition(): void {
    this.face.element.style.transform = `translate(${this.position.x}px, ${this.position.y}px)`;
  }
}

function digitalFace(use24Hour: boolean): Face {
  const element = document.createElement('div');
  element.className = 'clock-face clock-digital';
  const time = document.createElement('div');
  time.className = 'time';
  const date = document.createElement('div');
  date.className = 'date';
  element.append(time, date);
  return {
    element,
    render() {
      const now = new Date();
      const formatted = formatDigital(now, use24Hour);
      time.textContent = formatted.time;
      if (formatted.suffix) {
        const suffix = document.createElement('span');
        suffix.className = 'suffix';
        suffix.textContent = formatted.suffix;
        time.append(suffix);
      }
      date.textContent = now.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' });
    },
  };
}

function analogFace(): Face {
  const element = document.createElement('div');
  element.className = 'clock-face clock-analog';
  const svg = svgElement('svg', { viewBox: '0 0 200 200' });
  svg.append(svgElement('circle', { cx: 100, cy: 100, r: 96, class: 'ring' }));
  for (let i = 0; i < 12; i++) {
    svg.append(
      svgElement('line', {
        x1: 100, y1: 10, x2: 100, y2: i % 3 === 0 ? 26 : 18,
        class: 'tick', transform: `rotate(${i * 30} 100 100)`,
      }),
    );
  }
  const hour = svgElement('line', { x1: 100, y1: 108, x2: 100, y2: 55, class: 'hand hour' });
  const minute = svgElement('line', { x1: 100, y1: 112, x2: 100, y2: 30, class: 'hand minute' });
  const second = svgElement('line', { x1: 100, y1: 118, x2: 100, y2: 22, class: 'hand second' });
  svg.append(hour, minute, second, svgElement('circle', { cx: 100, cy: 100, r: 4, class: 'hub' }));
  element.append(svg);
  return {
    element,
    render() {
      const angles = handAngles(new Date());
      hour.setAttribute('transform', `rotate(${angles.hour} 100 100)`);
      minute.setAttribute('transform', `rotate(${angles.minute} 100 100)`);
      second.setAttribute('transform', `rotate(${angles.second} 100 100)`);
    },
  };
}

function svgElement(name: string, attributes: Record<string, string | number>): SVGElement {
  const node = document.createElementNS(SVG_NS, name);
  for (const [key, value] of Object.entries(attributes)) {
    node.setAttribute(key, String(value));
  }
  return node;
}
