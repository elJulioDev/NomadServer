import { DEFAULT_SETTINGS, type NomadBridge, type ServerSettings, type ServerSummary, type Snapshot } from './types'

const SAMPLE_LINES = [
  'Preparing level "world"',
  'Preparing start region for dimension minecraft:overworld',
  'Time elapsed: 412 ms',
  'Running AutoSave #1',
  "Saving chunks for level 'ServerLevel[world]'/minecraft:overworld",
  'Flushing Chunk IO',
]

const randomSuffix = () => Math.random().toString(16).slice(2, 6)
const clock = () => new Date().toTimeString().slice(0, 8)

/**
 * Datos falsos para `npm run dev`: la UI se diseña en el navegador sin teléfono ni Gradle.
 * Sólo existe en el bundle de diseño; nunca se ejecuta dentro del WebView (ahí manda Kotlin).
 */
export function createMock(): NomadBridge {
  const servers: ServerSummary[] = [
    { id: 'demo-survival', name: 'Survival', ramMb: 2048, maxPlayers: 20, status: 'Stopped', players: [], version: '1.21.8', iconVersion: null },
    { id: 'demo-creativo', name: 'Creativo', ramMb: 4096, maxPlayers: 10, status: 'Stopped', players: [], version: null, iconVersion: null },
  ]
  const settings: Record<string, ServerSettings> = {}
  const logs: Record<string, string[]> = {}
  const ram: Record<string, number | null> = {}
  const progress: Record<string, number> = {}
  const autoStop: Record<string, number | null> = {}
  const timers = new Map<string, number>()
  let activeId: string | null = null
  let lastLogId: string | null = null
  let lastLogCount = 0

  const emit = () => {
    const snapshot: Snapshot = { servers: servers.map((s) => ({ ...s })), active: null, lanAddress: null }
    const active = servers.find((s) => s.id === activeId)
    if (active) {
      const list = logs[active.id] ?? []
      const reset = lastLogId !== active.id
      snapshot.active = {
        id: active.id,
        status: active.status,
        ramUsedMb: ram[active.id] ?? null,
        players: [...active.players],
        logs: reset ? list : list.slice(lastLogCount),
        logsReset: reset,
        settings: settings[active.id] ?? DEFAULT_SETTINGS,
        tps: active.status === 'Running' ? 20 : null,
        startProgress: active.status === 'Running' ? 100 : (progress[active.id] ?? 0),
        autoStopSeconds: autoStop[active.id] ?? null,
      }
      lastLogId = active.id
      lastLogCount = list.length
      if (active.status === 'Running') snapshot.lanAddress = '192.168.1.42'
    } else {
      lastLogId = null
      lastLogCount = 0
    }
    window.onNomadState?.(JSON.stringify(snapshot))
  }

  const push = (id: string, line: string) => {
    // El mock no recorta: el cliente ya tiene su propio tope (MAX_LOGS).
    ;(logs[id] ??= []).push(`[${clock()}] [Server thread/INFO]: ${line}`)
  }

  const start: NomadBridge['startServer'] = (id, ramMb, maxPlayers) => {
    const server = servers.find((s) => s.id === id)
    if (!server || server.status === 'Running' || server.status === 'Starting') return
    server.status = 'Starting'
    server.ramMb = ramMb
    server.maxPlayers = maxPlayers
    logs[id] = []
    lastLogId = null
    progress[id] = 0
    autoStop[id] = null
    push(id, 'Iniciando servidor (mock)…')
    emit()
    let tick = 0
    const timer = window.setInterval(() => {
      tick++
      push(id, SAMPLE_LINES[tick % SAMPLE_LINES.length]!)
      if (server.status === 'Starting') progress[id] = Math.min(99, (progress[id] ?? 0) + 34)
      if (tick === 3) {
        push(id, 'Done (4.231s)! For help, type "help"')
        server.status = 'Running'
        progress[id] = 100
        ram[id] = Math.round(ramMb * 0.4)
        if (server.players.length === 0) autoStop[id] = 120
      }
      if (server.status === 'Running' && tick % 2 === 0) {
        ram[id] = Math.min(ramMb, (ram[id] ?? 0) + Math.round(ramMb * 0.02))
      }
      if (autoStop[id] != null) {
        if (server.players.length > 0) {
          autoStop[id] = null
        } else if ((autoStop[id] = (autoStop[id] ?? 0) - 1) <= 0) {
          autoStop[id] = null
          push(id, 'Nadie se conectó; apagando el servidor')
          window.clearInterval(timer)
          timers.delete(id)
          server.status = 'Stopped'
          server.players = []
          ram[id] = null
          progress[id] = 0
        }
      }
      emit()
      if (tick > 300) window.clearInterval(timer)
    }, 700)
    timers.set(id, timer)
  }

  return {
    ready: emit,
    openServer: (id) => {
      activeId = id
      emit()
    },
    closeServer: () => {
      activeId = null
      emit()
    },
    createServer: (name, ramMb, maxPlayers, settingsJson, _iconDataUrl) => {
      const created: ServerSummary = {
        id: `demo-${randomSuffix()}`,
        name,
        ramMb,
        maxPlayers,
        status: 'Stopped',
        players: [],
        version: null,
        iconVersion: null,
      }
      servers.push(created)
      try {
        settings[created.id] = JSON.parse(settingsJson) as ServerSettings
      } catch {
        // JSON inválido en modo diseño: se ignora.
      }
      emit()
    },
    deleteServer: (id) => {
      const index = servers.findIndex((s) => s.id === id)
      if (index >= 0) servers.splice(index, 1)
      if (activeId === id) activeId = null
      emit()
    },
    updateSettings: (id, json) => {
      try {
        const parsed = JSON.parse(json) as ServerSettings
        settings[id] = parsed
        const server = servers.find((s) => s.id === id)
        if (server) server.maxPlayers = parsed.maxPlayers
      } catch {
        // JSON inválido en modo diseño: se ignora.
      }
      emit()
    },
    // El mock no sirve PNGs; el icono se previsualiza localmente al elegirlo.
    setServerIcon: () => {},
    extendStartTimer: (id) => {
      if (autoStop[id] != null) autoStop[id] = (autoStop[id] ?? 0) + 60
      emit()
    },
    restartServer: (id, ramMb, maxPlayers) => {
      const server = servers.find((s) => s.id === id)
      if (!server) return
      window.clearInterval(timers.get(id))
      timers.delete(id)
      server.status = 'Stopped'
      server.players = []
      ram[id] = null
      autoStop[id] = null
      progress[id] = 0
      push(id, 'Reiniciando servidor…')
      emit()
      window.setTimeout(() => start(id, ramMb, maxPlayers), 1200)
    },
    startServer: start,
    stopServer: (id) => {
      const server = servers.find((s) => s.id === id)
      if (!server || (server.status !== 'Running' && server.status !== 'Starting')) return
      server.status = 'Stopping'
      push(id, 'Deteniendo servidor…')
      emit()
      window.clearInterval(timers.get(id))
      timers.delete(id)
      window.setTimeout(() => {
        server.status = 'Stopped'
        server.players = []
        ram[id] = null
        emit()
      }, 1200)
    },
    sendCommand: (id, text) => {
      const clean = text.replace(/^\//, '')
      if (/^say\s+/i.test(clean)) push(id, `[Server] ${clean.slice(4)}`)
      else push(id, clean)
      emit()
    },
    copy: (text) => {
      void navigator.clipboard?.writeText(text)
    },
  }
}
