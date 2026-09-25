export type Status = 'Stopped' | 'Starting' | 'Running' | 'Stopping' | 'Error'

export type Tab = 'panel' | 'console' | 'players' | 'settings'

/** Una versión vanilla del manifest de Mojang. */
export interface VersionOption {
  id: string
  type: string
  releaseTime: string
}

export type Gamemode = 'survival' | 'creative' | 'spectator'

export type Difficulty = 'peaceful' | 'easy' | 'normal' | 'hard'

/** Ajustes editables de `server.properties` (se aplican al próximo arranque). */
export interface ServerSettings {
  motd: string
  maxPlayers: number
  gamemode: Gamemode
  difficulty: Difficulty
  allowFlight: boolean
  whitelist: boolean
  cracked: boolean
  spawnProtection: number
}

export const DEFAULT_SETTINGS: ServerSettings = {
  motd: 'NomadServer',
  maxPlayers: 20,
  gamemode: 'survival',
  difficulty: 'easy',
  allowFlight: false,
  whitelist: false,
  cracked: false,
  spawnProtection: 16,
}

export interface ServerSummary {
  id: string
  name: string
  ramMb: number
  maxPlayers: number
  status: Status
  players: string[]
  version: string | null
  /** mtime de `server-icon.png`, o null si el servidor no tiene icono propio. */
  iconVersion: number | null
}

export interface ActiveServer {
  id: string
  status: Status
  ramUsedMb: number | null
  players: string[]
  /** Sólo las líneas nuevas desde el push anterior (todas si `logsReset`). */
  logs: string[]
  /** true cuando hay que reemplazar el log acumulado en vez de añadir. */
  logsReset: boolean
  settings: ServerSettings
  /** TPS 0..20 (estimados), o null si está apagado. */
  tps: number | null
  /** Progreso del arranque 0..100. */
  startProgress: number
  /** Segundos hasta el auto-apagado por inactividad; null si no aplica. */
  autoStopSeconds: number | null
  /** Nombres con OP (de `ops.json`). */
  ops: string[]
  /** Nombres en la lista blanca (de `whitelist.json`). */
  whitelist: string[]
}

export interface Snapshot {
  servers: ServerSummary[]
  active: ActiveServer | null
  lanAddress: string | null
  /** Versiones del manifest, sólo tras `fetchVersions()`. */
  versions?: VersionOption[]
}

/** Lo que Kotlin expone como `window.NomadBridge`. */
export interface NomadBridge {
  ready(): void
  openServer(id: string): void
  closeServer(): void
  /** `settingsJson` es un `ServerSettings`; `iconDataUrl` un data URL o `''`; `mcVersion` una release. */
  createServer(
    name: string,
    ramMb: number,
    maxPlayers: number,
    settingsJson: string,
    iconDataUrl: string,
    mcVersion: string,
  ): void
  /** Trae el manifest de Mojang (se entrega en `snapshot.versions`). */
  fetchVersions(): void
  /** `op` | `deop` | `kick` | `ban` | `pardon` | `whitelistAdd` | `whitelistRemove`. */
  playerAction(id: string, action: string, name: string): void
  setWhitelistEnabled(id: string, enabled: boolean): void
  deleteServer(id: string): void
  startServer(id: string, ramMb: number, maxPlayers: number): void
  stopServer(id: string): void
  /** Apaga y vuelve a encender el servidor. */
  restartServer(id: string, ramMb: number, maxPlayers: number): void
  /** Suma +1 minuto a la ventana de auto-apagado por inactividad. */
  extendStartTimer(id: string): void
  /** Envía una línea a la consola del servidor (stdin), como en el panel de Aternos. */
  sendCommand(id: string, text: string): void
  updateSettings(id: string, json: string): void
  /** data URL (`data:image/…;base64,…`) con el icono de 64x64. */
  setServerIcon(id: string, dataUrl: string): void
  copy(text: string): void
}

/** Tope de log que retiene el cliente; Kotlin recorta el delta, no el historial. */
export const MAX_LOGS = 2000

/** URL del icono servido por Kotlin (`/server-icons/<id>/server-icon.png`). */
export const serverIconUrl = (id: string, version: number) =>
  `https://appassets.androidplatform.net/server-icons/${id}/server-icon.png?v=${version}`
