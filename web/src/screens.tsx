import { useEffect, useRef, useState, type ReactNode } from 'react'
import { bridge, isNative } from './bridge'
import {
  Card,
  Icon,
  IconButton,
  Logo,
  StatusDot,
  StatusPill,
  Thumbnail,
  statusLabel,
  type IconName,
} from './components'
import type { ActiveServer, ServerSummary, Status, Tab } from './types'

const RAM_MIN = 1024
const RAM_MAX = 4096
const RAM_STEP = 512

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
    <div className="relative flex h-full flex-col">
      <header className="flex items-center gap-3 px-5 pt-5 pb-3">
        <Logo className="size-10" />
        <h1 className="flex-1 font-pixel text-2xl text-nomad-text">NomadServer</h1>
        {!isNative && (
          <span className="rounded-full border border-nomad-warn/40 px-2 py-0.5 text-[10px] text-nomad-warn">
            modo diseño
          </span>
        )}
      </header>

      <div className="flex items-start gap-3 px-5 pb-2">
        <Icon name="server" className="mt-0.5 size-6 text-nomad-muted" />
        <div>
          <h2 className="font-pixel text-lg text-nomad-text">Mis Servidores</h2>
          <p className="text-sm text-nomad-muted">Gestiona y controla tus servidores de Minecraft</p>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-4 pt-2 pb-28">
        {servers.length === 0 ? (
          <p className="px-4 py-24 text-center text-sm text-nomad-muted">
            No tienes servidores aún.
            <br />
            Toca + para crear uno.
          </p>
        ) : (
          <div className="flex flex-col gap-3.5">
            {servers.map((server) => (
              <ServerCard
                key={server.id}
                server={server}
                onOpen={() => onOpen(server.id, 'panel')}
                onSettings={() => onOpen(server.id, 'settings')}
                onConsole={() => onOpen(server.id, 'console')}
                onDelete={() => setPendingDelete(server)}
              />
            ))}
          </div>
        )}
      </div>

      <button
        type="button"
        aria-label="Crear servidor"
        onClick={() => setCreating(true)}
        className="absolute right-5 bottom-6 grid size-14 place-items-center rounded-full bg-nomad-accent text-white shadow-lg shadow-nomad-accent/25 active:scale-95"
      >
        <Icon name="plus" className="size-7" />
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

