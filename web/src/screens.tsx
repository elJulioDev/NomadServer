import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
  type KeyboardEvent,
  type ReactNode,
} from 'react'
import { bridge } from './bridge'
import backgroundUrl from './assets/background.jpeg'
import { primeAudio } from './sound'
import {
  Button,
  Icon,
  IconButton,
  LiveDot,
  Logo,
  Panel,
  PanelContent,
  PanelHeader,
  PanelTitle,
  Select,
  Separator,
  Thumbnail,
  cn,
  statusLabel,
  type IconName,
} from './components'
import {
  DEFAULT_SETTINGS,
  serverIconUrl,
  type ActiveServer,
  type Difficulty,
  type Gamemode,
  type ServerSettings,
  type ServerSummary,
  type Status,
  type Tab,
} from './types'

const RAM_MIN = 1024
const RAM_MAX = 4096
const RAM_STEP = 512

const FRAME = 'mx-auto w-full max-w-3xl'

/** Fondo: imagen + velo neutro del tema. */
function Backdrop() {
  return (
    <>
      <div className="absolute inset-0 bg-cover bg-center bg-no-repeat" style={{ backgroundImage: `url(${backgroundUrl})` }} />
      <div className="absolute inset-0 bg-background/85" />
    </>
  )
}

/* ---------------------------------- lista ---------------------------------- */

export function ServersScreen({
  servers,
  onOpen,
  onCreate,
  onDelete,
}: {
  servers: ServerSummary[]
  onOpen: (id: string, tab: Tab) => void
  onCreate: (name: string, ramMb: number, maxPlayers: number) => void
  onDelete: (id: string) => void
}) {
  const [creating, setCreating] = useState(false)
  const [pendingDelete, setPendingDelete] = useState<ServerSummary | null>(null)

  return (
    <div className="relative flex h-full flex-col overflow-hidden">
      <Backdrop />

      <header className="relative z-20 bg-background/80 backdrop-blur-md">
        <div className={cn(FRAME, 'screen-line-bottom flex h-(--header-height) items-center gap-3 border-x border-line pr-3 pl-4')}>
          <Logo className="size-9" />
          <h1 className="text-base font-semibold tracking-tight">NomadServer</h1>
        </div>
      </header>

      <div className="relative z-10 min-h-0 flex-1 overflow-x-hidden overflow-y-auto">
        <main className={cn(FRAME, 'min-h-full')}>
          <Panel className="screen-line-top-none">
            <PanelHeader>
              <PanelTitle className="flex items-center gap-2">
                <Icon name="server" className="size-5 shrink-0 text-muted-foreground" />
                Mis servidores
              </PanelTitle>
              <p className="mt-1 text-sm text-muted-foreground">Gestiona y controla tus servidores de Minecraft</p>
            </PanelHeader>

            {servers.length === 0 ? (
              <div className="px-6 py-24 text-center">
                <p className="text-sm font-medium">Aún no tienes servidores</p>
                <p className="mt-1 text-sm text-muted-foreground">
                  Toca el botón + para crear tu primer servidor y empezar a jugar.
                </p>
              </div>
            ) : (
              <ul>
                {servers.map((server) => (
                  <ServerRow
                    key={server.id}
                    server={server}
                    onOpen={() => onOpen(server.id, 'panel')}
                    onSettings={() => onOpen(server.id, 'settings')}
                    onConsole={() => onOpen(server.id, 'console')}
                    onDelete={() => setPendingDelete(server)}
                  />
                ))}
              </ul>
            )}
          </Panel>
        </main>
      </div>

      <button
        type="button"
        aria-label="Crear servidor"
        onClick={() => setCreating(true)}
        className="absolute right-4 bottom-4 z-30 grid size-12 place-items-center rounded-lg bg-primary text-primary-foreground shadow-md ring-1 ring-foreground/10 transition-all active:translate-y-px"
      >
        <Icon name="plus" className="size-5" />
      </button>

      {creating && (
        <CreateServerDialog
          onDismiss={() => setCreating(false)}
          onCreate={(name, ramMb, maxPlayers) => {
            onCreate(name, ramMb, maxPlayers)
            setCreating(false)
          }}
        />
      )}

      {pendingDelete && (
        <ConfirmDialog
          title={`¿Eliminar "${pendingDelete.name}"?`}
          body="Se borra el perfil. Los archivos del mundo quedan en el teléfono."
          confirm="Eliminar"
          onConfirm={() => {
            onDelete(pendingDelete.id)
            setPendingDelete(null)
          }}
          onDismiss={() => setPendingDelete(null)}
        />
      )}
    </div>
  )
}

