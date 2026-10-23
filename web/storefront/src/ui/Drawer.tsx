// TASK:WEB-01b: one drawer for the mobile menu, filters and size guide (spec section 3.6). A native modal <dialog>
// gives focus trapping, Escape, an inert background and focus return without any library.
import { useEffect, useId, useRef, type ReactNode } from 'react';

import { useLang } from '../i18n';
import { CloseIcon } from './icons';

const SIDES = {
  right: 'ml-auto w-full max-w-md starting:open:translate-x-full',
  full: 'w-full max-w-none starting:open:translate-y-4 starting:open:opacity-0',
};

export function Drawer({ open, onClose, title, side = 'right', children }: {
  open: boolean;
  onClose: () => void;
  title: string;
  side?: 'right' | 'full';
  children: ReactNode;
}) {
  const { t } = useLang();
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  // The prop, not the element, decides: a close() caused by the parent setting open=false must not call onClose again.
  const wanted = useRef(open);
  wanted.current = open;

  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    if (open && !element.open) element.showModal();
    if (!open && element.open) element.close();
  }, [open]);

  return (
    <dialog
      ref={dialog}
      aria-labelledby={titleId}
      onClose={() => { if (wanted.current) onClose(); }}
      onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}
      className={`m-0 h-dvh max-h-none bg-bg p-0 text-fg transition duration-250 ease-out backdrop:bg-black/50 ${SIDES[side]}`}
    >
      <div className="flex h-full flex-col">
        <div className="flex items-center justify-between border-b border-fg px-4 py-2">
          <h2 id={titleId} className="text-sm font-semibold uppercase tracking-[0.02em]">{title}</h2>
          <button type="button" aria-label={t('close')} onClick={onClose} className="-mr-2 grid size-11 place-items-center">
            <CloseIcon />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-4 py-4">{children}</div>
      </div>
    </dialog>
  );
}
