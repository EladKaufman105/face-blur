// Export: re-encodes the video with the faces hidden (WebCodecs through Mediabunny); the sound
// is copied as is when the container allows it.
import { ALL_FORMATS, AudioSample, AudioSampleSource, BlobSource, BufferTarget, Conversion, Input, Mp4OutputFormat, Output, QUALITY_HIGH, VideoSample, canEncodeVideo, getFirstEncodableAudioCodec } from 'mediabunny'
import { BlurRenderer, ellipseFor } from '../lib/renderer'
import { frameAt, trackBox, type Look, type Track } from '../lib/types'
import { mixInto, type SfxSamples } from '../lib/sfx'
import { cueAt, drawSubtitle, type Cue, type SubStyle } from '../lib/subtitles'
import { CutMap, type Range } from '../lib/cutmap'

export type ExportRequest = { file: File; times: Float64Array; tracks: Track[]; look: Look; sfx: SfxSamples[]; overlay: ImageBitmap | null; cues: Cue[]; subStyle: SubStyle; font: string; voice: { rate: number; data: Float32Array } | null; cuts: Range[] }
export type ExportMessage =
  | { type: 'progress'; progress: number }
  | { type: 'done'; blob: Blob; ms: number }
  | { type: 'error'; code: string; message: string }

const post = (m: ExportMessage) => (self as unknown as Worker).postMessage(m)

self.onmessage = async (e: MessageEvent<ExportRequest>) => {
  try {
    await run(e.data)
  } catch (err) {
    post({ type: 'error', code: 'failed', message: String((err as Error)?.message ?? err) })
  }
}

/**
 * The parts of a sound (planes at [rate], starting at [ts] in the source) that aren't cut, each
 * at its time in the result.
 */
function keptSamples(planes: Float32Array[], rate: number, ts: number, cuts: CutMap): AudioSample[] {
  const n = planes[0]?.length ?? 0
  const ch = planes.length
  const out: AudioSample[] = []
  for (const [a, b] of cuts.pieces(ts, ts + n / rate)) {
    const i0 = Math.max(0, Math.round((a - ts) * rate)), i1 = Math.min(n, Math.round((b - ts) * rate))
    if (i1 <= i0) continue
    const m = i1 - i0
    const data = new Float32Array(ch * m)
    planes.forEach((p, c) => data.set(p.subarray(i0, i1), c * m))
    out.push(new AudioSample({ data, format: 'f32-planar', numberOfChannels: ch, sampleRate: rate, timestamp: cuts.out(a) }))
  }
  return out
}

