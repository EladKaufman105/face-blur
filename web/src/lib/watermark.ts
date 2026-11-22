// A logo or a line of text on the video, drawn as the Android app draws it.

export type WatermarkPosition = 'tl' | 'tc' | 'tr' | 'cl' | 'c' | 'cr' | 'bl' | 'bc' | 'br'
export type WatermarkLook = 'plain' | 'shadow' | 'outline' | 'box'

export interface Watermark {
  text: string
  /** A picked logo (object URL), used instead of the text. */
  image: string | null
  position: WatermarkPosition
  /** Multiplier of the base size (logo: 10% of the short side high; text: 4%). */
  size: number
  opacity: number
  color: string
  look: WatermarkLook
  weight: number
}

export const NO_WATERMARK: Watermark = { text: '', image: null, position: 'br', size: 1, opacity: 0.8, color: '#ffffff', look: 'shadow', weight: 700 }

export const POSITIONS: WatermarkPosition[] = ['tl', 'tc', 'tr', 'cl', 'c', 'cr', 'bl', 'bc', 'br']

export function watermarkOn(w: Watermark) {
  return w.image !== null || w.text.trim() !== ''
}

/** Left (or top) edge of something [len] long along a side of [total], in grid cell [cell]. */
function place(cell: number, len: number, total: number, margin: number) {
  return cell === 0 ? margin : cell === 1 ? (total - len) / 2 : total - margin - len
}

function luminance(hex: string) {
  const n = parseInt(hex.slice(1), 16)
  const f = (c: number) => { const v = c / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4 }
  return 0.2126 * f(n >> 16 & 255) + 0.7152 * f(n >> 8 & 255) + 0.0722 * f(n & 255)
}

/** Draws [w] on a [width] x [height] canvas ([logo]: the decoded image, if any). */
export function drawWatermark(ctx: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D, w: Watermark, width: number, height: number, logo: ImageBitmap | HTMLImageElement | null) {
  const short = Math.min(width, height)
  const margin = short * 0.035
  const i = POSITIONS.indexOf(w.position)
  const col = i % 3, row = Math.floor(i / 3)
  ctx.save()
  ctx.globalAlpha = w.opacity
  if (w.image && logo) {
    let lh = short * 0.1 * w.size
    let lw = lh * logo.width / logo.height
    if (lw > width * 0.6) { lw = width * 0.6; lh = lw * logo.height / logo.width }
    ctx.drawImage(logo, place(col, lw, width, margin), place(row, lh, height, margin), lw, lh)
    ctx.restore()
    return
  }
  const text = w.text.trim()
  if (!text) { ctx.restore(); return }
  const fontSize = short * 0.04 * w.size
  ctx.font = `${w.weight} ${fontSize}px 'Allo Hebrew', system-ui, sans-serif`
  ctx.textBaseline = 'alphabetic'
  ctx.direction = /[֐-ۿ]/.test(text) ? 'rtl' : 'ltr'
  ctx.textAlign = 'left'
  const m = ctx.measureText(text)
  const tw = m.width
  const asc = m.fontBoundingBoxAscent ?? fontSize * 0.8, desc = m.fontBoundingBoxDescent ?? fontSize * 0.25
  const th = asc + desc
  const pad = w.look === 'box' ? fontSize * 0.35 : 0
  const x = place(col, tw + 2 * pad, width, margin) + pad
  const top = place(row, th + 2 * pad, height, margin) + pad
  const baseline = top + asc
  // The text is laid out from its left edge whatever its direction.
  const drawAt = ctx.direction === 'rtl' ? x + tw : x
  ctx.textAlign = ctx.direction === 'rtl' ? 'right' : 'left'
  const contrast = luminance(w.color) > 0.45 ? '#000000' : '#ffffff'
  if (w.look === 'shadow') {
    ctx.shadowColor = `rgba(0,0,0,${Math.min(1, w.opacity * 0.8)})`
    ctx.shadowBlur = fontSize * 0.24
    ctx.shadowOffsetY = fontSize * 0.04
  } else if (w.look === 'outline') {
    ctx.lineJoin = 'round'
    ctx.lineWidth = fontSize * 0.14
    ctx.strokeStyle = contrast
    ctx.strokeText(text, drawAt, baseline)
  } else if (w.look === 'box') {
    ctx.save()
    ctx.globalAlpha = w.opacity * 0.6
    ctx.fillStyle = contrast
    const r = (th + 2 * pad) * 0.3
    ctx.beginPath()
    ctx.roundRect(x - pad, top - pad, tw + 2 * pad, th + 2 * pad, r)
    ctx.fill()
    ctx.restore()
  }
  ctx.fillStyle = w.color
  ctx.fillText(text, drawAt, baseline)
  ctx.restore()
}

/** The watermark alone on a transparent frame-sized picture (for the export). */
export async function watermarkImage(w: Watermark, width: number, height: number): Promise<ImageBitmap | null> {
  if (!watermarkOn(w)) return null
  await document.fonts.ready
  const logo = w.image ? await createImageBitmap(await (await fetch(w.image)).blob()) : null
  const c = new OffscreenCanvas(width, height)
  drawWatermark(c.getContext('2d')!, w, width, height, logo)
  return c.transferToImageBitmap()
}