function ServerRow({
  server,
  onOpen,
  onSettings,
  onConsole,
  onDelete,
}: {
  server: ServerSummary
  onOpen: () => void
  onSettings: () => void
  onConsole: () => void
  onDelete: () => void
}) {
  const live = server.status === 'Running' || server.status === 'Starting'

  return (
    <li className="screen-line-bottom">
      <div className="flex items-stretch gap-3 p-4">
        <button type="button" onClick={onOpen} className="flex min-w-0 flex-1 items-center gap-3 text-left">
          <Thumbnail src={server.iconVersion ? serverIconUrl(server.id, server.iconVersion) : null} />
          <div className="min-w-0 flex-1">
            <h3 className="truncate text-sm font-medium text-balance">{server.name}</h3>
            <p className="mt-0.5 flex items-center gap-1 text-xs text-muted-foreground">
              <Icon name="box" className="size-3.5 shrink-0" />
              <span className="min-w-0 truncate font-mono">{server.version ?? 'Sin instalar'}</span>
            </p>
            <p className="mt-0.5 flex items-center gap-1 text-xs text-muted-foreground tabular-nums">
              <Icon name="users" className="size-3.5 shrink-0" />
              {server.players.length}/{server.maxPlayers} jugadores
            </p>
          </div>
        </button>

        <div className="flex shrink-0 flex-col items-end justify-between gap-1.5">
          <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <LiveDot status={server.status} />
            <span className={live ? 'text-foreground' : ''}>{statusLabel(server.status)}</span>
            <Icon name="chevronRight" className="size-3.5" />
          </span>

          <div className="flex items-center gap-1">
            <IconButton icon="sliders" label="Ajustes" onClick={onSettings} />
            <IconButton icon="terminal" label="Consola" onClick={onConsole} />
            <IconButton icon="trash" label="Eliminar" onClick={onDelete} />
          </div>
        </div>
      </div>
    </li>
  )
}

/* --------------------------------- detalle --------------------------------- */

const TABS: { id: Tab; label: string; icon: IconName }[] = [
  { id: 'panel', label: 'Panel', icon: 'home' },
  { id: 'console', label: 'Consola', icon: 'terminal' },
  { id: 'settings', label: 'Ajustes', icon: 'sliders' },
]

