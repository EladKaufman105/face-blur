// Export: re-encodes the video with the faces hidden (WebCodecs through Mediabunny); the sound
// is copied as is when the container allows it.
import { ALL_FORMATS, BlobSource, BufferTarget, Conversion, Input, Mp4OutputFormat, Output, QUALITY_HIGH, canEncodeVideo } from 'mediabunny'
import { BlurRenderer, ellipseFor } from '../lib/renderer'
import { frameAt, trackBox, type Look, type Track } from '../lib/types'

export type ExportRequest = { file: File; times: Float64Array; tracks: Track[]; look: Look }
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

async function run({ file, times, tracks, look }: ExportRequest) {
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
  let scratch: OffscreenCanvas | null = null
  const output = new Output({ format: new Mp4OutputFormat({ fastStart: 'in-memory' }), target: new BufferTarget() })
  const conversion = await Conversion.init({
    input,
    output,
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
    audio: {},
  })
  if (!conversion.isValid) {
    return post({ type: 'error', code: 'invalid', message: conversion.discardedTracks.map((d) => `${d.track.type}: ${d.reason}`).join(', ') })
  }
  conversion.onProgress = (p) => post({ type: 'progress', progress: p })
  await conversion.execute()
  const buf = (output.target as BufferTarget).buffer!
  post({ type: 'done', blob: new Blob([buf], { type: 'video/mp4' }), ms: performance.now() - t0 })
}
