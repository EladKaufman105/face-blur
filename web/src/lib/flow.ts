// Pyramidal Lucas-Kanade optical flow on small grayscale frames, as OpenCV's calcOpticalFlowPyrLK
// in the Android app: for following a hand-placed blur and for measuring how the camera moves.

/** A grayscale image, values 0..255. */
export interface Gray {
  w: number
  h: number
  d: Float32Array
}

/** Gray from RGBA pixels (Rec. 601 weights). */
export function toGray(px: Uint8ClampedArray, w: number, h: number): Gray {
  const d = new Float32Array(w * h)
  for (let i = 0; i < d.length; i++) d[i] = 0.299 * px[i * 4] + 0.587 * px[i * 4 + 1] + 0.114 * px[i * 4 + 2]
  return { w, h, d }
}

/** Gray from 8-bit gray. */
export function fromBytes(b: Uint8Array, w: number, h: number): Gray {
  const d = new Float32Array(w * h)
  for (let i = 0; i < d.length; i++) d[i] = b[i]
  return { w, h, d }
}

/** The image and [levels] - 1 halvings of it (each a 2x2 average). */
export function pyramid(g: Gray, levels = 3): Gray[] {
  const out = [g]
  for (let l = 1; l < levels; l++) {
    const p = out[l - 1]
    const w = p.w >> 1, h = p.h >> 1
    if (w < 16 || h < 16) break
    const d = new Float32Array(w * h)
    for (let y = 0; y < h; y++) {
      const r0 = 2 * y * p.w, r1 = r0 + p.w
      for (let x = 0; x < w; x++) {
        const a = r0 + 2 * x, b = r1 + 2 * x
        d[y * w + x] = (p.d[a] + p.d[a + 1] + p.d[b] + p.d[b + 1]) * 0.25
      }
    }
    out.push({ w, h, d })
  }
  return out
}

function at(g: Gray, x: number, y: number): number {
  if (x < 0) x = 0
  else if (x > g.w - 1.001) x = g.w - 1.001
  if (y < 0) y = 0
  else if (y > g.h - 1.001) y = g.h - 1.001
  const x0 = x | 0, y0 = y | 0
  const fx = x - x0, fy = y - y0
  const i = y0 * g.w + x0
  const a = g.d[i], b = g.d[i + 1], c = g.d[i + g.w], e = g.d[i + g.w + 1]
  return a + (b - a) * fx + (c - a) * fy + (a - b - c + e) * fx * fy
}

/**
 * Where each point (x, y pairs, level-0 pixels) of [prev] went in [cur].
 * [half]: half the window (7 → 15x15). Points that can't be followed get ok = 0.
 */
export function lk(prev: Gray[], cur: Gray[], pts: Float32Array, half = 7, iters = 12): { out: Float32Array; ok: Uint8Array } {
  const n = pts.length / 2
  const out = new Float32Array(pts.length)
  const ok = new Uint8Array(n)
  const levels = Math.min(prev.length, cur.length)
  const side = 2 * half + 1
  const ix = new Float32Array(side * side)
  const iy = new Float32Array(side * side)
  const iv = new Float32Array(side * side)
  for (let k = 0; k < n; k++) {
    let gx = 0, gy = 0
    let good = true
    for (let l = levels - 1; l >= 0; l--) {
      const s = 1 / (1 << l)
      const P = prev[l], C = cur[l]
      const px = pts[2 * k] * s, py = pts[2 * k + 1] * s
      // The template's gradients, once per level.
      let a11 = 0, a12 = 0, a22 = 0
      let m = 0
      for (let dy = -half; dy <= half; dy++) {
        for (let dx = -half; dx <= half; dx++) {
          const x = px + dx, y = py + dy
          const v = at(P, x, y)
          const gxv = (at(P, x + 1, y) - at(P, x - 1, y)) * 0.5
          const gyv = (at(P, x, y + 1) - at(P, x, y - 1)) * 0.5
          ix[m] = gxv; iy[m] = gyv; iv[m] = v
          a11 += gxv * gxv; a12 += gxv * gyv; a22 += gyv * gyv
          m++
        }
      }
      const det = a11 * a22 - a12 * a12
      // A flat or one-directional patch can't be followed.
      const minEig = (a11 + a22 - Math.sqrt((a11 - a22) * (a11 - a22) + 4 * a12 * a12)) / 2 / (side * side)
      if (det < 1e-6 || minEig < 1e-3) { good = false; break }
      let vx = 0, vy = 0
      for (let it = 0; it < iters; it++) {
        let b1 = 0, b2 = 0
        m = 0
        for (let dy = -half; dy <= half; dy++) {
          for (let dx = -half; dx <= half; dx++) {
            const diff = iv[m] - at(C, px + dx + gx + vx, py + dy + gy + vy)
            b1 += diff * ix[m]; b2 += diff * iy[m]
            m++
          }
        }
        const ex = (a22 * b1 - a12 * b2) / det
        const ey = (a11 * b2 - a12 * b1) / det
        vx += ex; vy += ey
        if (ex * ex + ey * ey < 0.0004) break
      }
      if (l > 0) { gx = 2 * (gx + vx); gy = 2 * (gy + vy) }
      else { gx += vx; gy += vy }
    }
    const x = pts[2 * k] + gx, y = pts[2 * k + 1] + gy
    out[2 * k] = x
    out[2 * k + 1] = y
    ok[k] = good && x >= 0 && y >= 0 && x < prev[0].w && y < prev[0].h ? 1 : 0
  }
  return { out, ok }
}

/** Followed both ways: points whose way back lands within a pixel of where they began. */
export function lkChecked(prev: Gray[], cur: Gray[], pts: Float32Array): { out: Float32Array; ok: Uint8Array } {
  const fwd = lk(prev, cur, pts)
  const back = lk(cur, prev, fwd.out)
  const n = pts.length / 2
  for (let k = 0; k < n; k++) {
    if (!fwd.ok[k] || !back.ok[k]) { fwd.ok[k] = 0; continue }
    const dx = back.out[2 * k] - pts[2 * k], dy = back.out[2 * k + 1] - pts[2 * k + 1]
    if (dx * dx + dy * dy >= 1) fwd.ok[k] = 0
  }
  return fwd
}

export function median(v: number[]): number {
  if (v.length === 0) return 0
  const s = [...v].sort((a, b) => a - b)
  const n = s.length
  return n % 2 ? s[n >> 1] : (s[n / 2 - 1] + s[n / 2]) / 2
}
