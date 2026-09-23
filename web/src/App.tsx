import { useEffect, useRef, useState } from 'react'
import { bridge, subscribe } from './bridge'
import { DetailScreen, ServersScreen } from './screens'
import { MAX_LOGS, type Snapshot, type Tab } from './types'

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
          onBack={() => {
            setOpenId(null)
            bridge.closeServer()
          }}
        />
      ) : (
        <ServersScreen
          servers={snapshot.servers}
          onOpen={open}
          onCreate={(name, ramMb, maxPlayers) => bridge.createServer(name, ramMb, maxPlayers)}
          onDelete={(id) => bridge.deleteServer(id)}
        />
      )}
    </div>
  )
}
