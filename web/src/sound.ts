/**
 * Efectos de sonido de la UI (WebAudio, sin assets).
 *
 * Chrome/WebView sólo permite crear/reanudar un `AudioContext` dentro de un gesto del usuario,
 * por eso [primeAudio] se llama al pulsar "Iniciar"/"Reiniciar" y la campanita suena después.
 */

let ctx: AudioContext | null = null

function context(): AudioContext | null {
  const Ctor =
    window.AudioContext ??
    (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  if (!Ctor) return null
  ctx ??= new Ctor()
  return ctx
}

/** Crea/reanuda el contexto dentro de un gesto (al pulsar un botón). */
export function primeAudio() {
  const audio = context()
  if (audio && audio.state === 'suspended') void audio.resume()
}

/** Campanita de dos notas cuando el servidor termina de arrancar. */
export function playChime() {
  const audio = context()
  if (!audio) return
  void audio.resume()
  const now = audio.currentTime
  for (const [index, frequency] of [659.25, 987.77].entries()) {
    const oscillator = audio.createOscillator()
    const gain = audio.createGain()
    oscillator.type = 'sine'
    oscillator.frequency.value = frequency
    const at = now + index * 0.14
    gain.gain.setValueAtTime(0.0001, at)
    gain.gain.exponentialRampToValueAtTime(0.25, at + 0.02)
    gain.gain.exponentialRampToValueAtTime(0.0001, at + 0.6)
    oscillator.connect(gain).connect(audio.destination)
    oscillator.start(at)
    oscillator.stop(at + 0.65)
  }
}