export function DetailScreen({
  server,
  active,
  logs,
  lanAddress,
  tab,
  onTabChange,
  onCommand,
  onBack,
}: {
  server: ServerSummary
  active: ActiveServer | null
  logs: string[]
  lanAddress: string | null
  tab: Tab
  onTabChange: (tab: Tab) => void
  onCommand: (text: string) => void
  onBack: () => void
}) {
  const status = active?.status ?? server.status
  const [ramMb, setRamMb] = useState(server.ramMb)

  useEffect(() => setRamMb(server.ramMb), [server.id, server.ramMb])

  return (
    <div className="relative flex h-full flex-col overflow-hidden">
      <Backdrop />

      <header className="relative z-20 bg-background/80 backdrop-blur-md">
        <div className={cn(FRAME, 'screen-line-bottom flex h-(--header-height) items-center gap-1 border-x border-line pr-3 pl-1')}>
          <IconButton icon="chevronLeft" label="Volver" onClick={onBack} />
          <span className="min-w-0 flex-1 truncate text-sm font-medium">{server.name}</span>
          <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <LiveDot status={status} />
            {statusLabel(status)}
          </span>
        </div>
      </header>

      <div className="relative z-10 min-h-0 flex-1 overflow-x-hidden overflow-y-auto">
        <div className={cn(FRAME, 'min-h-full pb-20')}>
          {tab === 'panel' && (
            <PanelTab
              server={server}
              active={active}
              status={status}
              ramMb={ramMb}
              lanAddress={lanAddress}
              onToggle={() => {
                primeAudio()
                if (status === 'Running' || status === 'Starting') bridge.stopServer(server.id)
                else bridge.startServer(server.id, ramMb, server.maxPlayers)
              }}
              onRestart={() => {
                primeAudio()
                bridge.restartServer(server.id, ramMb, server.maxPlayers)
              }}
              onExtend={() => bridge.extendStartTimer(server.id)}
            />
          )}
          {tab === 'console' && <ConsoleTab logs={logs} status={status} onCommand={onCommand} />}
          {tab === 'settings' && (
            <SettingsTab
              key={server.id}
              ramMb={ramMb}
              onRamChange={setRamMb}
              settings={active?.settings ?? DEFAULT_SETTINGS}
              iconSrc={server.iconVersion ? serverIconUrl(server.id, server.iconVersion) : null}
              onSave={(value) => bridge.updateSettings(server.id, JSON.stringify(value))}
              onIcon={(dataUrl) => bridge.setServerIcon(server.id, dataUrl)}
            />
          )}
        </div>
      </div>

      <nav className="absolute bottom-[calc(0.5rem+env(safe-area-inset-bottom,0px))] left-1/2 z-30 flex -translate-x-1/2 items-center gap-1 rounded-xl border border-border bg-popover p-1 shadow-md ring-1 ring-foreground/10">
        {TABS.map((item) => (
          <Button
            key={item.id}
            variant={tab === item.id ? 'secondary' : 'ghost'}
            size="sm"
            onClick={() => onTabChange(item.id)}
            className="gap-1.5"
          >
            <Icon name={item.icon} className="size-4" />
            {item.label}
          </Button>
        ))}
      </nav>
    </div>
  )
}

