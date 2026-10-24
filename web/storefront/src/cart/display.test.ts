// @vitest-environment jsdom
// TASK:WEB-02b: name/image/colour/size of cart lines, saved on this device when the item is added (spec §3, §7.6).
import { afterEach, expect, it, vi } from 'vitest';

import { displayOf, rememberDisplay, type ItemDisplay } from './display';

afterEach(() => { vi.restoreAllMocks(); localStorage.clear(); });

const tee: ItemDisplay = { product_id: 'p-1', name_vi: 'Áo thun mẫu', name_en: 'Sample tee', asset_id: 'a-1', color: 'Blue', size: 'M' };

it('remembers and reads back', () => {
  rememberDisplay('v-1', tee);
  expect(displayOf('v-1')).toEqual(tee);
});

it('unknown variant is null', () => {
  expect(displayOf('nope')).toBeNull();
});

it('keeps at most 200 entries, dropping the oldest', () => {
  for (let i = 0; i < 205; i++) rememberDisplay(`v-${i}`, { ...tee, size: String(i) });
  expect(displayOf('v-0')).toBeNull();
  expect(displayOf('v-4')).toBeNull();
  expect(displayOf('v-5')?.size).toBe('5');
  expect(displayOf('v-204')?.size).toBe('204');
  expect(Object.keys(JSON.parse(localStorage.getItem('storefront.cartDisplay')!))).toHaveLength(200);
});

it('remembering a variant again moves it to the newest end', () => {
  for (let i = 0; i < 200; i++) rememberDisplay(`v-${i}`, tee);
  rememberDisplay('v-0', { ...tee, size: 'L' });
  rememberDisplay('v-new', tee);
  expect(displayOf('v-0')?.size).toBe('L');
  expect(displayOf('v-1')).toBeNull();
});

it('a throwing or corrupt localStorage never throws', () => {
  localStorage.setItem('storefront.cartDisplay', '{not json');
  expect(displayOf('v-1')).toBeNull();
  expect(() => rememberDisplay('v-1', tee)).not.toThrow();
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
  expect(displayOf('v-1')).toBeNull();
  expect(() => rememberDisplay('v-2', tee)).not.toThrow();
});
