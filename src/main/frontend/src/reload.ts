/**
 * Any backend restart (a deploy or a config change) drops the stream, so a
 * reconnect means code, scenes or config may have changed. The app reloads at
 * the next scene change, but only while connected, so Fully never loads the
 * page while the backend is down.
 */
export class ReloadPolicy {
  private pending = false;
  private connected = false;

  onConnection(connected: boolean, reconnected: boolean): void {
    this.connected = connected;
    if (connected && reconnected) {
      this.pending = true;
    }
  }

  shouldReloadNow(): boolean {
    return this.pending && this.connected;
  }
}
