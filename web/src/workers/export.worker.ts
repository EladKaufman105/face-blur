// Export: re-encodes the video with the faces hidden (WebCodecs through Mediabunny); the sound
// is copied as is when the container allows it. Or only the sound, as MP3.
import { ALL_FORMATS, AudioSample, AudioSampleSource, BlobSource, BufferTarget, Conversion, Input, Mp3OutputFormat, Mp4OutputFormat, Output, QUALITY_HIGH, VideoSample, canEncodeAudio, canEncodeVideo, getFirstEncodableAudioCodec, type VideoCodec } from 'mediabunny'
import { registerMp3Encoder } from '@mediabunny/mp3-encoder'
import { DEFAULT_EXPORT, outputSize, videoBitrate, type ExportOptions } from '../lib/exportopts'
import { maskAt, maskIndex, type Background, type PeopleMasks } from '../lib/background'
import { ASPECT_RATIO, cropFraction, viewRect, type Steady } from '../lib/camera'

/** The picture tools: the background, the steadying and the frame that follows the faces (per frame of [times]). */
export type ExportPicture = { background: Background | null; masks: PeopleMasks | null; image: ImageBitmap | null; steady: Steady | null; path: { x: Float32Array; y: Float32Array } | null }
import { BlurRenderer, ellipseFor } from '../lib/renderer'
import { frameAt, trackBox, type Look, type Track } from '../lib/types'
import { mixInto, type SfxSamples } from '../lib/sfx'
import { cueAt, drawSubtitle, type Cue, type SubStyle } from '../lib/subtitles'
import { CutMap, type Range } from '../lib/cutmap'
import { speakerColor } from '../lib/speakers'

export type ExportRequest = { file: File; times: Float64Array; tracks: Track[]; look: Look; sfx: SfxSamples[]; overlay: ImageBitmap | null; cues: Cue[]; subStyle: SubStyle; font: string; voice: { rate: number; data: Float32Array } | null; cuts: Range[]; options?: ExportOptions; picture?: ExportPicture }
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

