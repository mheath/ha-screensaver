import type { KioskConfig, SceneType, WorldState } from './types';

export interface SceneContext {
  config: KioskConfig;
  state: WorldState;
  random: () => number;
}

/** One appearance of a scene on screen. */
export interface Scene {
  /** Builds the scene inside `container`, which is already attached and sized. */
  mount(container: HTMLElement, ctx: SceneContext): void;
  /** Receives live world-state updates while on screen. */
  update?(state: WorldState): void;
  /** Stops timers and animation. The host removes the container afterwards. */
  unmount(): void;
}

export interface SceneModule {
  type: SceneType;
  isEligible(config: KioskConfig, state: WorldState): boolean;
  create(): Scene;
}