async function run({ file, times, tracks, look, sfx, overlay, cues, subStyle, font, voice, cuts: cutList }: ExportRequest) {
  const t0 = performance.now()
  const cuts = new CutMap(cutList ?? [])
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const video = await input.getPrimaryVideoTrack()
  if (!video) return post({ type: 'error', code: 'no-video', message: 'no video track' })
  const w = video.displayWidth, h = video.displayHeight
  const ew = w + (w & 1), eh = h + (h & 1)
  const codec = (await canEncodeVideo('avc', { width: ew, height: eh })) ? 'avc' : (await canEncodeVideo('hevc', { width: ew, height: eh })) ? 'hevc' : null
  if (!codec) return post({ type: 'error', code: 'encoder', message: 'no H.264/HEVC encoder' })

  const canvas = new OffscreenCanvas(w, h)
  const renderer = new BlurRenderer(canvas)
  renderer.setOverlay(overlay)
  // Subtitles: the watermark and the current cue on one picture, redrawn when the cue changes.
  let family = "'Allo Hebrew', system-ui, sans-serif"
  if (cues.length > 0) {
    try {
      const f = await new FontFace('Allo Hebrew', `url(${font})`, { weight: '100 900' }).load()
      ;(self as any).fonts?.add(f)
    } catch {
      family = 'system-ui, sans-serif'
    }
  }
  const layer = cues.length > 0 ? new OffscreenCanvas(w, h) : null
  let shownCue: Cue | null | undefined = undefined
  let scratch: OffscreenCanvas | null = null
  const output = new Output({ format: new Mp4OutputFormat({ fastStart: 'in-memory' }), target: new BufferTarget() })
  // A sound track made here, fed alongside the conversion: the changed voice (with the bleeps),
  // or, for a video without sound, one for the effects.
  let feed: (() => Promise<void>) | null = null
  const silent = !(await input.getPrimaryAudioTrack())
  if (voice || (sfx.length > 0 && silent)) {
    const rate = voice?.rate ?? 48000
    const ch = voice ? 1 : 2
    const audioCodec = await getFirstEncodableAudioCodec(['aac', 'opus'], { numberOfChannels: ch, sampleRate: rate, quality: QUALITY_HIGH })
    if (audioCodec) {
      const src = new AudioSampleSource({ codec: audioCodec, quality: QUALITY_HIGH })
      output.addAudioTrack(src)
      const end = voice ? voice.data.length / rate : await video.computeDuration()
      feed = async () => {
        for (let t = 0; t < end; t += 1) {
          const i0 = Math.round(t * rate)
          const n = Math.min(rate, Math.ceil(end * rate) - i0)
          if (n <= 0) break
          const planes = Array.from({ length: ch }, () => (voice ? voice.data.slice(i0, i0 + n) : new Float32Array(n)))
          mixInto(planes, rate, t, sfx)
          for (const sample of keptSamples(planes, rate, t, cuts)) await src.add(sample)
        }
        src.close()
      }
    }
  }
  const conversion = await Conversion.init({
    input,
    output,
    composable: feed !== null,
    video: {
      codec,
      quality: QUALITY_HIGH,
      forceTranscode: true,
      allowTransformationMetadata: false,
      process: async (sample) => {
        // A cut frame is dropped; the ones after a cut move up.
        if (!cuts.empty && cuts.isCut(sample.timestamp)) return null
        if (layer) {
          const cue = cueAt(cues, sample.timestamp)
          if (cue !== shownCue) {
            shownCue = cue
            const ctx = layer.getContext('2d')!
            ctx.clearRect(0, 0, w, h)
            if (overlay) ctx.drawImage(overlay, 0, 0)
            if (cue) drawSubtitle(ctx, cue.text, subStyle, w, h, family)
            renderer.setOverlay(layer)
          }
        }
        const i = frameAt(times, sample.timestamp)
        const faces = []
        for (const t of tracks) {
          const b = trackBox(t, i)
          if (b) faces.push(ellipseFor(b, w, h, look.padding * (t.scale ?? 1)))
        }
        let frame: VideoFrame | null = null
        try {
          frame = sample.toVideoFrame()
          renderer.draw(frame, w, h, faces, look)
        } catch {
          // Some browsers can't upload a VideoFrame to WebGL: go through a 2D canvas.
          scratch ??= new OffscreenCanvas(w, h)
          const ctx = scratch.getContext('2d')!
          sample.draw(ctx, 0, 0, w, h)
          renderer.draw(scratch, w, h, faces, look)
        } finally {
          frame?.close()
        }
        if (cuts.empty) return canvas
        const ts = sample.timestamp, d = sample.duration
        return new VideoSample(canvas, { timestamp: cuts.out(ts), duration: cuts.kept(ts, ts + d) || d })
      },
    },
    // The sound is copied as is, or re-encoded with the sound effects mixed in (and the cuts).
    audio: voice ? { discard: true } : sfx.length === 0 && cuts.empty ? {} : {
      forceTranscode: true,
      process: (sample) => {
        const ch = sample.numberOfChannels, n = sample.numberOfFrames
        const planes: Float32Array[] = []
        for (let c = 0; c < ch; c++) {
          const p = new Float32Array(n)
          sample.copyTo(p, { planeIndex: c, format: 'f32-planar' })
          planes.push(p)
        }
        mixInto(planes, sample.sampleRate, sample.timestamp, sfx)
        return keptSamples(planes, sample.sampleRate, sample.timestamp, cuts)
      },
    },
  })
  if (!conversion.isValid) {
    return post({ type: 'error', code: 'invalid', message: conversion.discardedTracks.map((d) => `${d.track.type}: ${d.reason}`).join(', ') })
  }
  conversion.onProgress = (p) => post({ type: 'progress', progress: p })
  if (feed) {
    await output.start()
    await Promise.all([conversion.execute(), feed()])
    await output.finalize()
  } else {
    await conversion.execute()
  }
  const buf = (output.target as BufferTarget).buffer!
  post({ type: 'done', blob: new Blob([buf], { type: 'video/mp4' }), ms: performance.now() - t0 })
}
