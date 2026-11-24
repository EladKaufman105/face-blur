// What an export is made of, and the request the export worker takes for it. Shared by the
// export screen and the batch (which exports with no screen).
import type { Look, Track } from './types'
import type { ExportPicture, ExportRequest } from '../workers/export.worker'
import { sfxSamples, type SfxClip } from './sfx'
import { watermarkImage, type Watermark } from './watermark'
import type { Cue, SubStyle, Word } from './subtitles'
import { getFont } from './projects'
import type { Range } from './cutmap'
import { DEFAULT_EXPORT, outputSize, type ExportOptions } from './exportopts'

export type ExportJob = { file: File; times: Float64Array; tracks: Track[]; look: Look; width: number; height: number; sfx: SfxClip[]; watermark: Watermark; cues: Cue[]; subStyle: SubStyle; words?: Word[]; voice: { rate: number; data: Float32Array } | null; cuts: Range[]; options?: ExportOptions; standalone?: boolean; picture?: Omit<ExportPicture, 'image'>; /** The second of two steps (found and saved straight away). */ twoSteps?: boolean }

/**
 * The export worker's request for [job], and what to transfer with it. The sounds' samples and
 * the watermark's picture are made here (the page has the decoder and the font); track boxes
 * are copied, not transferred: the editor keeps its own.
 */
export async function exportRequest(job: ExportJob): Promise<{ req: ExportRequest; transfer: Transferable[] }> {
  const sfx = await sfxSamples(job.sfx)
  // The watermark at the result's size (its frame and resolution).
  const o = job.options ?? DEFAULT_EXPORT
  const [ow, oh] = outputSize(job.width, job.height, o.shortSide, o.aspect)
  const overlay = await watermarkImage(job.watermark, ow, oh)
  const bg = job.picture?.background
  const image = bg?.fill === 'image' && bg.image ? await createImageBitmap(await (await fetch(bg.image)).blob()) : null
  const customFont = job.subStyle.customFont ? await getFont(job.subStyle.customFont) : null
  const picture: ExportPicture | undefined = job.picture ? { ...job.picture, image } : undefined
  const req: ExportRequest = { file: job.file, times: job.times, tracks: job.tracks, look: job.look, sfx, overlay, cues: job.options?.burn === false ? [] : job.cues, subStyle: job.subStyle, voice: job.voice, cuts: job.cuts, options: job.options, picture, base: new URL('.', document.baseURI).href, words: job.words ?? [], customFont }
  const transfer: Transferable[] = []
  if (overlay) transfer.push(overlay)
  if (image) transfer.push(image)
  return { req, transfer }
}
