import { emptyEnvelope, parseEnvelope, type Envelope } from './domain';

export interface KeyValueStorage {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
}

function copyEnvelope(envelope: Envelope): Envelope {
  return parseEnvelope(JSON.stringify(envelope), envelope.ownerId);
}

export class SessionStore {
  readonly key: string;
  private readonly storage: KeyValueStorage;
  private readonly ownerId: string;
  private tail: Promise<void> = Promise.resolve();
  private current: Envelope | null = null;
  private readonly listeners = new Set<(envelope: Envelope) => void>();

  constructor(storage: KeyValueStorage, ownerId: string) {
    this.storage = storage;
    this.ownerId = emptyEnvelope(ownerId).ownerId;
    this.key = `focuslock.sessions.v1.${encodeURIComponent(this.ownerId)}`;
  }

  load(): Promise<Envelope> {
    return this.enqueue(async () => copyEnvelope(await this.readCurrent()));
  }

  update(transform: (current: Envelope) => Envelope): Promise<Envelope> {
    return this.enqueue(async () => {
      const current = await this.readCurrent();
      const candidate = transform(copyEnvelope(current));
      const next = parseEnvelope(JSON.stringify(candidate), this.ownerId);
      await this.storage.setItem(this.key, JSON.stringify(next));
      this.current = copyEnvelope(next);
      const result = copyEnvelope(next);
      for (const listener of this.listeners) {
        try {
          listener(copyEnvelope(result));
        } catch {
          // A subscriber cannot roll back a durable write or block other subscribers.
        }
      }
      return copyEnvelope(result);
    });
  }

  subscribe(listener: (envelope: Envelope) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private async readCurrent(): Promise<Envelope> {
    if (this.current !== null) return this.current;
    const raw = await this.storage.getItem(this.key);
    const parsed = parseEnvelope(raw, this.ownerId);
    this.current = copyEnvelope(parsed);
    return this.current;
  }

  private enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.tail.then(operation);
    this.tail = result.then(() => undefined, () => undefined);
    return result;
  }
}
