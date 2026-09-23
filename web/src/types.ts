export type Status = 'Stopped' | 'Starting' | 'Running' | 'Stopping' | 'Error'

export type Tab = 'panel' | 'console' | 'settings'

export interface ServerSummary {
  id: string
  name: string
  ramMb: number
  maxPlayers: number
  status: Status
  players: string[]
  version: string | null
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
}

export interface Snapshot {
  servers: ServerSummary[]
  active: ActiveServer | null
  lanAddress: string | null
}

/** Lo que Kotlin expone como `window.NomadBridge`. */
export interface NomadBridge {
  ready(): void
  openServer(id: string): void
  closeServer(): void
  createServer(name: string, ramMb: number, maxPlayers: number): void
  deleteServer(id: string): void
  startServer(id: string, ramMb: number, maxPlayers: number): void
  stopServer(id: string): void
  copy(text: string): void
}

/** Tope de log que retiene el cliente; Kotlin recorta el delta, no el historial. */
export const MAX_LOGS = 2000
