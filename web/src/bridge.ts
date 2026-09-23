import type { NomadBridge, Snapshot } from './types'
import { createMock } from './mock'

declare global {
  interface Window {
    NomadBridge?: NomadBridge
    onNomadState?: (json: string) => void
    nomadHandleBack?: () => boolean
  }
}

/** true dentro del WebView de Android; false en el navegador (se usa el mock de diseño). */
export const isNative = typeof window.NomadBridge !== 'undefined'

export const bridge: NomadBridge = window.NomadBridge ?? createMock()

/** Registra el listener de estado y avisa a Kotlin de que la web está lista. */
export function subscribe(listener: (snapshot: Snapshot) => void): () => void {
  const onState = (json: string) => listener(JSON.parse(json) as Snapshot)
  window.onNomadState = onState
  bridge.ready()
  return () => {
    if (window.onNomadState === onState) window.onNomadState = undefined
  }
}
