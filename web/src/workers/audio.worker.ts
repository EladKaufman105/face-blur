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
  | { type?: 'voice'; id?: number; file: File; voice: VoiceFx; bleeps: Bleep[]; tone: boolean; clean?: Cleanup }
  | { type: 'silences'; file: File; minPause: number; words: Range[] }
export type AudioMessage =
  | { type: 'started'; id?: number }
  | { type: 'progress'; id?: number }
  | { type: 'done'; id?: number; rate: number; data: Float32Array; lufs: number | null; gainDb: number }
  | { type: 'silences'; ranges: Range[] }
  | { type: 'error'; id?: number; code: string; message: string }

let cache: { file: File; rate: number; data: Float32Array; channels: number } | null = null

self.onmessage = async (e: MessageEvent<AudioRequest>) => {
  const r = e.data
  // A voice request's id comes back with its answer, so a late answer is not taken for a newer one's.
  const id = r.type === 'silences' ? undefined : r.id
  const post = (m: AudioMessage) => (self as unknown as Worker).postMessage(m)
  // Heartbeats, so the page can tell a long job from a dead worker (at most one a second).
  let last = 0
  const beat = () => { const n = Date.now(); if (n - last >= 1000) { last = n; post({ type: 'progress', id }) } }
  if (r.type !== 'silences') post({ type: 'started', id })
  try {
    if (cache?.file !== r.file) cache = await decode(r.file, beat)
    if (!cache) return (self as unknown as Worker).postMessage({ type: 'error', id, code: 'no-audio', message: 'no sound' } satisfies AudioMessage)
    if (r.type === 'silences') {
      const ranges = findSilences(cache.data, cache.rate, r.minPause, r.words)
      return (self as unknown as Worker).postMessage({ type: 'silences', ranges } satisfies AudioMessage)
    }
    const { voice, bleeps, tone } = r
    const clean = r.clean ?? NO_CLEANUP
    const c = cleanupOn(clean) ? cleanup(cache.data, cache.rate, clean, beat) : { data: cache.data, lufs: null, gainDb: 0 }
    const data = processVoice(c.data, cache.rate, voice, bleeps, tone, beat)
    const msg: AudioMessage = { type: 'done', id, rate: cache.rate, data, lufs: c.lufs, gainDb: c.gainDb }
    ;(self as unknown as Worker).postMessage(msg, [data.buffer])
  } catch (err) {
    ;(self as unknown as Worker).postMessage({ type: 'error', id, code: (err as { code?: string })?.code === 'undecodable' ? 'undecodable' : 'failed', message: String((err as Error)?.message ?? err) } satisfies AudioMessage)
  }
}

async function decode(file: File, beat: () => void): Promise<{ file: File; rate: number; data: Float32Array; channels: number } | null> {
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const track = await input.getPrimaryAudioTrack()
  if (!track) return null
  // A sound the browser can't decode is not "no sound": the original voice is in it, so the caller must not export it as if it were clean.
  if (!(await track.canDecode())) throw Object.assign(new Error('the sound cannot be decoded'), { code: 'undecodable' })
  const parts: Float32Array[] = []
  let rate = 48000, total = 0
  for await (const s of new AudioSampleSink(track).samples()) {
    beat()
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
