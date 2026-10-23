// TASK:WEB-02a: client checks that mirror user.yaml limits; each returns an i18n key or null. The server still decides.
import type { StringKey } from '../i18n';

export type Validator = (value: string) => StringKey | null;

export const utf8Bytes = (s: string) => new TextEncoder().encode(s).length;

export const required: Validator = (v) => (v.trim() ? null : 'errRequired');
export const email: Validator = (v) =>
  !v.trim() ? 'errRequired' : v.trim().length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.trim()) ? 'errEmail' : null;
/** BCrypt limit: at least 8 characters, at most 72 UTF-8 bytes (Vietnamese letters can take 3 bytes each). */
export const password: Validator = (v) =>
  !v ? 'errRequired' : [...v].length < 8 ? 'errPasswordShort' : utf8Bytes(v) > 72 ? 'errPasswordLong' : null;
export const maxLength = (n: number): Validator => (v) => (!v.trim() ? 'errRequired' : [...v.trim()].length > n ? 'errTooLong' : null);
export const phone: Validator = (v) => (!v.trim() ? 'errRequired' : /^(0|\+84)\d{9}$/.test(v.trim()) ? null : 'errPhone');
