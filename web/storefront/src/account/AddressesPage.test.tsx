// @vitest-environment jsdom
// TASK:WEB-02a: address book — list with default, add/edit in a drawer, confirmed delete, set default, sample geo list.
import { act } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import address from '../test/fixtures/address.json';
import addresses from '../test/fixtures/addresses.json';
import profile from '../test/fixtures/profile.json';
import refresh from '../test/fixtures/refresh.json';
import { FakeChannel, flush, json, mockFetch, render } from '../test/harness';

beforeEach(() => { FakeChannel.all = []; vi.stubGlobal('BroadcastChannel', FakeChannel); });
afterEach(() => { vi.unstubAllGlobals(); document.body.innerHTML = ''; });

const metadata = { request_id: 'web02a-test', trace_id: 'web02a-test' };
const LIST = '/api/v1/users/me/addresses';
const second = addresses.data[1];
type Extra = (path: string) => Response | null;
const member = (extra: Extra = () => null, list: unknown = addresses) => (path: string) =>
  extra(path) ?? (path === '/api/v1/auth/refresh' ? json(refresh) : path === '/api/v1/users/me' ? json(profile)
    : path === LIST ? json(list) : path.startsWith(`${LIST}/`) ? json(address) : null);

const cards = (container: Element) => [...container.querySelectorAll('main [data-testid="address-card"]')];
const button = (scope: Element, text: string) =>
  [...scope.querySelectorAll('button')].find((b) => b.textContent?.trim() === text) as HTMLButtonElement;
const openDialog = (container: Element) => container.querySelector('main dialog[open]') as HTMLDialogElement;

