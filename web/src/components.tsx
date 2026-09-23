import type { ReactNode } from 'react'
import type { Status } from './types'
import logoUrl from './assets/logo.png'

const ICONS = {
  home: (
    <>
      <path d="M3 10.5 12 3l9 7.5" />
      <path d="M5.5 9.5V21h13V9.5" />
    </>
  ),
  terminal: (
    <>
      <rect x="3.5" y="4" width="17" height="16" rx="2.5" />
      <path d="m7.5 9.5 3 3-3 3" />
      <path d="M13.5 15.5H16" />
    </>
  ),
  sliders: (
    <>
      <path d="M4 7h16M4 17h16" />
      <circle cx="9" cy="7" r="2.5" />
      <circle cx="15" cy="17" r="2.5" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  chevronRight: <path d="m9.5 6 6 6-6 6" />,
  chevronLeft: <path d="m14.5 6-6 6 6 6" />,
  trash: (
    <>
      <path d="M4 7h16M9.5 7V4.5h5V7" />
      <path d="M6.5 7 7.5 20h9l1-13" />
    </>
  ),
  play: <path d="M8 5.5v13l10.5-6.5z" fill="currentColor" stroke="none" />,
  stop: <rect x="7" y="7" width="10" height="10" rx="1.5" fill="currentColor" stroke="none" />,
  copy: (
    <>
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M15 5.5H6a2 2 0 0 0-2 2v9" />
    </>
  ),
  users: (
    <>
      <path d="M15.5 19.5v-1a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v1" />
      <circle cx="9.2" cy="7.5" r="3.2" />
      <path d="M17 11.2a3 3 0 0 0 0-6" />
      <path d="M20.5 19.5v-1a4 4 0 0 0-2.8-3.8" />
    </>
  ),
  box: (
    <>
      <path d="m12 3 8 4.4v9.2L12 21l-8-4.4V7.4z" />
      <path d="m4 7.4 8 4.4 8-4.4" />
      <path d="M12 11.8V21" />
    </>
  ),
  server: (
    <>
      <rect x="4" y="4" width="16" height="6" rx="2" />
      <rect x="4" y="14" width="16" height="6" rx="2" />
      <path d="M8 7h.01M8 17h.01" />
    </>
  ),
  dots: (
    <>
      <circle cx="12" cy="5.5" r="1.4" fill="currentColor" stroke="none" />
      <circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none" />
      <circle cx="12" cy="18.5" r="1.4" fill="currentColor" stroke="none" />
    </>
  ),
  wifi: (
    <>
      <path d="M4 9.5a12 12 0 0 1 16 0" />
      <path d="M7 13a8 8 0 0 1 10 0" />
      <path d="M10 16.3a4 4 0 0 1 4 0" />
      <circle cx="12" cy="19" r="1" fill="currentColor" stroke="none" />
    </>
  ),
} satisfies Record<string, ReactNode>

export type IconName = keyof typeof ICONS

export function Icon({ name, className = 'size-5' }: { name: IconName; className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
    >
      {ICONS[name]}
    </svg>
  )
}

export function Logo({ className = 'size-9' }: { className?: string }) {
  return <img src={logoUrl} alt="" className={className} />
}

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`rounded-2xl border border-nomad-border bg-nomad-card ${className}`}>{children}</div>
  )
}

export function IconButton({
  icon,
  label,
  onClick,
  className = '',
}: {
  icon: IconName
  label: string
  onClick: () => void
  className?: string
}) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className={`grid size-9 place-items-center rounded-lg border border-nomad-border bg-nomad-bg text-nomad-muted active:bg-nomad-surface ${className}`}
    >
      <Icon name={icon} className="size-[18px]" />
    </button>
  )
}

const STATUS_COLOR: Record<Status, string> = {
  Running: 'bg-nomad-online',
  Starting: 'bg-nomad-warn',
  Stopping: 'bg-nomad-warn',
  Error: 'bg-red-500',
  Stopped: 'bg-nomad-muted',
}

const STATUS_LABEL: Record<Status, string> = {
  Stopped: 'Apagado',
  Starting: 'Iniciando',
  Running: 'En línea',
  Stopping: 'Deteniendo',
  Error: 'Error',
}

export const statusLabel = (status: Status) => STATUS_LABEL[status]

export function StatusDot({ status, className = 'size-2' }: { status: Status; className?: string }) {
  return <span className={`inline-block rounded-full ${STATUS_COLOR[status]} ${className}`} />
}

export function StatusPill({ status }: { status: Status }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-nomad-border bg-nomad-bg px-2.5 py-1 text-xs text-nomad-muted">
      <StatusDot status={status} />
      {statusLabel(status)}
    </span>
  )
}

const GRADIENTS: [string, string][] = [
  ['#3A6B8A', '#1B2740'],
  ['#7A3B8A', '#241B40'],
  ['#3B8A5E', '#1B402C'],
  ['#8A6B3B', '#402F1B'],
  ['#3B5E8A', '#1B2A40'],
]

export function Thumbnail({ seed, className = 'size-[72px]' }: { seed: string; className?: string }) {
  let hash = 0
  for (const char of seed) hash = (hash * 31 + char.charCodeAt(0)) | 0
  const [from, to] = GRADIENTS[Math.abs(hash) % GRADIENTS.length]!
  return (
    <div
      className={`grid shrink-0 place-items-center rounded-2xl ${className}`}
      style={{ backgroundImage: `linear-gradient(135deg, ${from}, ${to})` }}
    >
      <Logo className="size-7 opacity-90" />
    </div>
  )
}