function ServerCard({
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
  const [menu, setMenu] = useState(false)
  const running = server.status === 'Running'

  return (
    <Card className="relative p-4">
      <button type="button" onClick={onOpen} className="flex w-full items-start gap-4 text-left">
        <Thumbnail seed={server.id} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="min-w-0 flex-1 truncate font-pixel text-base text-nomad-text">{server.name}</span>
            <StatusDot status={server.status} />
            <span className={`text-xs ${running ? 'text-nomad-online' : 'text-nomad-muted'}`}>
              {statusLabel(server.status)}
            </span>
            <Icon name="chevronRight" className="size-4 text-nomad-muted" />
          </div>

          <div className="mt-2 flex items-end justify-between gap-2">
            <div className="flex flex-col gap-1 text-xs text-nomad-muted">
              <span className="inline-flex items-center gap-1.5">
                <Icon name="box" className="size-4" />
                {server.version ?? 'Sin instalar'}
              </span>
              <span className="inline-flex items-center gap-1.5">
                <Icon name="users" className="size-4" />
                {server.players.length}/{server.maxPlayers}
              </span>
            </div>

            <div className="flex items-center gap-2" onClick={(event) => event.stopPropagation()}>
              <IconButton icon="sliders" label="Ajustes" onClick={onSettings} />
              <IconButton icon="terminal" label="Consola" onClick={onConsole} />
              <div className="relative">
                <IconButton icon="dots" label="Más opciones" onClick={() => setMenu((open) => !open)} />
                {menu && (
                  <>
                    <div className="fixed inset-0 z-10" onClick={() => setMenu(false)} />
                    <div className="absolute right-0 bottom-11 z-20 w-40 overflow-hidden rounded-xl border border-nomad-border bg-nomad-surface py-1 shadow-xl">
                      <MenuItem
                        icon={running ? 'stop' : 'play'}
                        label={running ? 'Detener' : 'Iniciar'}
                        onClick={() => {
                          setMenu(false)
                          if (running) bridge.stopServer(server.id)
                          else bridge.startServer(server.id, server.ramMb, server.maxPlayers)
                        }}
                      />
                      <MenuItem
                        icon="trash"
                        label="Eliminar"
                        onClick={() => {
                          setMenu(false)
                          onDelete()
                        }}
                      />
                    </div>
                  </>
                )}
              </div>
            </div>
          </div>
        </div>
      </button>
    </Card>
  )
}

function MenuItem({ icon, label, onClick }: { icon: IconName; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-center gap-3 px-3 py-2.5 text-left text-sm text-nomad-text active:bg-nomad-card"
    >
      <Icon name={icon} className="size-[18px] text-nomad-muted" />
      {label}
    </button>
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
  onBack,
}: {
  server: ServerSummary
  active: ActiveServer | null
  logs: string[]
  lanAddress: string | null
  tab: Tab
  onTabChange: (tab: Tab) => void
  onBack: () => void
}) {
  const status = active?.status ?? server.status
  const [ramMb, setRamMb] = useState(server.ramMb)

  useEffect(() => setRamMb(server.ramMb), [server.id, server.ramMb])

  return (
    <div className="flex h-full flex-col">
      <header className="flex items-center gap-3 border-b border-nomad-border px-3 py-3">
        <IconButton icon="chevronLeft" label="Volver" onClick={onBack} className="border-transparent bg-transparent" />
        <span className="min-w-0 flex-1 truncate font-pixel text-lg text-nomad-text">{server.name}</span>
        <StatusPill status={status} />
      </header>

      <nav className="flex border-b border-nomad-border">
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            onClick={() => onTabChange(item.id)}
            className={`flex flex-1 items-center justify-center gap-2 py-3 text-sm ${
              tab === item.id ? 'border-b-2 border-nomad-accent text-nomad-text' : 'text-nomad-muted'
            }`}
          >
            <Icon name={item.icon} className="size-[18px]" />
            {item.label}
          </button>
        ))}
      </nav>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {tab === 'panel' && (
          <PanelTab
            status={status}
            ramMb={ramMb}
            ramUsedMb={active?.ramUsedMb ?? null}
            players={active?.players ?? server.players}
            lanAddress={lanAddress}
            onToggle={() => {
              if (status === 'Running' || status === 'Starting') bridge.stopServer(server.id)
              else bridge.startServer(server.id, ramMb, server.maxPlayers)
            }}
          />
        )}
        {tab === 'console' && <ConsoleTab logs={logs} />}
        {tab === 'settings' && (
          <SettingsTab ramMb={ramMb} onRamChange={setRamMb} maxPlayers={server.maxPlayers} />
        )}
      </div>
    </div>
  )
}