function PanelTab({
  server,
  active,
  status,
  ramMb,
  lanAddress,
  onToggle,
  onRestart,
  onExtend,
}: {
  server: ServerSummary
  active: ActiveServer | null
  status: Status
  ramMb: number
  lanAddress: string | null
  onToggle: () => void
  onRestart: () => void
  onExtend: () => void
}) {
  const live = status === 'Running'
  const starting = status === 'Starting'
  const stopping = status === 'Stopping'
  const players = active?.players ?? server.players
  const ramUsedMb = active?.ramUsedMb ?? null
  const tps = active?.tps ?? null
  const progress = active?.startProgress ?? 0
  const autoStop = active?.autoStopSeconds ?? null
  const address = lanAddress ? `${lanAddress}:25565` : null
  const iconSrc = server.iconVersion ? serverIconUrl(server.id, server.iconVersion) : null
  const hint = live
    ? 'El servidor está aceptando conexiones'
    : starting
      ? 'Arrancando el servidor…'
      : stopping
        ? 'Deteniendo el servidor…'
        : 'El servidor no está corriendo'

  return (
    <>
      {/* Banner: vista previa del servidor (icono + IP), separado del control. */}
      <section className="screen-line-bottom relative overflow-hidden border-x border-line bg-card px-4 py-5">
        <div className="diagonal-stripes pointer-events-none absolute inset-0 opacity-60" aria-hidden />
        <div className="relative flex items-center gap-4">
          <Thumbnail src={iconSrc} className="size-16" />
          <div className="flex min-w-0 flex-1 flex-col gap-0.5">
            <span className="text-xs text-muted-foreground">IP del servidor</span>
            <span className="truncate font-mono text-base tabular-nums">{address ?? '—'}</span>
          </div>
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label="Copiar IP"
            disabled={!address}
            onClick={() => address && bridge.copy(address)}
          >
            <Icon name="copy" className="size-4" />
          </Button>
        </div>
      </section>

      <Panel className="screen-line-top-none">
        <PanelHeader>
          <PanelTitle className="text-lg">Control</PanelTitle>
        </PanelHeader>
        <PanelContent className="flex flex-col gap-3">
          {starting ? (
            <div className="flex flex-col gap-2">
              <div className="h-2 w-full overflow-hidden rounded-full bg-secondary">
                <div
                  className={cn(
                    'h-full rounded-full bg-info transition-[width] duration-500',
                    progress === 0 && 'w-1/4 animate-pulse',
                  )}
                  style={progress > 0 ? { width: `${progress}%` } : undefined}
                />
              </div>
              <p className="text-center text-xs text-muted-foreground">Arrancando… {progress}%</p>
            </div>
          ) : live ? (
            <div className="grid grid-cols-2 gap-2">
              <Button variant="danger" onClick={onToggle} className="gap-2">
                <Icon name="stop" className="size-4" />
                Detener
              </Button>
              <Button variant="info" onClick={onRestart} className="gap-2">
                <Icon name="restart" className="size-4" />
                Reiniciar
              </Button>
            </div>
          ) : (
            <Button onClick={onToggle} disabled={stopping} className="h-9 w-full gap-2">
              <Icon name="play" className="size-4" />
              {stopping ? 'Deteniendo…' : 'Iniciar'}
            </Button>
          )}
          <p className="text-center text-xs text-muted-foreground">{hint}</p>

          {autoStop !== null && live && (
            <div className="flex items-center justify-between gap-2 border-t border-line pt-3">
              <span className="inline-flex min-w-0 items-center gap-2 text-xs text-muted-foreground">
                <Icon name="clock" className="size-4 shrink-0" />
                <span className="truncate">Se apaga en {formatClock(autoStop)} si nadie entra</span>
              </span>
              <Button variant="outline" size="sm" onClick={onExtend}>
                +1 min
              </Button>
            </div>
          )}
        </PanelContent>
      </Panel>

      <div className="stripe-divider border-x border-line" />

      <Panel className="screen-line-top-none">
        <PanelHeader>
          <PanelTitle className="text-lg">Estado</PanelTitle>
        </PanelHeader>
        <PanelContent className="flex flex-col gap-4">
          <StatRow icon="users" label="Jugadores" value={`${players.length}/${server.maxPlayers}`} />
          <Separator />
          <StatRow icon="cpu" label="RAM" value={`${ramUsedMb ?? '—'} / ${ramMb} MB`} />
          <Separator />
          <StatRow icon="activity" label="TPS" value={tps !== null ? tps.toFixed(1) : '—'} />
        </PanelContent>
      </Panel>

      <div className="stripe-divider border-x border-line" />

      <Panel className="screen-line-top-none">
        <PanelHeader>
          <PanelTitle className="text-lg">Información</PanelTitle>
        </PanelHeader>
        <PanelContent className="flex flex-col gap-4">
          <StatRow icon="globe" label="IP" value={address ?? '—'} mono />
          <Separator />
          <StatRow icon="box" label="Software" value="Vanilla" />
          <Separator />
          <StatRow icon="server" label="Versión" value={server.version ?? '—'} mono />
        </PanelContent>
      </Panel>

      {players.length > 0 && (
        <Panel className="screen-line-top-none">
          <PanelHeader>
            <PanelTitle className="text-lg">Conectados</PanelTitle>
          </PanelHeader>
          <PanelContent className="flex flex-col gap-1.5">
            {players.map((player) => (
              <span key={player} className="font-mono text-sm text-muted-foreground">
                {player}
              </span>
            ))}
          </PanelContent>
        </Panel>
      )}
    </>
  )
}

