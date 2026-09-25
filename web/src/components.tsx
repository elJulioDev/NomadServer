import type { ButtonHTMLAttributes, CSSProperties, ReactNode } from 'react'
import type { Status } from './types'
import logoUrl from './assets/logo.svg'
import packUrl from './assets/pack.jpg'

/** Une clases. Sin `tailwind-merge`: basta con no pisar utilidades en conflicto. */
export function cn(...parts: (string | false | null | undefined)[]) {
  return parts.filter(Boolean).join(' ')
}

/* ---------------------------------- iconos --------------------------------- */

const ICONS = {
  home: (
    <>
      <path d="M3 10.5 12 3l9 7.5" />
      <path d="M5.5 9.5V21h13V9.5" />
    </>
  ),
  terminal: (
    <>
      <rect x="3.5" y="4" width="17" height="16" rx="2" />
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
  chevronsUpDown: (
    <>
      <path d="m7 15 5 5 5-5" />
      <path d="m7 9 5-5 5 5" />
    </>
  ),
  trash: (
    <>
      <path d="M4 7h16M9.5 7V4.5h5V7" />
      <path d="M6.5 7 7.5 20h9l1-13" />
    </>
  ),
  copy: (
    <>
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M15 5.5H6a2 2 0 0 0-2 2v9" />
    </>
  ),
  send: (
    <>
      <path d="m22 2-7 20-4-9-9-4Z" />
      <path d="M22 2 11 13" />
    </>
  ),
  play: <path d="M8 5.5v13l10.5-6.5z" fill="currentColor" stroke="none" />,
  stop: <rect x="7" y="7" width="10" height="10" rx="1.5" fill="currentColor" stroke="none" />,
  restart: (
    <>
      <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
      <path d="M3 3v5h5" />
    </>
  ),
  cpu: (
    <>
      <rect x="5" y="5" width="14" height="14" rx="2" />
      <rect x="9" y="9" width="6" height="6" rx="1" />
      <path d="M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3" />
    </>
  ),
  activity: <path d="M22 12h-4l-3 9L9 3l-3 9H2" />,
  globe: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18" />
      <path d="M12 3a14 14 0 0 1 3.5 9 14 14 0 0 1-3.5 9 14 14 0 0 1-3.5-9A14 14 0 0 1 12 3z" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3 5 6v6c0 4.5 3 7.6 7 9 4-1.4 7-4.5 7-9V6z" />
      <path d="m9.5 12 1.8 1.8 3.4-3.6" />
    </>
  ),
  userMinus: (
    <>
      <path d="M15.5 19.5v-1a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v1" />
      <circle cx="9.2" cy="7.5" r="3.2" />
      <path d="M17 11h5" />
    </>
  ),
  ban: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m5.6 5.6 12.8 12.8" />
    </>
  ),
  x: <path d="M6 6l12 12M18 6 6 18" />,
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

export function Icon({ name, className = 'size-4' }: { name: IconName; className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
    >
      {ICONS[name]}
    </svg>
  )
}

/** El SVG viene en negro; `invert` lo pinta blanco (el fondo es transparente). */
export function Logo({ className = 'size-6' }: { className?: string }) {
  return <img src={logoUrl} alt="" className={cn('invert', className)} />
}

/* ---------------------------------- botón ---------------------------------- */

type ButtonVariant = 'default' | 'outline' | 'secondary' | 'ghost' | 'destructive' | 'danger' | 'info' | 'link'
type ButtonSize = 'default' | 'sm' | 'lg' | 'icon' | 'icon-sm' | 'icon-lg'

// Sin `tailwind-merge`: el color de borde va por variante para que no haya dos
// utilidades en conflicto (el orden de CSS ganaría de forma impredecible).
const BUTTON_VARIANT: Record<ButtonVariant, string> = {
  default: 'border-transparent bg-primary text-primary-foreground hover:bg-primary/80',
  outline: 'border-border bg-background hover:bg-muted hover:text-foreground',
  secondary: 'border-transparent bg-secondary text-secondary-foreground hover:bg-secondary/80',
  ghost: 'border-transparent hover:bg-muted hover:text-foreground',
  destructive: 'border-transparent bg-destructive/10 text-destructive hover:bg-destructive/20',
  danger: 'border-transparent bg-destructive text-white hover:bg-destructive/90',
  info: 'border-transparent bg-info text-white hover:bg-info/90',
  link: 'border-transparent text-primary underline-offset-4 hover:underline',
}

const BUTTON_SIZE: Record<ButtonSize, string> = {
  default: 'h-8 gap-1.5 px-2.5',
  sm: 'h-7 gap-1 rounded-md px-2.5 text-[0.8rem]',
  lg: 'h-9 gap-1.5 px-2.5',
  icon: 'size-8',
  'icon-sm': 'size-7 rounded-md',
  'icon-lg': 'size-9',
}