function PanelTab({
  status,
  ramMb,
  ramUsedMb,
  players,
  lanAddress,
  onToggle,
}: {
  status: Status
  ramMb: number
  ramUsedMb: number | null
  players: string[]
  lanAddress: string | null
  onToggle: () => void
}) {
  const live = status === 'Running'
  const busy = status === 'Starting' || status === 'Stopping'
  const address = live && lanAddress ? `${lanAddress}:25565` : null
  const hint = live
    ? 'Toca para detener'
    : status === 'Starting'
      ? 'Arrancando…'
      : status === 'Stopping'
        ? 'Deteniendo…'
        : 'Toca para encender'

  return (
    <div className="flex flex-col gap-4 p-5">
      <Card className="flex items-center gap-3 p-4">
        <StatusDot status={status} className="size-3" />
        <div>
          <p className="text-sm text-nomad-text">{statusLabel(status)}</p>
          <p className="text-xs text-nomad-muted">
            {live ? 'El servidor está aceptando conexiones' : 'El servidor no está corriendo'}
          </p>
        </div>
      </Card>

      <div className="grid grid-cols-2 gap-3">
        <Stat label="RAM" value={`${ramUsedMb ?? '—'} / ${ramMb} MB`} />
        <Stat label="Jugadores" value={`${players.length}`} />
      </div>

      <div className="flex flex-col items-center gap-2 py-2">
        <button
          type="button"
          onClick={onToggle}
          disabled={busy}
          className={`grid size-32 place-items-center rounded-full font-pixel text-xl text-white shadow-lg transition-transform active:scale-95 disabled:opacity-60 ${
            live || status === 'Starting' ? 'bg-[#2E7D32]' : 'bg-nomad-accent'
          }`}
        >
          {live || status === 'Starting' ? 'STOP' : 'START'}
        </button>
        <span className="text-sm text-nomad-muted">{hint}</span>
      </div>

      <Card className="flex flex-col gap-3 p-4">
        <p className="text-sm text-nomad-text">Conexión</p>
        <LabeledValue
          label="LAN"
          value={address ?? '—'}
          onCopy={address ? () => bridge.copy(address) : undefined}
        />
        <div className="flex items-center justify-between">
          <span className="text-sm text-nomad-muted">Playit.gg</span>
          <span className="inline-flex items-center gap-1.5 text-sm text-nomad-muted">
            <Icon name="wifi" className="size-4" />
            Desactivado
          </span>
        </div>
      </Card>

      {players.length > 0 && (
        <Card className="flex flex-col gap-1.5 p-4">
          <p className="text-sm text-nomad-text">Conectados</p>
          {players.map((player) => (
            <span key={player} className="text-sm text-nomad-muted">
              {player}
            </span>
          ))}
        </Card>
      )}
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Card className="flex flex-col items-center gap-1 p-4">
      <span className="text-lg text-nomad-text">{value}</span>
      <span className="text-xs text-nomad-muted">{label}</span>
    </Card>
  )
}

function LabeledValue({ label, value, onCopy }: { label: string; value: string; onCopy?: () => void }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-sm text-nomad-muted">{label}</span>
      <span className="flex items-center gap-1">
        <span className="font-mono text-sm text-nomad-text">{value}</span>
        {onCopy && (
          <button type="button" onClick={onCopy} className="px-2 py-1 text-xs text-nomad-accent active:opacity-70">
            Copiar
          </button>
        )}
      </span>
    </div>
  )
}

const MAX_RENDERED_LOGS = 800