/** mm:ss del contador de auto-apagado. */
const formatClock = (totalSeconds: number) => {
  const seconds = Math.max(0, totalSeconds)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function StatRow({ icon, label, value, mono }: { icon: IconName; label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-center gap-3">
      <Icon name={icon} className="size-4 shrink-0 text-muted-foreground" />
      <span className="min-w-0 flex-1 text-sm">{label}</span>
      <span className={cn('shrink-0 text-sm text-muted-foreground', mono && 'font-mono tabular-nums')}>{value}</span>
    </div>
  )
}

const MAX_RENDERED_LOGS = 800
const LOG_PREFIX = /^\[(\d{2}:\d{2}:\d{2})\]\s*\[([^\]]+)\]:?\s?(.*)$/
const CHAT = /^<([^>]{1,32})>\s?(.*)$/
const SAY = /^\[Server\]\s?(.*)$/

function ConsoleTab({
  logs,
  status,
  onCommand,
}: {
  logs: string[]
  status: Status
  onCommand: (text: string) => void
}) {
  // ponytail: sólo las últimas 800 líneas al DOM; "Copiar" manda el log completo.
  const shown = logs.length > MAX_RENDERED_LOGS ? logs.slice(-MAX_RENDERED_LOGS) : logs
  const box = useRef<HTMLDivElement>(null)
  // Autoscroll sólo si el usuario ya estaba abajo: si subió a leer, no se le arrastra.
  const pinned = useRef(true)
  const [text, setText] = useState('')
  const history = useRef<string[]>([])
  const histIndex = useRef(-1)

  const running = status === 'Running'

  useEffect(() => {
    const el = box.current
    if (el && pinned.current) el.scrollTop = el.scrollHeight
  }, [logs])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const command = text.trim()
    if (!command || !running) return
    history.current = [...history.current.filter((c) => c !== command), command].slice(-50)
    histIndex.current = -1
    onCommand(command)
    setText('')
    pinned.current = true
    requestAnimationFrame(() => {
      const el = box.current
      if (el) el.scrollTop = el.scrollHeight
    })
  }

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    const list = history.current
    if (event.key === 'ArrowUp') {
      if (list.length === 0) return
      event.preventDefault()
      histIndex.current = histIndex.current < 0 ? list.length - 1 : Math.max(0, histIndex.current - 1)
      setText(list[histIndex.current] ?? '')
    } else if (event.key === 'ArrowDown') {
      if (histIndex.current < 0) return
      event.preventDefault()
      histIndex.current = histIndex.current + 1 >= list.length ? -1 : histIndex.current + 1
      setText(histIndex.current < 0 ? '' : (list[histIndex.current] ?? ''))
    }
  }

  return (
    <Panel className="screen-line-top-none">
      <PanelHeader className="flex items-center justify-between gap-2">
        <PanelTitle className="text-lg">Consola</PanelTitle>
        <Button
          variant="outline"
          size="sm"
          disabled={logs.length === 0}
          onClick={() => bridge.copy(logs.join('\n'))}
        >
          <Icon name="copy" className="size-3.5" />
          Copiar
        </Button>
      </PanelHeader>

      <div
        ref={box}
        onScroll={() => {
          const el = box.current
          if (el) pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40
        }}
        className="h-[calc(100dvh-15rem)] min-h-40 overflow-y-auto bg-background/60 px-3 py-3 font-mono text-xs leading-relaxed"
      >
        {logs.length === 0 ? (
          <p className="px-4 py-16 text-center text-muted-foreground">
            Aún no hay actividad. Enciende el servidor para ver la consola.
          </p>
        ) : (
          shown.map((line, index) => <LogLine key={index} line={line} />)
        )}
      </div>

      <form onSubmit={submit} className="flex items-center gap-2 border-t border-border p-2">
        <input
          value={text}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={onKeyDown}
          disabled={!running}
          placeholder={running ? 'Escribe un comando…  (/say hola)' : 'Enciende el servidor para enviar comandos'}
          autoComplete="off"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          className={cn(INPUT, 'h-9 min-w-0 flex-1 font-mono')}
        />
        <Button type="submit" size="icon-lg" disabled={!running || text.trim().length === 0} aria-label="Enviar">
          <Icon name="send" className="size-4" />
        </Button>
      </form>
    </Panel>
  )
}