async function set(element: HTMLInputElement | HTMLSelectElement, value: string) {
  await act(async () => {
    const proto = element instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value')!.set!.call(element, value);
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
  await flush();
}
async function click(element: HTMLElement) {
  await act(async () => { element.click(); });
  await flush();
}
async function submit(dialog: Element) {
  await act(async () => { (dialog.querySelector('form') as HTMLFormElement).requestSubmit(); });
  await flush();
}

it('lists addresses with the default marked', async () => {
  mockFetch(member());
  const container = await render('/account/addresses');
  expect(cards(container)).toHaveLength(2);
  expect(cards(container)[0].textContent).toContain('Mặc định');
  expect(cards(container)[1].textContent).not.toContain('Mặc định');
  expect(cards(container)[0].textContent).toContain('Phường Bến Thành (mẫu)');
});

it('adds an address through the drawer', async () => {
  const calls = mockFetch(member());
  const container = await render('/account/addresses');
  await click(button(container.querySelector('main')!, 'Thêm địa chỉ'));
  const dialog = openDialog(container);
  await set(dialog.querySelector('[name="recipient_name"]') as HTMLInputElement, 'Người Nhận Mới');
  await set(dialog.querySelector('[name="phone"]') as HTMLInputElement, '0912345678');
  await set(dialog.querySelector('[name="province_code"]') as HTMLSelectElement, '79');
  await set(dialog.querySelector('[name="ward_code"]') as HTMLSelectElement, '26734');
  await set(dialog.querySelector('[name="address_line"]') as HTMLInputElement, '9 Đường Mới');
  await submit(dialog);
  expect(calls.find((c) => c.url === LIST && c.method === 'POST')!.body).toEqual({
    recipient_name: 'Người Nhận Mới', phone: '0912345678', province_code: '79', district_code: null,
    ward_code: '26734', address_line: '9 Đường Mới', is_default: false });
  expect(calls.filter((c) => c.url === LIST && c.method === 'GET').length).toBeGreaterThanOrEqual(2);
  expect(container.querySelector('main dialog[open]')).toBeNull();
  expect(container.querySelector('main [role="status"]')!.textContent).toBe('Đã lưu địa chỉ.');
});

it('choosing another province resets the ward', async () => {
  mockFetch(member());
  const container = await render('/account/addresses');
  await click(button(container.querySelector('main')!, 'Thêm địa chỉ'));
  const dialog = openDialog(container);
  await set(dialog.querySelector('[name="province_code"]') as HTMLSelectElement, '79');
  await set(dialog.querySelector('[name="ward_code"]') as HTMLSelectElement, '26734');
  await set(dialog.querySelector('[name="province_code"]') as HTMLSelectElement, '01');
  expect((dialog.querySelector('[name="ward_code"]') as HTMLSelectElement).value).toBe('');
});

it('validates phone and required fields', async () => {
  const calls = mockFetch(member());
  const container = await render('/account/addresses');
  await click(button(container.querySelector('main')!, 'Thêm địa chỉ'));
  const dialog = openDialog(container);
  await set(dialog.querySelector('[name="phone"]') as HTMLInputElement, '123');
  await submit(dialog);
  expect(dialog.querySelector('#phone-error')!.textContent).toBe('Số điện thoại phải có dạng 0xxxxxxxxx hoặc +84xxxxxxxxx.');
  expect(dialog.querySelector('#recipient_name-error')!.textContent).toBe('Vui lòng nhập thông tin này.');
  expect(dialog.querySelector('#province_code-error')).not.toBeNull();
  expect(calls.some((c) => c.url === LIST && c.method === 'POST')).toBe(false);
});

it('delete asks for confirmation and focuses Cancel', async () => {
  const calls = mockFetch(member((path) => (path === `${LIST}/${second.id}` ? new Response(null, { status: 204 }) : null)));
  const container = await render('/account/addresses');
  await click(button(cards(container)[1], 'Xóa'));
  const dialog = openDialog(container);
  expect(document.activeElement).toBe(button(dialog, 'Hủy'));
  expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
  await click(button(dialog, 'Xóa địa chỉ'));
  expect(calls.find((c) => c.method === 'DELETE')!.url).toBe(`${LIST}/${second.id}`);
});

it('sets another address as default', async () => {
  const calls = mockFetch(member());
  const container = await render('/account/addresses');
  await click(button(cards(container)[1], 'Đặt làm mặc định'));
  const put = calls.find((c) => c.method === 'PUT')!;
  expect(put.url).toBe(`${LIST}/${second.id}`);
  expect(put.body).toEqual({
    recipient_name: second.recipient_name, phone: second.phone, province_code: second.province_code,
    district_code: second.district_code, ward_code: second.ward_code, address_line: second.address_line, is_default: true });
});

it('a server 400 on delete is shown', async () => {
  mockFetch(member((path) => (path === `${LIST}/${addresses.data[0].id}`
    ? json({ code: 'VALIDATION_ERROR', message: 'invalid', errors: [], metadata }, 400) : null)));
  const container = await render('/account/addresses');
  await click(button(cards(container)[0], 'Xóa'));
  await click(button(openDialog(container), 'Xóa địa chỉ'));
  // Deleting the default is allowed (the server promotes another address); a 400 here is a plain failure (final review).
  expect(container.querySelector('main [role="alert"]')!.textContent).toBe('Không xóa được địa chỉ. Vui lòng thử lại.');
});

it('codes outside the sample directory are shown raw', async () => {
  const outside = { ...addresses, data: [{ ...addresses.data[0], province_code: '96', ward_code: '32248' }] };
  mockFetch(member(() => null, outside));
  const container = await render('/account/addresses');
  expect(cards(container)[0].textContent).toContain('96');
  expect(cards(container)[0].textContent).toContain('32248');
});

it('unchecking default on the default address shows the server rule in the form (final review)', async () => {
  mockFetch(member((path) => (path === `${LIST}/${addresses.data[0].id}`
    ? json({ code: 'DEFAULT_ADDRESS_REQUIRED', message: 'DEFAULT_ADDRESS_REQUIRED', metadata }, 400) : null)));
  const container = await render('/account/addresses');
  await click(button(cards(container)[0], 'Sửa'));
  const dialog = openDialog(container);
  await act(async () => { (dialog.querySelector('[name="is_default"]') as HTMLInputElement).click(); });
  await submit(dialog);
  expect(dialog.querySelector('[role="alert"]')!.textContent).toContain('Phải luôn có một địa chỉ mặc định. Hãy đặt địa chỉ khác làm mặc định.');
  expect(openDialog(container)).not.toBeNull();
});

it('changing province or ward drops the old district code (PR #38 item 4)', async () => {
  const withDistrict = { ...addresses, data: [{ ...addresses.data[0], district_code: '760' }, addresses.data[1]] };
  const calls = mockFetch(member(() => null, withDistrict));
  const container = await render('/account/addresses');
  await click(button(cards(container)[0], 'Sửa'));
  let dialog = openDialog(container);
  await submit(dialog);
  expect(calls.filter((c) => c.method === 'PUT').at(-1)!.body).toMatchObject({ province_code: '79', district_code: '760' });

  await click(button(cards(container)[0], 'Sửa'));
  dialog = openDialog(container);
  await set(dialog.querySelector('[name="province_code"]') as HTMLSelectElement, '01');
  await set(dialog.querySelector('[name="ward_code"]') as HTMLSelectElement, '00004');
  await submit(dialog);
  expect(calls.filter((c) => c.method === 'PUT').at(-1)!.body).toMatchObject({ province_code: '01', ward_code: '00004', district_code: null });
});