function ConsoleTab({ logs }: { logs: string[] }) {
  // ponytail: sólo las últimas 800 líneas al DOM; "Copiar" manda el log completo.
  const shown = logs.length > MAX_RENDERED_LOGS ? logs.slice(-MAX_RENDERED_LOGS) : logs
  const box = useRef<HTMLDivElement>(null)
  // Autoscroll sólo si el usuario ya estaba abajo: si subió a leer, no se le arrastra.
  const pinned = useRef(true)

  useEffect(() => {
    const el = box.current
    if (el && pinned.current) el.scrollTop = el.scrollHeight
  }, [logs])

  return (
    <div className="flex h-full flex-col">
      <div className="flex justify-end px-3 py-2">
        <button
          type="button"
          disabled={logs.length === 0}
          onClick={() => bridge.copy(logs.join('\n'))}
          className="px-3 py-1 text-sm text-nomad-accent disabled:text-nomad-muted"
        >
          Copiar
        </button>
      </div>

      {logs.length === 0 ? (
        <p className="px-6 py-24 text-center text-sm text-nomad-muted">
          Aún no hay actividad. Enciende el servidor para ver la consola.
        </p>
      ) : (
        <div
          ref={box}
          onScroll={() => {
            const el = box.current
            if (el) pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40
          }}
          className="min-h-0 flex-1 overflow-y-auto px-3 pb-6 font-mono text-[11px] leading-relaxed"
        >
          {shown.map((line, index) => (
            <div key={index} className={lineTone(line)}>
              {line}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function lineTone(line: string) {
  if (line.includes('ERROR') || line.includes('SEVERE')) return 'text-red-400'
  if (line.includes('WARN')) return 'text-nomad-warn'
  return 'text-nomad-muted'
}

function SettingsTab({
  ramMb,
  onRamChange,
  maxPlayers,
}: {
  ramMb: number
  onRamChange: (ramMb: number) => void
  maxPlayers: number
}) {
  // ponytail: túnel y world border son visuales; se aplican en las fases 5 y 6.
  const [tunnel, setTunnel] = useState(false)
  const [border, setBorder] = useState(3000)

  return (
    <div className="flex flex-col gap-5 p-5">
      <Slider
        label={`RAM asignada: ${ramMb} MB`}
        min={RAM_MIN}
        max={RAM_MAX}
        step={RAM_STEP}
        value={ramMb}
        onChange={onRamChange}
      />
      <p className="text-xs text-nomad-muted">Se aplica la próxima vez que enciendas el servidor.</p>

      <div className="flex items-center justify-between">
        <span className="text-sm text-nomad-text">Jugadores máximos</span>
        <span className="text-sm text-nomad-muted">{maxPlayers}</span>
      </div>

      <div className="flex items-center justify-between">
        <span className="text-sm text-nomad-text">Túnel Playit.gg</span>
        <button
          type="button"
          role="switch"
          aria-checked={tunnel}
          onClick={() => setTunnel((on) => !on)}
          className={`relative h-6 w-11 rounded-full transition-colors ${tunnel ? 'bg-nomad-accent' : 'bg-nomad-border'}`}
        >
          <span
            className={`absolute top-0.5 left-0.5 size-5 rounded-full bg-white transition-transform ${tunnel ? 'translate-x-5' : ''}`}
          />
        </button>
      </div>

      <Slider
        label={`World border: ${border} bloques`}
        min={1000}
        max={10000}
        step={1000}
        value={border}
        onChange={setBorder}
      />
    </div>
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
      <span className="text-sm text-nomad-text">{label}</span>
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
      <p className="font-pixel text-lg text-nomad-text">Nuevo servidor</p>

      <div className="flex flex-col gap-4">
        <input
          autoFocus
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="Nombre"
          className="rounded-xl border border-nomad-border bg-nomad-bg px-3 py-2.5 text-sm text-nomad-text outline-none focus:border-nomad-accent"
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
        <button type="button" onClick={onDismiss} className="px-3 py-2 text-sm text-nomad-muted">
          Cancelar
        </button>
        <button
          type="button"
          disabled={name.trim().length === 0}
          onClick={() => onCreate(name.trim(), ramMb, maxPlayers)}
          className="rounded-lg bg-nomad-accent px-4 py-2 text-sm text-white disabled:opacity-40"
        >
          Crear
        </button>
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
      <p className="font-pixel text-lg text-nomad-text">{title}</p>
      <p className="text-sm text-nomad-muted">{body}</p>
      <div className="flex justify-end gap-2">
        <button type="button" onClick={onDismiss} className="px-3 py-2 text-sm text-nomad-muted">
          Cancelar
        </button>
        <button type="button" onClick={onConfirm} className="rounded-lg bg-red-500 px-4 py-2 text-sm text-white">
          {confirm}
        </button>
      </div>
    </Modal>
  )
}

function Modal({ children, onDismiss }: { children: ReactNode; onDismiss: () => void }) {
  return (
    <div className="fixed inset-0 z-30 grid place-items-center bg-black/60 p-6" onClick={onDismiss}>
      <div
        className="flex w-full max-w-sm flex-col gap-5 rounded-2xl border border-nomad-border bg-nomad-surface p-5"
        onClick={(event) => event.stopPropagation()}
      >
        {children}
      </div>
    </div>
  )
}
