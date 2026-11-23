// What the export makes, as the app's export options: a video (its resolution, frame rate,
// quality and codec) or only its sound as MP3, and the subtitles as an SRT file too.

export type ExportFormat = 'mp4' | 'mp3'
export type ExportQuality = 'original' | 'high' | 'saver'
export type ExportCodec = 'same' | 'avc' | 'hevc'

export type ExportOptions = {
  format: ExportFormat
  /** The short side in pixels (0: the source's). */
  shortSide: number
  /** Frames per second (0: the source's). */
  fps: number
  quality: ExportQuality
  codec: ExportCodec
  /** Save the subtitles as an SRT file too. */
  srt: boolean
}

export const DEFAULT_EXPORT: ExportOptions = { format: 'mp4', shortSide: 0, fps: 0, quality: 'high', codec: 'same', srt: false }

export function loadExportOptions(): ExportOptions {
  try {
    const o = JSON.parse(localStorage.getItem('onit.export') ?? 'null')
    if (o && typeof o === 'object') return { ...DEFAULT_EXPORT, ...o, format: 'mp4' }
  } catch {}
  return DEFAULT_EXPORT
}

export function saveExportOptions(o: ExportOptions) {
  try { localStorage.setItem('onit.export', JSON.stringify(o)) } catch {}
}

/** Smaller sizes offered for a source (the short side, below the source's). */
export const resolutionChoices = (w: number, h: number) => [2160, 1440, 1080, 720, 480].filter((s) => s < Math.min(w, h) - 8)

/** Lower frame rates offered for a source. */
export const fpsChoices = (fps: number) => [60, 50, 30, 25, 24].filter((f) => f < fps - 0.5)

const even = (v: number) => Math.max(2, Math.round(v / 2) * 2)

/** The exported picture's size (even). */
export function outputSize(w: number, h: number, shortSide: number): [number, number] {
  const short = Math.min(w, h)
  if (shortSide <= 0 || shortSide >= short || short <= 0) return [even(w), even(h)]
  const s = shortSide / short
  return [even(w * s), even(h * s)]
}

export const outputFps = (fps: number, wanted: number) => (wanted <= 0 || wanted >= fps - 0.5 ? fps : wanted)

/**
 * The video bitrate to ask for (bits per second), as in the app: about YouTube's recommended
 * rates (1080p30 ≈ 8 Mbps), never more than the source's scaled to the new size.
 */
export function videoBitrate(o: ExportOptions, srcW: number, srcH: number, srcFps: number, srcBitrate: number, hevc: boolean): number {
  const [w, h] = outputSize(srcW, srcH, o.shortSide)
  const f = outputFps(srcFps, o.fps)
  const recommended = w * h * 30 * 0.13 * Math.pow(f / 30, 0.6) * (hevc ? 0.65 : 1)
  const scaled = srcBitrate * ((w * h) / Math.max(1, srcW * srcH)) * Math.pow(f / Math.max(1, srcFps), 0.6)
  const b = o.quality === 'original'
    ? (srcBitrate > 0 ? scaled : recommended * 1.4)
    : (srcBitrate > 0 ? Math.min(recommended, scaled) : recommended) * (o.quality === 'saver' ? 0.5 : 1)
  return Math.round(Math.min(150e6, Math.max(500e3, b)))
}
