import { describe, expect, it } from 'vitest';
import { ReloadPolicy } from './reload';

describe('ReloadPolicy', () => {
  it('does not reload after the first connect', () => {
    const policy = new ReloadPolicy();
    policy.onConnection(true, false);
    expect(policy.shouldReloadNow()).toBe(false);
  });

  it('reloads after a reconnect', () => {
    const policy = new ReloadPolicy();
    policy.onConnection(true, false);
    policy.onConnection(false, false);
    policy.onConnection(true, true);
    expect(policy.shouldReloadNow()).toBe(true);
  });

  it('waits while the stream is down again', () => {
    const policy = new ReloadPolicy();
    policy.onConnection(true, true);
    policy.onConnection(false, false);
    expect(policy.shouldReloadNow()).toBe(false);
    policy.onConnection(true, true);
    expect(policy.shouldReloadNow()).toBe(true);
  });
});
