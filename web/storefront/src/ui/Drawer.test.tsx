// @vitest-environment jsdom
// TASK:WEB-01b: the shared Drawer is a native modal <dialog> with a labelled title and close button.
import { act, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, expect, it, vi } from 'vitest';

import { LangProvider } from '../i18n';
import { click, flush } from '../test/harness';
import { Drawer } from './Drawer';

afterEach(() => { document.body.innerHTML = ''; });

function Host({ onClose }: { onClose: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>Mở</button>
      <Drawer open={open} title="Bộ lọc" onClose={() => { onClose(); setOpen(false); }}>
        <p>Nội dung</p>
      </Drawer>
    </>
  );
}

async function mount(onClose = vi.fn()) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  await act(async () => createRoot(container).render(<LangProvider initial="vi"><Host onClose={onClose} /></LangProvider>));
  await flush();
  await click([...container.querySelectorAll('button')].find((b) => b.textContent === 'Mở'));
  return { container, dialog: container.querySelector('dialog') as HTMLDialogElement, onClose };
}

it('opens as a modal dialog with its title and a labelled close button', async () => {
  const { dialog } = await mount();
  expect(dialog.open).toBe(true);
  const title = document.getElementById(dialog.getAttribute('aria-labelledby') ?? '');
  expect(title?.tagName).toBe('H2');
  expect(title?.textContent).toBe('Bộ lọc');
  expect(dialog.querySelector('button[aria-label="Đóng"]')).not.toBeNull();
  expect(dialog.textContent).toContain('Nội dung');
});

it('close button, Escape (close event) and backdrop click all call onClose', async () => {
  const button = await mount();
  await click(button.dialog.querySelector('button[aria-label="Đóng"]'));
  expect(button.onClose).toHaveBeenCalledTimes(1);
  expect(button.dialog.open).toBe(false);
  document.body.innerHTML = '';

  const escape = await mount();
  await act(async () => { escape.dialog.dispatchEvent(new Event('close')); });
  await flush();
  expect(escape.onClose).toHaveBeenCalledTimes(1);
  document.body.innerHTML = '';

  const backdrop = await mount();
  await click(backdrop.dialog);
  expect(backdrop.onClose).toHaveBeenCalledTimes(1);
  await click(backdrop.dialog.querySelector('p'));
  expect(backdrop.onClose).toHaveBeenCalledTimes(1);
});
