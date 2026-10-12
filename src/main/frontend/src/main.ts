import '@fontsource/vt323';
import './styles.css';
import { startDemo } from './demo';
import { SceneHost } from './host';
import { ReloadPolicy } from './reload';
import type { SceneContext, SceneModule } from './scene';
import { calendarModule } from './scenes/calendar/calendar';
import { clockModule } from './scenes/clock/clock';
import { snakesModule } from './scenes/snakes/snakes';
import { Scheduler, type Candidate } from './scheduler';
import { openStream, type StreamHandlers } from './stream';
import type { KioskConfig, SceneType, WorldState } from './types';

const MODULES: Record<SceneType, SceneModule> = { clock: clockModule, snakes: snakesModule, calendar: calendarModule };
const RETRY_MS = 5000;
const FALLBACK_AFTER_MS = 5000;
const FORCED_SCENE_MS = 60_000;

const params = new URLSearchParams(location.search);
const kiosk = params.get('kiosk') ?? 'default';
const forced = params.get('scene') as SceneType | null;

const status = document.getElementById('status') as HTMLElement;
const host = new SceneHost(document.getElementById('stage') as HTMLElement);
const scheduler = new Scheduler(Math.random);
const reload = new ReloadPolicy();

let config: KioskConfig | null = null;
let state: WorldState | null = null;
let streamConnected = false;
let started = false;
let currentType: SceneType | null = null;
let timer = 0;

function context(cfg: KioskConfig, st: WorldState): SceneContext {
  return { config: cfg, state: st, random: Math.random };
}

function candidates(cfg: KioskConfig, st: WorldState): Candidate[] {
  if (forced) {
    return MODULES[forced]?.isEligible(cfg, st)
      ? [{ key: forced, type: forced, weight: 1, durationMs: FORCED_SCENE_MS }]
      : [];
  }
  return cfg.scenes
    .filter((scene) => MODULES[scene.type]?.isEligible(cfg, st))
    .map((scene) => ({ key: scene.type, type: scene.type, weight: scene.weight, durationMs: scene.durationMs }));
}

function schedule(ms: number): void {
  window.clearTimeout(timer);
  timer = window.setTimeout(showNext, ms);
}

function showNext(): void {
  if (reload.shouldReloadNow()) {
    location.reload();
    return;
  }
  if (!config || !state) {
    schedule(RETRY_MS);
    return;
  }
  const next = scheduler.next(candidates(config, state));
  if (!next) {
    schedule(RETRY_MS);
    return;
  }
  try {
    host.show(MODULES[next.type].create(), context(config, state));
    currentType = next.type;
    schedule(next.durationMs);
  } catch (error) {
    console.error(`Scene "${next.type}" failed to start`, error);
    scheduler.sideline(next.type);
    schedule(0);
  }
}

/** Something to look at if the backend is slow to answer on first load. */
function showFallbackClock(): void {
  if (started) return;
  const fallback: KioskConfig = config ?? {
    kiosk,
    scenes: [],
    clock: { use24Hour: false },
    snakes: { guestCount: 0, minLength: 4, maxLength: 30, minSpeed: 2, maxSpeed: 20, pinned: [], guests: [] },
    calendar: { allDayUntil: '18:00', calendars: [] },
  };
  host.show(clockModule.create(), context(fallback, state ?? { haConnected: false, snakes: [], calendar: { events: [] } }));
  currentType = 'clock';
}

function maybeStart(): void {
  if (started || !config || !state) return;
  started = true;
  showNext();
}

function updateStatus(): void {
  status.hidden = streamConnected && (state?.haConnected ?? true);
}

const handlers: StreamHandlers = {
  onConfig(next) {
    config = next;
    maybeStart();
  },
  onState(next) {
    state = next;
    updateStatus();
    if (started) {
      try {
        host.update(next);
      } catch (error) {
        console.error(`Scene "${currentType}" failed to update`, error);
        if (currentType) scheduler.sideline(currentType);
        showNext();
      }
    }
    maybeStart();
  },
  onConnection(connected, reconnected) {
    streamConnected = connected;
    reload.onConnection(connected, reconnected);
    updateStatus();
  },
};

// Measure scenes with the real font, not a fallback.
void document.fonts.ready.then(() => {
  if (params.has('demo')) {
    startDemo(handlers);
  } else {
    openStream(`/api/stream?kiosk=${encodeURIComponent(kiosk)}`, handlers);
  }
  window.setTimeout(showFallbackClock, FALLBACK_AFTER_MS);
});
