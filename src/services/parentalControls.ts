// ─── Parental Controls Service ───────────────────────────────────────────────
// PIN stored as SHA-256 hex hash (never plaintext).
// Locked categories stored in localStorage as a Set<string>.

const ADULT_KEYWORDS = ['xxx', 'adult', '18+', 'erotic', 'playboy', 'x-rated', 'xxx18'];

const KEY_PIN = 'dylandos:parental:pin';
const KEY_LOCKED = 'dylandos:parental:locked';
const KEY_ENABLED = 'dylandos:parental:enabled';

// ── SHA-256 hash via Web Crypto API ────────────────────────────────────────

async function hashPin(pin: string): Promise<string> {
  const encoded = new TextEncoder().encode(pin);
  const hashBuffer = await crypto.subtle.digest('SHA-256', encoded);
  return Array.from(new Uint8Array(hashBuffer))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

class ParentalControlsClass {
  // ── PIN management ────────────────────────────────────────────────────────

  async setPin(pin: string): Promise<void> {
    const hash = await hashPin(pin);
    localStorage.setItem(KEY_PIN, hash);
    localStorage.setItem(KEY_ENABLED, 'true');
  }

  async verifyPin(pin: string): Promise<boolean> {
    const stored = localStorage.getItem(KEY_PIN);
    if (!stored) return true; // no PIN set = always unlocked
    return stored === await hashPin(pin);
  }

  isPinSet(): boolean {
    return !!localStorage.getItem(KEY_PIN);
  }

  isEnabled(): boolean {
    return localStorage.getItem(KEY_ENABLED) === 'true' && this.isPinSet();
  }

  setEnabled(enabled: boolean): void {
    localStorage.setItem(KEY_ENABLED, enabled ? 'true' : 'false');
  }

  clearPin(): void {
    localStorage.removeItem(KEY_PIN);
    localStorage.setItem(KEY_ENABLED, 'false');
  }

  // ── Category locking ──────────────────────────────────────────────────────

  lockCategory(categoryName: string): void {
    const locked = this.getLockedCategories();
    locked.add(categoryName.toLowerCase());
    localStorage.setItem(KEY_LOCKED, JSON.stringify([...locked]));
  }

  unlockCategory(categoryName: string): void {
    const locked = this.getLockedCategories();
    locked.delete(categoryName.toLowerCase());
    localStorage.setItem(KEY_LOCKED, JSON.stringify([...locked]));
  }

  isLocked(categoryName: string): boolean {
    if (!this.isEnabled()) return false;
    return this.getLockedCategories().has(categoryName.toLowerCase());
  }

  getLockedCategories(): Set<string> {
    try {
      const raw = localStorage.getItem(KEY_LOCKED);
      return new Set(raw ? JSON.parse(raw) : []);
    } catch {
      return new Set();
    }
  }

  setLockedCategories(categories: string[]): void {
    const normalized = categories.map(c => c.toLowerCase());
    localStorage.setItem(KEY_LOCKED, JSON.stringify(normalized));
  }

  // ── Adult content detection ───────────────────────────────────────────────

  isAdultCategory(categoryName: string): boolean {
    const name = categoryName.toLowerCase();
    return ADULT_KEYWORDS.some(kw => name.includes(kw));
  }

  // Auto-lock all detected adult categories (call on first launch or when new
  // playlist loads). Only auto-locks if parental controls are enabled.
  autoLockAdult(categories: { category_name: string }[]): void {
    if (!this.isEnabled()) return;
    categories
      .filter(c => this.isAdultCategory(c.category_name))
      .forEach(c => this.lockCategory(c.category_name));
  }

  // ── Unlock session ────────────────────────────────────────────────────────
  // Tracks temporarily-unlocked categories for current session.

  private sessionUnlocked = new Set<string>();

  unlockForSession(categoryName: string): void {
    this.sessionUnlocked.add(categoryName.toLowerCase());
  }

  isSessionUnlocked(categoryName: string): boolean {
    return this.sessionUnlocked.has(categoryName.toLowerCase());
  }

  lockAllSessions(): void {
    this.sessionUnlocked.clear();
  }

  // ── Check if access requires PIN ─────────────────────────────────────────

  requiresPin(categoryName: string): boolean {
    if (!this.isEnabled()) return false;
    if (this.isSessionUnlocked(categoryName)) return false;
    return this.isLocked(categoryName);
  }
}

export const parentalControls = new ParentalControlsClass();
export { ParentalControlsClass };