/** Color de cada línea del log: chat, /say, logros, conexiones, avisos y errores. */
function LogLine({ line }: { line: string }) {
  const prefix = line.match(LOG_PREFIX)
  const time = prefix?.[1]
  const channel = prefix?.[2] ?? ''
  const body = prefix?.[3] ?? line
  const level = channel.split('/').pop()?.toUpperCase() ?? ''

  let tone = 'text-muted-foreground'
  let content: ReactNode = body

  const chat = body.match(CHAT)
  const say = body.match(SAY)
  if (chat) {
    content = (
      <>
        <span className="text-sky-300">{chat[1]}</span> <span className="text-foreground">{chat[2]}</span>
      </>
    )
    tone = ''
  } else if (say) {
    content = (
      <>
        <span className="text-amber-300">[Server]</span> <span className="text-foreground">{say[1]}</span>
      </>
    )
    tone = ''
  } else if (body.startsWith('> ')) {
    tone = 'text-primary'
  } else if (/has (made the advancement|reached the goal|completed the challenge)/.test(body)) {
    tone = 'text-amber-300'
  } else if (/joined the game/.test(body)) {
    tone = 'text-success'
  } else if (/left the game|lost connection/.test(body)) {
    tone = 'text-destructive/80'
  } else if (level === 'ERROR' || level === 'SEVERE' || level === 'FATAL' || /ERROR|SEVERE|Exception|Caused by/.test(body)) {
    tone = 'text-destructive'
  } else if (level === 'WARN' || /WARN/.test(body)) {
    tone = 'text-amber-400'
  }

  return (
    <div className="whitespace-pre-wrap break-words">
      {prefix && (
        <span className="text-muted-foreground/60">
          [{time}] [{channel}]:{' '}
        </span>
      )}
      <span className={tone}>{content}</span>
    </div>
  )
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

function SettingsTab({
  ramMb,
  onRamChange,
  settings,
  iconSrc,
  onSave,
  onIcon,
}: {
  ramMb: number
  onRamChange: (ramMb: number) => void
  settings: ServerSettings
  iconSrc: string | null
  onSave: (settings: ServerSettings) => void
  onIcon: (dataUrl: string) => void
}) {
  // El borrador se inicializa al abrir la pestaña; los pushes del snapshot no lo pisan.
  const [draft, setDraft] = useState(settings)
  const [iconPreview, setIconPreview] = useState<string | null>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  const dirty = JSON.stringify(draft) !== JSON.stringify(settings)
  const set = <K extends keyof ServerSettings>(key: K, value: ServerSettings[K]) =>
    setDraft((prev) => ({ ...prev, [key]: value }))

  const pickIcon = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      const image = new Image()
      image.onload = () => {
        // Se recorta a 64x64 en el canvas: así al bridge sólo cruzan ~10 KB, no la foto entera.
        const side = Math.min(image.width, image.height)
        const canvas = document.createElement('canvas')
        canvas.width = 64
        canvas.height = 64
        const ctx = canvas.getContext('2d')
        if (!ctx || side <= 0) return
        ctx.drawImage(image, (image.width - side) / 2, (image.height - side) / 2, side, side, 0, 0, 64, 64)
        const dataUrl = canvas.toDataURL('image/png')
        setIconPreview(dataUrl)
        onIcon(dataUrl)
      }
      image.src = String(reader.result)
    }
    reader.readAsDataURL(file)
  }

  return (
    <>
      <Panel className="screen-line-top-none">
        <PanelHeader>
          <PanelTitle className="text-lg">Identidad</PanelTitle>
        </PanelHeader>
        <PanelContent className="flex flex-col gap-4">
          <div className="flex items-center gap-4">
            <Thumbnail src={iconPreview ?? iconSrc} className="size-16" />
            <div className="flex min-w-0 flex-col gap-1.5">
              <Button variant="outline" size="sm" onClick={() => fileRef.current?.click()}>
                <Icon name="box" className="size-3.5" />
                Cambiar imagen
              </Button>
              <p className="text-xs text-muted-foreground">PNG de 64×64; se recorta automáticamente.</p>
            </div>
            <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={pickIcon} />
          </div>

          <label className="flex flex-col gap-2">
            <span className="text-sm">Descripción</span>
            <textarea
              value={draft.motd}
              maxLength={120}
              rows={2}
              onChange={(event) => set('motd', event.target.value)}
              placeholder="Mi servidor de Minecraft"
              className={cn(INPUT, 'h-auto py-2')}
            />
          </label>
        </PanelContent>
      </Panel>

      <div className="stripe-divider border-x border-line" />

      <Panel className="screen-line-top-none">
        <PanelHeader>
          <PanelTitle className="text-lg">Jugadores</PanelTitle>
        </PanelHeader>
        <PanelContent className="flex flex-col gap-4">
          <SettingRow label="Slots" hint="Máximo de jugadores">
            <input
              type="number"
              min={1}
              max={100}
              value={draft.maxPlayers}
              onChange={(event) => set('maxPlayers', clamp(Number(event.target.value) || 1, 1, 100))}
              className={cn(INPUT, 'w-20 text-right tabular-nums')}
            />
          </SettingRow>
          <Separator />
          <SettingRow label="Lista blanca" hint="Solo entran jugadores autorizados">
            <Switch checked={draft.whitelist} onChange={(v) => set('whitelist', v)} label="Lista blanca" />
          </SettingRow>
          <Separator />
          <SettingRow label="Cracked" hint="No premium (online-mode = false)">
            <Switch checked={draft.cracked} onChange={(v) => set('cracked', v)} label="Cracked" />
          </SettingRow>
        </PanelContent>
      </Panel>

      <div className="stripe-divider border-x border-line" />

      <Panel className="screen-line-top-none">
        <PanelHeader>
          <PanelTitle className="text-lg">Mundo</PanelTitle>
        </PanelHeader>
        <PanelContent className="flex flex-col gap-4">
          <SettingRow label="Modo de juego">
            <Select value={draft.gamemode} onChange={(value) => set('gamemode', value as Gamemode)}>
              <option value="survival">Supervivencia</option>
              <option value="creative">Creativo</option>
              <option value="spectator">Espectador</option>
            </Select>
          </SettingRow>
          <Separator />
          <SettingRow label="Dificultad">
            <Select value={draft.difficulty} onChange={(value) => set('difficulty', value as Difficulty)}>
              <option value="peaceful">Pacífico</option>
              <option value="easy">Fácil</option>
              <option value="normal">Normal</option>
              <option value="hard">Difícil</option>
            </Select>
          </SettingRow>
          <Separator />
          <SettingRow
            label="Vuelo"
            hint="Evita que el servidor expulse a quien parezca volar (elytras, lag o plugins)"
          >
            <Switch checked={draft.allowFlight} onChange={(v) => set('allowFlight', v)} label="Vuelo" />
          </SettingRow>
          <Separator />
          <SettingRow label="Protección del spawn" hint="Bloques protegidos alrededor del origen">
            <input
              type="number"
              min={0}
              max={1000}
              value={draft.spawnProtection}
              onChange={(event) => set('spawnProtection', clamp(Number(event.target.value) || 0, 0, 1000))}
              className={cn(INPUT, 'w-20 text-right tabular-nums')}
            />
          </SettingRow>
        </PanelContent>
      </Panel>

      <div className="stripe-divider border-x border-line" />

      <Panel className="screen-line-top-none">
        <PanelHeader>
          <PanelTitle className="text-lg">Rendimiento</PanelTitle>
        </PanelHeader>
        <PanelContent className="flex flex-col gap-3">
          <Slider
            label={`RAM asignada: ${ramMb} MB`}
            min={RAM_MIN}
            max={RAM_MAX}
            step={RAM_STEP}
            value={ramMb}
            onChange={onRamChange}
          />
          <p className="-mt-1 text-xs text-muted-foreground">Se aplica la próxima vez que enciendas el servidor.</p>
        </PanelContent>
      </Panel>

      <div className="stripe-divider border-x border-line" />

      <div className="p-4">
        <Button className="h-9 w-full" disabled={!dirty} onClick={() => onSave(draft)}>
          Guardar cambios
        </Button>
        <p className="mt-2 text-center text-xs text-muted-foreground">
          Los ajustes de Minecraft se aplican al próximo arranque.
        </p>
      </div>
    </>
  )
}

