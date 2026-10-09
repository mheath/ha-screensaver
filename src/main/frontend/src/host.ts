import type { Scene, SceneContext } from './scene';
import type { WorldState } from './types';

interface Showing {
  scene: Scene;
  layer: HTMLElement;
}

/** Shows one scene at a time, crossfading between them. */
export class SceneHost {
  private current: Showing | null = null;

  constructor(
    private readonly stage: HTMLElement,
    private readonly fadeMs = 1000,
  ) {}

  /** Mounts `scene` and fades it in. If mounting throws, the previous scene stays up. */
  show(scene: Scene, ctx: SceneContext): void {
    const layer = document.createElement('div');
    layer.className = 'layer';
    this.stage.appendChild(layer);
    try {
      scene.mount(layer, ctx);
    } catch (error) {
      layer.remove();
      throw error;
    }
    // Force a style flush so the transition starts from opacity 0. Not tied to
    // requestAnimationFrame, which browsers pause when they decide not to paint.
    void layer.offsetWidth;
    layer.classList.add('visible');
    const previous = this.current;
    this.current = { scene, layer };
    if (previous) {
      previous.layer.classList.remove('visible');
      window.setTimeout(() => this.dispose(previous), this.fadeMs);
    }
  }

  update(state: WorldState): void {
    this.current?.scene.update?.(state);
  }

  private dispose(showing: Showing): void {
    try {
      showing.scene.unmount();
    } catch (error) {
      console.error('Scene failed to unmount', error);
    }
    showing.layer.remove();
  }
}
