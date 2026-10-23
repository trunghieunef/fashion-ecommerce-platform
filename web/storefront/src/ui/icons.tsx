// TASK:WEB-01b: inline SVG icons; always decorative (the button carrying one has its own label).
import type { ReactNode } from 'react';

type IconProps = { className?: string };

function Icon({ className = 'size-6', children }: IconProps & { children: ReactNode }) {
  return (
    <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.5"
      strokeLinecap="square" aria-hidden="true" focusable="false" className={className}>
      {children}
    </svg>
  );
}

export const MenuIcon = (p: IconProps) => <Icon {...p}><path d="M3 7h18M3 12h18M3 17h18" /></Icon>;
export const SearchIcon = (p: IconProps) => <Icon {...p}><circle cx="11" cy="11" r="6.5" /><path d="m16 16 5 5" /></Icon>;
export const CloseIcon = (p: IconProps) => <Icon {...p}><path d="m5 5 14 14M19 5 5 19" /></Icon>;
export const ChevronLeft = (p: IconProps) => <Icon {...p}><path d="m15 5-7 7 7 7" /></Icon>;
export const ChevronRight = (p: IconProps) => <Icon {...p}><path d="m9 5 7 7-7 7" /></Icon>;
export const UserIcon = (p: IconProps) => <Icon {...p}><circle cx="12" cy="8" r="4" /><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6" /></Icon>;
export const ChevronDown = (p: IconProps) => <Icon {...p}><path d="m5 9 7 7 7-7" /></Icon>;
