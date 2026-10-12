import './calendar.css';
import type { Scene, SceneContext, SceneModule } from '../../scene';
import type { CalendarDef, CalendarEvent, KioskConfig, WorldState } from '../../types';
import {
  addDays, chooseDay, type Day, eventsOn, formatRange, hourLabel, hourOfDay, hourSpan, isShort, packLanes, startOfDay,
} from './model';

const NOW_TICK_MS = 30_000;
/** For events whose calendar isn't in the config (shouldn't happen). */
const FALLBACK_COLORS = { color: '#64748b', textColor: '#ffffff' };

export const calendarModule: SceneModule = {
  type: 'calendar',
  isEligible: (config, state) => chooseDay(state.calendar.events, new Date(), config.calendar.allDayUntil) !== null,
  create: () => new CalendarScene(),
};

class CalendarScene implements Scene {
  private root!: HTMLElement;
  private config!: KioskConfig;
  private events: CalendarEvent[] = [];
  private day: Day = 'today';
  private timer = 0;

  mount(container: HTMLElement, ctx: SceneContext): void {
    this.config = ctx.config;
    this.events = ctx.state.calendar.events;
    // Chosen once per appearance.
    this.day = chooseDay(this.events, new Date(), this.config.calendar.allDayUntil) ?? 'today';
    this.root = el('div', `calendar-scene ${this.day}`);
    container.append(this.root);
    this.render();
    if (this.day === 'today') {
      this.timer = window.setInterval(() => this.render(), NOW_TICK_MS);
    }
  }

  update(state: WorldState): void {
    this.events = state.calendar.events;
    this.render();
  }

  unmount(): void {
    window.clearInterval(this.timer);
  }

  private colorsFor(event: CalendarEvent): Pick<CalendarDef, 'color' | 'textColor'> {
    return this.config.calendar.calendars.find((c) => c.id === event.calendar) ?? FALLBACK_COLORS;
  }

  private render(): void {
    const now = new Date();
    const dayStart = startOfDay(this.day === 'today' ? now : addDays(now, 1));
    const { allDay, timed } = eventsOn(this.events, dayStart);
    const use24Hour = this.config.clock.use24Hour;

    this.root.replaceChildren(header(this.day, dayStart));
    this.root.classList.toggle('all-day-only', timed.length === 0);
    if (allDay.length > 0) {
      const strip = el('div', 'all-day');
      for (const event of allDay) strip.append(colored(el('span', 'pill', event.title), this.colorsFor(event)));
      this.root.append(strip);
    }
    if (timed.length === 0) return;

    const { startHour, endHour } = hourSpan(timed, dayStart);
    const top = (hour: number) => `${((hour - startHour) / (endHour - startHour)) * 100}%`;
    const grid = el('div', 'grid');
    for (let hour = startHour; hour <= endHour; hour++) {
      const line = el('div', 'hour');
      line.style.top = top(hour);
      line.append(el('span', 'label', hourLabel(hour, use24Hour)));
      grid.append(line);
    }
    for (const placed of packLanes(timed)) {
      const from = top(hourOfDay(placed.start, dayStart));
      const to = top(hourOfDay(placed.end, dayStart));
      const block = colored(el('div', 'event'), this.colorsFor(placed.event));
      block.classList.toggle('past', this.day === 'today' && placed.end <= now);
      block.classList.toggle('short', isShort(placed));
      block.style.top = from;
      block.style.height = `calc(${to} - ${from} - 2px)`;
      block.style.left = `${(placed.lane / placed.laneCount) * 100}%`;
      block.style.width = `calc(${100 / placed.laneCount}% - 4px)`;
      block.append(
        el('span', 'title', placed.event.title),
        el('span', 'time', formatRange(placed.start, placed.end, use24Hour)),
      );
      grid.append(block);
    }
    if (this.day === 'today') {
      const hour = hourOfDay(now, dayStart);
      if (hour >= startHour && hour <= endHour) {
        const line = el('div', 'now');
        line.style.top = top(hour);
        grid.append(line);
      }
    }
    this.root.append(grid);
  }
}

function el(tag: string, className: string, text?: string): HTMLElement {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function colored(node: HTMLElement, colors: Pick<CalendarDef, 'color' | 'textColor'>): HTMLElement {
  node.style.background = colors.color;
  node.style.color = colors.textColor;
  return node;
}

function header(day: Day, dayStart: Date): HTMLElement {
  const head = el('div', 'head');
  head.append(
    el('span', 'day-tag', day === 'today' ? 'Today' : 'Tomorrow'),
    el('span', 'date', dayStart.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })),
  );
  return head;
}
