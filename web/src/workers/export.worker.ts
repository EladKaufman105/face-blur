// Export: re-encodes the video with the faces hidden (WebCodecs through Mediabunny); the sound
// is copied as is when the container allows it.
import { ALL_FORMATS, AudioSample, AudioSampleSource, BlobSource, BufferTarget, Conversion, Input, Mp4OutputFormat, Output, QUALITY_HIGH, canEncodeVideo, getFirstEncodableAudioCodec } from 'mediabunny'
import { BlurRenderer, ellipseFor } from '../lib/renderer'
import { frameAt, trackBox, type Look, type Track } from '../lib/types'
import { mixInto, type SfxSamples } from '../lib/sfx'

export type ExportRequest = { file: File; times: Float64Array; tracks: Track[]; look: Look; sfx: SfxSamples[]; overlay: ImageBitmap | null }
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

async function run({ file, times, tracks, look, sfx, overlay }: ExportRequest) {
  const t0 = performance.now()
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
  let scratch: OffscreenCanvas | null = null
  const output = new Output({ format: new Mp4OutputFormat({ fastStart: 'in-memory' }), target: new BufferTarget() })
  // A video without sound gets a sound track for the effects (fed alongside the conversion).
  let feed: (() => Promise<void>) | null = null
  if (sfx.length > 0 && !(await input.getPrimaryAudioTrack())) {
    const rate = 48000
    const audioCodec = await getFirstEncodableAudioCodec(['aac', 'opus'], { numberOfChannels: 2, sampleRate: rate, quality: QUALITY_HIGH })
    if (audioCodec) {
      const src = new AudioSampleSource({ codec: audioCodec, quality: QUALITY_HIGH })
      output.addAudioTrack(src)
      const end = await video.computeDuration()
      feed = async () => {
        for (let t = 0; t < end; t += 1) {
          const n = Math.min(rate, Math.ceil((end - t) * rate))
          const planes = [new Float32Array(n), new Float32Array(n)]
          mixInto(planes, rate, t, sfx)
          const data = new Float32Array(2 * n)
          data.set(planes[0], 0)
          data.set(planes[1], n)
          await src.add(new AudioSample({ data, format: 'f32-planar', numberOfChannels: 2, sampleRate: rate, timestamp: t }))
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
        const i = frameAt(times, sample.timestamp)
        const faces = []
        for (const t of tracks) {
          const b = trackBox(t, i)
          if (b) faces.push(ellipseFor(b, w, h, look.padding))
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
        return canvas
      },
    },
    // The sound is copied as is, or re-encoded with the sound effects mixed in.
    audio: sfx.length === 0 ? {} : {
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
        const data = new Float32Array(ch * n)
        planes.forEach((p, c) => data.set(p, c * n))
        return new AudioSample({ data, format: 'f32-planar', numberOfChannels: ch, sampleRate: sample.sampleRate, timestamp: sample.timestamp })
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
