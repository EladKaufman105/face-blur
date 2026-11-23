// The video's sound with the voice change and the bleeps, for hearing in the editor and for the
// export: decoded once (mono, at its own rate), processed off the main thread.
import { ALL_FORMATS, AudioSampleSink, BlobSource, Input } from 'mediabunny'
import { processVoice, type Bleep, type VoiceFx } from '../lib/voice'
import { findSilences } from '../lib/silence'
import { cleanup, cleanupOn, NO_CLEANUP, type Cleanup } from '../lib/cleanup'
import type { Range } from '../lib/cutmap'

/**
 * The sound cleaned up, the voice change and the bleeps (with how loud it was and the gain to
 * the networks' level, when asked for); or ('silences') the pauses longer than [minPause] seconds.
 */
export type AudioRequest =
  | { type?: 'voice'; file: File; voice: VoiceFx; bleeps: Bleep[]; tone: boolean; clean?: Cleanup }
  | { type: 'silences'; file: File; minPause: number; words: Range[] }
export type AudioMessage =
  | { type: 'done'; rate: number; data: Float32Array; lufs: number | null; gainDb: number }
  | { type: 'silences'; ranges: Range[] }
  | { type: 'error'; code: string; message: string }

let cache: { file: File; rate: number; data: Float32Array; channels: number } | null = null

self.onmessage = async (e: MessageEvent<AudioRequest>) => {
  try {
    const r = e.data
    if (cache?.file !== r.file) cache = await decode(r.file)
    if (!cache) return (self as unknown as Worker).postMessage({ type: 'error', code: 'no-audio', message: 'no sound' } satisfies AudioMessage)
    if (r.type === 'silences') {
      const ranges = findSilences(cache.data, cache.rate, r.minPause, r.words)
      return (self as unknown as Worker).postMessage({ type: 'silences', ranges } satisfies AudioMessage)
    }
    const { voice, bleeps, tone } = r
    const clean = r.clean ?? NO_CLEANUP
    const c = cleanupOn(clean) ? cleanup(cache.data, cache.rate, clean) : { data: cache.data, lufs: null, gainDb: 0 }
    const data = processVoice(c.data, cache.rate, voice, bleeps, tone)
    const msg: AudioMessage = { type: 'done', rate: cache.rate, data, lufs: c.lufs, gainDb: c.gainDb }
    ;(self as unknown as Worker).postMessage(msg, [data.buffer])
  } catch (err) {
    ;(self as unknown as Worker).postMessage({ type: 'error', code: 'failed', message: String((err as Error)?.message ?? err) } satisfies AudioMessage)
  }
}

async function decode(file: File): Promise<{ file: File; rate: number; data: Float32Array; channels: number } | null> {
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
  return { file, rate, data, channels: track.numberOfChannels }
}
