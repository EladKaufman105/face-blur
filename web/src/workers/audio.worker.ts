// The video's sound with the voice change and the bleeps, for hearing in the editor and for the
// export: decoded once (mono, at its own rate), processed off the main thread.
import { ALL_FORMATS, AudioSampleSink, BlobSource, Input } from 'mediabunny'
import { processVoice, type Bleep, type VoiceFx } from '../lib/voice'

export type AudioRequest = { file: File; voice: VoiceFx; bleeps: Bleep[]; tone: boolean }
export type AudioMessage =
  | { type: 'done'; rate: number; data: Float32Array }
  | { type: 'error'; code: string; message: string }

let cache: { file: File; rate: number; data: Float32Array } | null = null

self.onmessage = async (e: MessageEvent<AudioRequest>) => {
  try {
    const { file, voice, bleeps, tone } = e.data
    if (cache?.file !== file) cache = await decode(file)
    if (!cache) return (self as unknown as Worker).postMessage({ type: 'error', code: 'no-audio', message: 'no sound' } satisfies AudioMessage)
    const data = processVoice(cache.data, cache.rate, voice, bleeps, tone)
    const msg: AudioMessage = { type: 'done', rate: cache.rate, data }
    ;(self as unknown as Worker).postMessage(msg, [data.buffer])
  } catch (err) {
    ;(self as unknown as Worker).postMessage({ type: 'error', code: 'failed', message: String((err as Error)?.message ?? err) } satisfies AudioMessage)
  }
}

async function decode(file: File): Promise<{ file: File; rate: number; data: Float32Array } | null> {
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const track = await input.getPrimaryAudioTrack()
  if (!track || !(await track.canDecode())) return null
  const parts: Float32Array[] = []
  let rate = 48000, total = 0
  for await (const s of new AudioSampleSink(track).samples()) {
    rate = s.sampleRate
    const n = s.numberOfFrames, ch = s.numberOfChannels
    const mono = new Float32Array(n)
    const p = new Float32Array(n)
    for (let c = 0; c < ch; c++) {
      s.copyTo(p, { planeIndex: c, format: 'f32-planar' })
      for (let i = 0; i < n; i++) mono[i] += p[i] / ch
    }
    // Where the track starts after the video, leave silence before it.
    const at = Math.round(s.timestamp * rate)
    if (at > total) { parts.push(new Float32Array(at - total)); total = at }
    parts.push(mono)
    total += n
    s.close()
  }
  const data = new Float32Array(total)
  let o = 0
  for (const p of parts) { data.set(p, o); o += p.length }
  return { file, rate, data }
}
