import { useEffect, useRef, useState } from 'react'
import { bridge, subscribe } from './bridge'
import { DetailScreen, ServersScreen } from './screens'
import { playChime } from './sound'
import { MAX_LOGS, type Snapshot, type Status, type Tab } from './types'

const EMPTY: Snapshot = { servers: [], active: null, lanAddress: null }

export default function App() {
  const [snapshot, setSnapshot] = useState<Snapshot>(EMPTY)
  const [logs, setLogs] = useState<string[]>([])
  const [openId, setOpenId] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('panel')

  // El log llega en deltas: se acumula aquí y sólo se reemplaza si Kotlin pide un reset
  // (`logsReset`) o si cambiamos de servidor. Así el array no se re-crea entero en cada push.
  const lastActiveId = useRef<string | null>(null)
  useEffect(
    () =>
      subscribe((next) => {
        const active = next.active
        if (!active) {
          lastActiveId.current = null
          setLogs((prev) => (prev.length === 0 ? prev : []))
        } else if (active.logsReset || lastActiveId.current !== active.id) {
          lastActiveId.current = active.id
          setLogs(active.logs)
        } else if (active.logs.length > 0) {
          setLogs((prev) => {
            const merged = prev.concat(active.logs)
            return merged.length > MAX_LOGS ? merged.slice(-MAX_LOGS) : merged
          })
        }
        setSnapshot(next)
      }),
    [],
  )

  // Campanita cuando el servidor termina de arrancar (Starting -> Running).
  const lastStatus = useRef<Status | null>(null)
  useEffect(() => {
    const status = snapshot.active?.status ?? null
    if (lastStatus.current === 'Starting' && status === 'Running') playChime()
    lastStatus.current = status
  }, [snapshot.active?.status])

  // El botón "atrás" de Android pregunta primero a la web (ver MainActivity/WebUi).
  const openIdRef = useRef(openId)
  useEffect(() => {
    openIdRef.current = openId
  }, [openId])
  useEffect(() => {
    window.nomadHandleBack = () => {
      if (!openIdRef.current) return false
      openIdRef.current = null
      setOpenId(null)
      bridge.closeServer()
      return true
    }
  }, [])

  const open = (id: string, nextTab: Tab) => {
    setTab(nextTab)
    setOpenId(id)
    bridge.openServer(id)
  }

  // Eco local del comando enviado: la consola del server no lo relee en stdout.
  const sendCommand = (text: string) => {
    if (!openId) return
    setLogs((prev) => {
      const merged = prev.concat(`> ${text}`)
      return merged.length > MAX_LOGS ? merged.slice(-MAX_LOGS) : merged
    })
    bridge.sendCommand(openId, text)
  }

  const selected = openId ? snapshot.servers.find((server) => server.id === openId) : undefined

  return (
    <div className="h-full">
      {selected ? (
        <DetailScreen
          server={selected}
          active={snapshot.active?.id === selected.id ? snapshot.active : null}
          logs={logs}
          lanAddress={snapshot.lanAddress}
          tab={tab}
          onTabChange={setTab}
          onCommand={sendCommand}
          onBack={() => {
            setOpenId(null)
            bridge.closeServer()
          }}
        />
      ) : (
        <ServersScreen
          servers={snapshot.servers}
          versions={snapshot.versions}
          onOpen={open}
          onCreate={(name, ramMb, settings, icon, mcVersion) =>
            bridge.createServer(
              name,
              ramMb,
              settings.maxPlayers,
              JSON.stringify(settings),
              icon ?? '',
              mcVersion,
            )
          }
          onDelete={(id) => bridge.deleteServer(id)}
        />
      )}
    </div>
  )
}
