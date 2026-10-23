// TASK:WEB-01b: product gallery (spec 3.5) — a two-column image grid on desktop, a swipeable scroll-snap strip with a
// position readout on mobile. Every image opens one shared zoom <dialog>; every image stays reachable (PR #28).
import { useEffect, useRef, useState } from 'react';

import { useLang } from '../i18n';
import { CloseIcon } from '../ui/icons';
import { imageUrl } from './api';
import { CatalogImage } from './ui';

export type GalleryImage = { assetId: string; alt: string; color: string | null };

export function Gallery({ images, fallbackAlt, focusColor }: { images: GalleryImage[]; fallbackAlt: string; focusColor: string }) {
  const { t } = useLang();
  const strip = useRef<HTMLUListElement>(null);
  const zoom = useRef<HTMLDialogElement>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const [zoomed, setZoomed] = useState(0);
  const [position, setPosition] = useState(1);

  // Choosing a colour brings its first image into view (no reordering, so positions stay stable).
  useEffect(() => {
    if (!focusColor) return;
    const index = images.findIndex((image) => image.color === focusColor);
    if (index >= 0) strip.current?.children[index]?.scrollIntoView?.({ block: 'nearest', inline: 'start', behavior: 'smooth' });
  }, [focusColor]);

  if (images.length === 0)
    return <div data-testid="gallery"><CatalogImage assetId={null} alt={fallbackAlt} kind="image" /></div>;

  const onScroll = () => {
    const el = strip.current;
    if (el && el.clientWidth > 0) setPosition(Math.min(images.length, Math.round(el.scrollLeft / el.clientWidth) + 1));
  };
  const current = images[zoomed] ?? images[0];
  return (
    <div data-testid="gallery" className="relative">
      <ul ref={strip} onScroll={onScroll} aria-label={t('images')}
        className="flex snap-x snap-mandatory overflow-x-auto [scrollbar-width:none] lg:grid lg:grid-cols-2 lg:gap-1 lg:overflow-visible">
        {images.map((image, i) => (
          <li key={image.assetId} className="w-full shrink-0 snap-start">
            <button type="button" aria-label={`${t('zoom')}: ${image.alt}`} className="block w-full cursor-zoom-in"
              onClick={(e) => { opener.current = e.currentTarget; setZoomed(i); zoom.current?.showModal?.(); }}>
              <CatalogImage key={image.assetId} assetId={image.assetId} alt={image.alt} kind="image" eager={i < 2} />
            </button>
          </li>
        ))}
      </ul>
      {images.length > 1 && (
        <p aria-live="polite" className="absolute right-3 bottom-3 bg-bg px-2 py-0.5 text-xs font-semibold tabular-nums lg:hidden">
          {position} / {images.length}
        </p>
      )}
      <dialog ref={zoom} data-testid="zoom" aria-label={current.alt} onClose={() => opener.current?.focus()}
        className="m-auto max-h-[95dvh] max-w-[min(95vw,60rem)] bg-bg p-0 backdrop:bg-black/80">
        <img src={imageUrl(current.assetId, 'image')} alt={current.alt} className="max-h-[90dvh] w-auto object-contain" />
        <form method="dialog" className="absolute top-2 right-2">
          <button type="submit" aria-label={t('close')} className="grid size-11 place-items-center bg-bg"><CloseIcon /></button>
        </form>
      </dialog>
    </div>
  );
}