function SettingRow({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="min-w-0">
        <p className="text-sm">{label}</p>
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
      </div>
      {children}
    </div>
  )
}

function Switch({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className={cn(
        'relative h-5 w-9 shrink-0 rounded-full transition-colors',
        checked ? 'bg-primary' : 'bg-input',
      )}
    >
      <span
        className={cn(
          'absolute top-0.5 left-0.5 size-4 rounded-full bg-background transition-transform',
          checked ? 'translate-x-4' : '',
        )}
      />
    </button>
  )
}

function Slider({
  label,
  min,
  max,
  step,
  value,
  onChange,
}: {
  label: string
  min: number
  max: number
  step: number
  value: number
  onChange: (value: number) => void
}) {
  return (
    <label className="flex flex-col gap-3">
      <span className="text-sm">{label}</span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
      />
    </label>
  )
}

/* --------------------------------- diálogos -------------------------------- */

const INPUT =
  'h-8 w-full rounded-md border border-input bg-transparent px-2.5 text-sm outline-none transition-[color,box-shadow] focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/50'

function CreateServerDialog({
  onDismiss,
  onCreate,
}: {
  onDismiss: () => void
  onCreate: (name: string, ramMb: number, maxPlayers: number) => void
}) {
  const [name, setName] = useState('')
  const [ramMb, setRamMb] = useState(2048)
  const [maxPlayers, setMaxPlayers] = useState(20)

  return (
    <Modal onDismiss={onDismiss}>
      <p className="text-sm font-medium">Nuevo servidor</p>

      <div className="flex flex-col gap-4">
        <input
          autoFocus
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="Nombre"
          className={INPUT}
        />
        <Slider label={`RAM asignada: ${ramMb} MB`} min={RAM_MIN} max={RAM_MAX} step={RAM_STEP} value={ramMb} onChange={setRamMb} />
        <Slider
          label={`Jugadores máximos: ${maxPlayers}`}
          min={1}
          max={40}
          step={1}
          value={maxPlayers}
          onChange={setMaxPlayers}
        />
      </div>

      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onDismiss}>
          Cancelar
        </Button>
        <Button disabled={name.trim().length === 0} onClick={() => onCreate(name.trim(), ramMb, maxPlayers)}>
          Crear
        </Button>
      </div>
    </Modal>
  )
}

function ConfirmDialog({
  title,
  body,
  confirm,
  onConfirm,
  onDismiss,
}: {
  title: string
  body: string
  confirm: string
  onConfirm: () => void
  onDismiss: () => void
}) {
  return (
    <Modal onDismiss={onDismiss}>
      <p className="text-sm font-medium">{title}</p>
      <p className="text-sm text-muted-foreground">{body}</p>
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onDismiss}>
          Cancelar
        </Button>
        <Button variant="danger" onClick={onConfirm}>
          {confirm}
        </Button>
      </div>
    </Modal>
  )
}

function Modal({ children, onDismiss }: { children: ReactNode; onDismiss: () => void }) {
  return (
    <div className="fixed inset-0 z-40 grid place-items-center bg-black/80 p-4" onClick={onDismiss}>
      <div
        className="flex w-full max-w-sm flex-col gap-4 rounded-xl border border-border bg-popover p-4 shadow-lg"
        onClick={(event) => event.stopPropagation()}
      >
        {children}
      </div>
    </div>
  )
}