/** Only the sound, as MP3: the changed voice fed in, or the source's sound (effects mixed, cuts taken out). */
async function mp3({ file, sfx, voice, cuts: cutList }: ExportRequest, t0: number) {
  if (!(await canEncodeAudio('mp3'))) registerMp3Encoder()
  const cuts = new CutMap(cutList ?? [])
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const audio = await input.getPrimaryAudioTrack()
  if (!audio) return post({ type: 'error', code: 'no-audio', message: 'no sound' })
  const output = new Output({ format: new Mp3OutputFormat(), target: new BufferTarget() })
  if (voice) {
    const rate = voice.rate
    const src = new AudioSampleSource({ codec: 'mp3', bitrate: 192e3 })
    output.addAudioTrack(src)
    await output.start()
    const end = voice.data.length / rate
    for (let t = 0; t < end; t += 1) {
      const i0 = Math.round(t * rate)
      const n = Math.min(rate, voice.data.length - i0)
      if (n <= 0) break
      const planes = [voice.data.slice(i0, i0 + n)]
      mixInto(planes, rate, t, sfx)
      for (const sample of keptSamples(planes, rate, t, cuts)) await src.add(sample)
      post({ type: 'progress', progress: Math.min(0.99, (t + 1) / end) })
    }
    src.close()
    await output.finalize()
  } else {
    const conversion = await Conversion.init({
      input,
      output,
      video: { discard: true },
      audio: {
        codec: 'mp3',
        bitrate: 192e3,
        forceTranscode: true,
        process: sfx.length === 0 && cuts.empty ? undefined : (sample) => {
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
    if (!conversion.isValid) return post({ type: 'error', code: 'invalid', message: conversion.discardedTracks.map((d) => `${d.track.type}: ${d.reason}`).join(', ') })
    conversion.onProgress = (p) => post({ type: 'progress', progress: p })
    await conversion.execute()
  }
  const buf = (output.target as BufferTarget).buffer!
  post({ type: 'done', blob: new Blob([buf], { type: 'audio/mpeg' }), ms: performance.now() - t0 })
}

async function run(req: ExportRequest) {
  const t0 = performance.now()
  const o = req.options ?? DEFAULT_EXPORT
  if (o.format === 'mp3') return mp3(req, t0)
  const { file, times, tracks, look, sfx, overlay, cues, subStyle, font, voice, cuts: cutList } = req
  const cuts = new CutMap(cutList ?? [])
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  const video = await input.getPrimaryVideoTrack()
  if (!video) return post({ type: 'error', code: 'no-video', message: 'no video track' })
  const sw = video.displayWidth, sh = video.displayHeight
  // The size, frame rate, codec and bitrate asked for (the codec the browser can make).
  const [w, h] = outputSize(sw, sh, o.shortSide, o.aspect)
  // A frame that follows the faces or a steadied camera: the picture is made at the source's size
  // and the part shown taken from it here (not resized by the conversion).
  const pic = req.picture
  const crop = cropFraction(sw, sh, ASPECT_RATIO[o.aspect])
  const camera = !!pic?.steady || crop[0] < 0.999 || crop[1] < 0.999
  const resized = !camera && (w !== sw + (sw & 1) || h !== sh + (sh & 1))
  const stats = await video.computePacketStats(240).catch(() => null)
  const srcFps = stats && stats.averagePacketRate > 0 ? stats.averagePacketRate : 30
  const wanted: VideoCodec = o.codec === 'hevc' || (o.codec === 'same' && video.codec === 'hevc') ? 'hevc' : 'avc'
  const other: VideoCodec = wanted === 'hevc' ? 'avc' : 'hevc'
  const codec = (await canEncodeVideo(wanted, { width: w, height: h })) ? wanted : (await canEncodeVideo(other, { width: w, height: h })) ? other : null
  if (!codec) return post({ type: 'error', code: 'encoder', message: 'no H.264/HEVC encoder' })
  const bitrate = videoBitrate(o, sw, sh, srcFps, stats?.averageBitrate ?? 0, codec === 'hevc')

  // [rw] x [rh]: what the renderer draws (the frames as they come); [w] x [h]: the result.
  const rw = camera ? sw : w, rh = camera ? sh : h
  const canvas = new OffscreenCanvas(rw, rh)
  const renderer = new BlurRenderer(canvas)
  if (!camera) renderer.setOverlay(overlay)
  const out = camera ? new OffscreenCanvas(w, h) : null
  const octx = out?.getContext('2d') ?? null
  if (octx) octx.imageSmoothingQuality = 'high'
  renderer.setBackground(pic?.background ?? null)
  if (pic?.image) renderer.setBackgroundImage(pic.image, 'image')
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
      bitrate,
      forceTranscode: true,
      allowTransformationMetadata: false,
      ...(resized ? { width: w, height: h, fit: 'fill' as const } : {}),
      ...(camera ? { processedWidth: w, processedHeight: h } : {}),
      ...(o.fps > 0 && o.fps < srcFps - 0.5 ? { frameRate: o.fps } : {}),
      process: async (sample) => {
        // A cut frame is dropped; the ones after a cut move up.
        if (!cuts.empty && cuts.isCut(sample.timestamp)) return null
        if (layer) {
          const cue = cueAt(cues, sample.timestamp)
          if (cue !== shownCue) {
            shownCue = cue
            const ctx = layer.getContext('2d')!
            ctx.clearRect(0, 0, w, h)
            if (overlay) ctx.drawImage(overlay, 0, 0, w, h)
            if (cue) drawSubtitle(ctx, cue.text, { ...subStyle, color: speakerColor(subStyle.color, cue.speaker) }, w, h, family)
            if (!camera) renderer.setOverlay(layer)
          }
        }
        const i = frameAt(times, sample.timestamp)
        const faces = []
        for (const t of tracks) {
          const b = trackBox(t, i)
          if (b) faces.push(ellipseFor(b, rw, rh, look.padding * (t.scale ?? 1)))
        }
        const m = pic?.masks
        if (m && pic?.background?.key === 'person') {
          const k = maskIndex(m, i)
          renderer.setMask(m.w, m.h, k >= 0 ? maskAt(m, k) : null, k)
        }
        let frame: VideoFrame | null = null
        try {
          frame = sample.toVideoFrame()
          renderer.draw(frame, rw, rh, faces, look)
        } catch {
          // Some browsers can't upload a VideoFrame to WebGL: go through a 2D canvas.
          scratch ??= new OffscreenCanvas(rw, rh)
          const ctx = scratch.getContext('2d')!
          sample.draw(ctx, 0, 0, rw, rh)
          renderer.draw(scratch, rw, rh, faces, look)
        } finally {
          frame?.close()
        }
        let result: OffscreenCanvas = canvas
        if (out && octx) {
          // The steadied view, and in it the frame that follows the faces; then the watermark and the subtitles.
          const st = pic?.steady
          const k = Math.min(i, (st?.sx.length ?? 1) - 1)
          const p = pic?.path
          const q = Math.min(i, (p?.x.length ?? 1) - 1)
          const [x, y, vw, vh] = viewRect(rw, rh, crop, p ? [p.x[q], p.y[q]] : [0.5, 0.5], st ? st.sx[k] : 0, st ? st.sy[k] : 0, st?.zoom ?? 1)
          octx.drawImage(canvas, x, y, vw, vh, 0, 0, w, h)
          if (layer) octx.drawImage(layer, 0, 0)
          else if (overlay) octx.drawImage(overlay, 0, 0, w, h)
          result = out
        }
        if (cuts.empty) return result
        const ts = sample.timestamp, d = sample.duration
        return new VideoSample(result, { timestamp: cuts.out(ts), duration: cuts.kept(ts, ts + d) || d })
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