export function Button({
  variant = 'default',
  size = 'default',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: ButtonSize }) {
  return (
    <button
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-md border text-sm font-medium whitespace-nowrap transition-all outline-none select-none',
        'focus-visible:ring-2 focus-visible:ring-ring/50 active:translate-y-px disabled:pointer-events-none disabled:opacity-50',
        '[&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*=size-])]:size-4',
        BUTTON_VARIANT[variant],
        BUTTON_SIZE[size],
        className,
      )}
      {...props}
    />
  )
}

export function IconButton({
  icon,
  label,
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { icon: IconName; label: string }) {
  return (
    <Button variant="ghost" size="icon-sm" aria-label={label} title={label} className={className} {...props}>
      <Icon name={icon} className="size-4" />
    </Button>
  )
}

/* --------------------------------- structure ------------------------------- */

export function Separator({
  orientation = 'horizontal',
  className = '',
}: {
  orientation?: 'horizontal' | 'vertical'
  className?: string
}) {
  return (
    <div
      role="separator"
      aria-orientation={orientation}
      className={cn('shrink-0 bg-border', orientation === 'horizontal' ? 'h-px w-full' : 'w-px self-stretch', className)}
    />
  )
}

export function Panel({ className = '', children, style }: { className?: string; children: ReactNode; style?: CSSProperties }) {
  return (
    <section style={style} className={cn('screen-line-top screen-line-bottom border-x border-line', className)}>
      {children}
    </section>
  )
}

export function PanelHeader({ className = '', children }: { className?: string; children: ReactNode }) {
  return <header className={cn('screen-line-bottom px-4 py-3', className)}>{children}</header>
}

export function PanelTitle({ className = '', children }: { className?: string; children: ReactNode }) {
  return <h2 className={cn('text-xl font-medium tracking-tight text-balance', className)}>{children}</h2>
}

export function PanelContent({ className = '', children }: { className?: string; children: ReactNode }) {
  return <div className={cn('p-4', className)}>{children}</div>
}

/** Caja de icono estilo shadcn (24px por defecto). */
export function IconTile({ className = '', children }: { className?: string; children: ReactNode }) {
  return (
    <div
      className={cn(
        'flex size-6 shrink-0 items-center justify-center rounded-md border border-muted-foreground/15 bg-muted text-muted-foreground ring-1 ring-border/50 ring-offset-1 ring-offset-background select-none',
        className,
      )}
    >
      {children}
    </div>
  )
}

/* --------------------------------- estados --------------------------------- */

const STATUS_TONE: Record<Status, string> = {
  Running: 'bg-success',
  Starting: 'bg-info',
  Stopping: 'bg-muted-foreground',
  Error: 'bg-destructive',
  Stopped: 'bg-muted-foreground',
}

const STATUS_LABEL: Record<Status, string> = {
  Stopped: 'Apagado',
  Starting: 'Iniciando',
  Running: 'En línea',
  Stopping: 'Deteniendo',
  Error: 'Error',
}

export const statusLabel = (status: Status) => STATUS_LABEL[status]

/** Punto "en vivo" con ping, como en el portafolio. */
export function LiveDot({ status, className = 'size-2' }: { status: Status; className?: string }) {
  return (
    <span className={cn('relative flex items-center justify-center', className)}>
      {status === 'Running' && (
        <span className={cn('absolute inline-flex size-full animate-ping rounded-full opacity-50', STATUS_TONE[status])} />
      )}
      <span className={cn('relative inline-flex size-1.5 rounded-full', STATUS_TONE[status])} />
    </span>
  )
}

/* ---------------------------------- visual --------------------------------- */

export function Card({
  children,
  className = '',
  style,
}: {
  children: ReactNode
  className?: string
  style?: CSSProperties
}) {
  return (
    <div style={style} className={cn('rounded-lg border border-border bg-card', className)}>
      {children}
    </div>
  )
}

export function Thumbnail({ src, className = 'size-11' }: { src?: string | null; className?: string }) {
  return (
    <div
      className={cn(
        'shrink-0 overflow-hidden rounded-md border border-muted-foreground/15 bg-muted ring-1 ring-border/50 ring-offset-1 ring-offset-background',
        className,
      )}
    >
      <img src={src ?? packUrl} alt="" className="size-full object-cover" />
    </div>
  )
}

/** `select` nativo con estética shadcn (funciona bien en WebView, sin dependencias). */
export function Select({
  value,
  onChange,
  children,
  className = '',
}: {
  value: string
  onChange: (value: string) => void
  children: ReactNode
  className?: string
}) {
  return (
    <select
      value={value}
      onChange={(event) => onChange(event.target.value)}
      className={cn(
        'h-8 rounded-md border border-input bg-transparent px-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring/50 [&>option]:bg-popover',
        className,
      )}
    >
      {children}
    </select>
  )
}
