export type Status = 'Stopped' | 'Starting' | 'Running' | 'Stopping' | 'Error'

export type Tab = 'panel' | 'console' | 'players' | 'world' | 'files' | 'settings'

/** Entrada del explorador de archivos del servidor. */
export interface FileEntry {
  name: string
  directory: boolean
  size: number
  modified: number
}

export interface FileListing {
  path: string
  entries: FileEntry[]
}

/** Tamaños en bytes de las partes del servidor y espacio libre del dispositivo. */
export interface WorldSizes {
  world: number
  nether: number
  end: number
  jar: number
  logs: number
  total: number
  free: number
  worldFiles: number
  netherFiles: number
  endFiles: number
  totalFiles: number
  /** Semilla leída de `level.dat` (string: es un long de 64 bits). Sirve con el server apagado. */
  seed: string | null
}

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
  /** Versión elegida en el perfil (se descarga al arrancar). */
  mcVersion: string | null
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
  /** IPs baneadas (de `banned-ips.json`). */
  bannedIps: string[]
  /** Jugadores baneados (de `banned-players.json`). */
  bannedPlayers: string[]
  /** Último listado de archivos pedido con `listFiles` (null hasta entonces). */
  files: FileListing | null
  /** Semilla del mundo, del comando `/seed` (null si no se pidió). */
  seed: string | null
  /** Tamaños calculados a demanda por `worldInfo` (null hasta entonces). */
  world: WorldSizes | null
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
  /** `op` | `deop` | `kick` | `ban` | `pardon` | `whitelistAdd` | `whitelistRemove` | `banIp` | `pardonIp`. */
  playerAction(id: string, action: string, name: string, reason: string): void
  setWhitelistEnabled(id: string, enabled: boolean): void
  /** Lista un directorio del servidor (`path` relativo a su carpeta); llega en `active.files`. */
  listFiles(id: string, path: string): void
  /** Cambia la versión del perfil (se descarga al próximo arranque; server apagado). */
  setVersion(id: string, version: string): void
  /** Pide la semilla al servidor (manda `seed` por consola; requiere estar encendido). */
  requestSeed(id: string): void
  /** Calcula tamaños/espacio; el resultado llega en `active.world`. */
  worldInfo(id: string): void
  /** `nether` | `end`: borra esa dimensión para que se regenere (server apagado). */
  regenerateWorld(id: string, dimension: string): void
  /** Abre el selector de `.zip` y reemplaza el mundo (server apagado). */
  importWorld(id: string): void
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
