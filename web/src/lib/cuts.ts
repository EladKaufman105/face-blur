// Hard-cut detection, as in the Android app: a 32x18 thumbnail that changes a lot and a
// brightness histogram that no longer correlates.
// The thresholds: shared/detect/detect.json, shared with the app's CutDetector (generated).
import { CUT_CORR, CUT_DIFF, CUT_DIFF_HARD } from '../gen/detect.ts'

export class CutDetector {
  private small = new OffscreenCanvas(64, 36)
  private prevThumb: Float32Array | null = null
  private prevHist: Float32Array | null = null

  isCut(frame: CanvasImageSource): boolean {
    const ctx = this.small.getContext('2d', { willReadFrequently: true })!
    ctx.drawImage(frame, 0, 0, 64, 36)
    const px = ctx.getImageData(0, 0, 64, 36).data
    const gray = new Float32Array(64 * 36)
    for (let i = 0; i < gray.length; i++) gray[i] = 0.299 * px[i * 4] + 0.587 * px[i * 4 + 1] + 0.114 * px[i * 4 + 2]
    const thumb = new Float32Array(32 * 18)
    for (let y = 0; y < 18; y++) for (let x = 0; x < 32; x++) {
      const a = (2 * y) * 64 + 2 * x
      thumb[y * 32 + x] = (gray[a] + gray[a + 1] + gray[a + 64] + gray[a + 65]) / 4
    }
    const hist = new Float32Array(32)
    for (const g of gray) hist[Math.min(31, g >> 3)] += 1 / gray.length
    const pt = this.prevThumb, ph = this.prevHist
    this.prevThumb = thumb
    this.prevHist = hist
    if (!pt || !ph) return false
    let diff = 0
    for (let i = 0; i < thumb.length; i++) diff += Math.abs(thumb[i] - pt[i])
    diff /= thumb.length
    const corr = correlation(hist, ph)
    return (diff > CUT_DIFF && corr < CUT_CORR) || diff > CUT_DIFF_HARD
  }
}

function correlation(a: Float32Array, b: Float32Array): number {
  const ma = a.reduce((s, v) => s + v, 0) / a.length
  const mb = b.reduce((s, v) => s + v, 0) / b.length
  let num = 0, da = 0, db = 0
  for (let i = 0; i < a.length; i++) {
    const x = a[i] - ma, y = b[i] - mb
    num += x * y; da += x * x; db += y * y
  }
  const den = Math.sqrt(da * db)
  return den < 1e-12 ? 1 : num / den
}
