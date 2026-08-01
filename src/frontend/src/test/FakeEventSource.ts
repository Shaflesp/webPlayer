/**
 * jsdom does not implement EventSource at all — `new EventSource(...)` would
 * throw "EventSource is not defined" in any test that reaches that line
 * without this. This fake supports exactly the two patterns SyncPanel uses:
 * plain `es.onmessage = fn` for the default message event, and
 * `es.addEventListener('done', fn)` for the server's named 'done' event.
 *
 * Tests drive it by calling the exposed emitMessage/emitNamed/emitError
 * methods directly — there's no real network involved.
 */
export class FakeEventSource {
  static instances: FakeEventSource[] = [];

  url: string;
  onmessage: ((e: MessageEvent) => void) | null = null;
  onerror:   (() => void) | null = null;
  closed = false;

  private listeners = new Map<string, Array<(e: MessageEvent) => void>>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, cb: (e: MessageEvent) => void) {
    const arr = this.listeners.get(name) ?? [];
    arr.push(cb);
    this.listeners.set(name, arr);
  }

  close() {
    this.closed = true;
  }

  // ── Test-driving helpers ──────────────────────────────────────────────────

  emitMessage(data: string) {
    this.onmessage?.({ data } as MessageEvent);
  }

  emitNamed(name: string, data: string) {
    for (const cb of this.listeners.get(name) ?? []) cb({ data } as MessageEvent);
  }

  emitError() {
    this.onerror?.();
  }

  static reset() {
    FakeEventSource.instances = [];
  }

  static latest(): FakeEventSource | undefined {
    return FakeEventSource.instances.at(-1);
  }
}
