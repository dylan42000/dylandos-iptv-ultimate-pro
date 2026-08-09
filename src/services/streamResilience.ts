// ─── Stream Resilience Engine ─────────────────────────────────────────────────
// Handles automatic reconnection with exponential backoff when a stream fails.
// Also provides a buffering watchdog: if MPV reports stall > 15s, triggers reconnect.
//
// Usage:
//   const engine = new StreamResilienceEngine({
//     maxRetries: settings.liveReconnectMaxAttempts,
//     baseDelayMs: settings.liveReconnectBaseDelayMs,
//     onReconnecting: (attempt, max) => setReconnectState({ attempt, max }),
//     onReconnected: () => setReconnectState(null),
//     onGaveUp: () => setStreamDead(true),
//     onRestart: async () => { await mpv.stop(); await mpv.play(url); },
//   });

export interface ResilienceEngineOptions {
  maxRetries: number;
  baseDelayMs: number;
  onReconnecting: (attempt: number, maxAttempts: number) => void;
  onReconnected: () => void;
  onGaveUp: () => void;
  onRestart: () => Promise<void>;
}

export class StreamResilienceEngine {
  private retryCount = 0;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private bufferingWatchdog: ReturnType<typeof setTimeout> | null = null;
  private isReconnecting = false;
  private disposed = false;

  constructor(private opts: ResilienceEngineOptions) {}

  // ── Exponential backoff delay ──────────────────────────────────────────────
  // Doubles each attempt, capped at 30 seconds

  private getDelay(attempt: number): number {
    return Math.min(this.opts.baseDelayMs * Math.pow(2, attempt), 30_000);
  }

  // ── Handle a stream error / stall ─────────────────────────────────────────

  handleError(): void {
    if (this.disposed || this.isReconnecting) return;

    if (this.retryCount >= this.opts.maxRetries) {
      this.opts.onGaveUp();
      return;
    }

    this.isReconnecting = true;
    const delay = this.getDelay(this.retryCount);
    this.opts.onReconnecting(this.retryCount + 1, this.opts.maxRetries);

    this.retryTimer = setTimeout(async () => {
      if (this.disposed) return;
      this.retryCount++;
      try {
        await this.opts.onRestart();
        // Give the stream 5s to confirm playback before declaring success
        this.retryTimer = setTimeout(() => {
          if (!this.disposed) {
            this.isReconnecting = false;
            this.retryCount = 0;
            this.opts.onReconnected();
          }
        }, 5_000);
      } catch {
        this.isReconnecting = false;
        // Retry again (recursive)
        this.handleError();
      }
    }, delay);
  }

  // ── Buffering watchdog ─────────────────────────────────────────────────────
  // Start when MPV enters buffering state. If it doesn't recover in 15s, treat as error.

  startBufferingWatchdog(timeoutMs = 15_000): void {
    this.clearBufferingWatchdog();
    this.bufferingWatchdog = setTimeout(() => {
      this.handleError();
    }, timeoutMs);
  }

  clearBufferingWatchdog(): void {
    if (this.bufferingWatchdog) {
      clearTimeout(this.bufferingWatchdog);
      this.bufferingWatchdog = null;
    }
  }

  // ── Signal successful playback resumed ────────────────────────────────────
  // Call this when MPV reports it started playing after a reconnect attempt.

  onPlaybackResumed(): void {
    this.clearBufferingWatchdog();
    if (this.isReconnecting) {
      this.isReconnecting = false;
      this.retryCount = 0;
      if (this.retryTimer) clearTimeout(this.retryTimer);
      this.retryTimer = null;
      this.opts.onReconnected();
    }
  }

  // ── Reset state (e.g. when user manually changes channel) ─────────────────

  reset(): void {
    this.retryCount = 0;
    this.isReconnecting = false;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    this.clearBufferingWatchdog();
  }

  // ── Cleanup ────────────────────────────────────────────────────────────────

  dispose(): void {
    this.disposed = true;
    this.reset();
  }
}
