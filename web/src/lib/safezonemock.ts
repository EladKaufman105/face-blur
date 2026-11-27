// A generic, made-up short-video app drawn over the frame to show what a platform's own screens will
// cover: the app's SafeZoneMock.kt. It borrows no platform's logo, name or artwork: white shapes with a
// soft shadow, placed from the platform's margins. Preview only, never burned into the video.
import { t } from '../i18n'
import type { Platform, Zone } from './safezone'

/** Plain Material glyph outlines (24x24, Apache 2.0). Generic shapes only: no platform's own artwork. */
const G = {
  NOTE: 'M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z',
  HEART: 'M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z',
  COMMENT: 'M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z',
  ARROW: 'M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z',
  BOOKMARK: 'M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z',
  PLANE: 'M2.01 21L23 12 2.01 3 2 10l15 2-15 2z',
  THUMB: 'M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z',
  DOTS: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
  SEARCH: 'M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
}

/** The most the caption gives way to the button column (as a fraction of the width). The column itself is centred in the zone's right margin. */
const RAIL_MAX = 0.19

const paths = new Map<string, Path2D>()
const path = (d: string) => { let p = paths.get(d); if (!p) { p = new Path2D(d); paths.set(d, p) } return p }

const INK = 'rgba(255,255,255,0.92)'
const SOFT = 'rgba(255,255,255,0.55)'
const SHADOW = 'rgba(0,0,0,0.38)'

type C = CanvasRenderingContext2D

function glyph(c: C, d: string, cx: number, cy: number, size: number, color = INK, flip = false, turn = 0) {
  const p = path(d)
  const k = size / 24
  for (let pass = 0; pass < 2; pass++) {
    const shadow = pass === 0
    c.save()
    c.translate(cx - size / 2 + (shadow ? size * 0.04 : 0), cy - size / 2 + (shadow ? size * 0.06 : 0))
    if (flip) { c.translate(size, 0); c.scale(-k, k) } else c.scale(k, k)
    if (turn !== 0) { c.translate(12, 12); c.rotate((turn * Math.PI) / 180); c.translate(-12, -12) }
    c.fillStyle = shadow ? SHADOW : color
    c.fill(p)
    c.restore()
  }
}

function pill(c: C, x: number, y: number, w: number, h: number, r: number, fill: string) {
  c.beginPath()
  c.roundRect(x, y, w, h, r)
  c.fillStyle = fill
  c.fill()
}

function bar(c: C, x: number, y: number, w: number, h: number, color = SOFT) {
  pill(c, x + h * 0.1, y + h * 0.25, w, h, h / 2, SHADOW)
  pill(c, x, y, w, h, h / 2, color)
}

function dot(c: C, cx: number, cy: number, r: number, color = INK) {
  c.beginPath(); c.arc(cx + r * 0.06, cy + r * 0.12, r, 0, Math.PI * 2); c.fillStyle = SHADOW; c.fill()
  c.beginPath(); c.arc(cx, cy, r, 0, Math.PI * 2); c.fillStyle = color; c.fill()
}

function font(c: C, size: number, bold: boolean) { c.font = `${bold ? 700 : 400} ${size}px system-ui, sans-serif` }

/** Set while a mirrored (right-to-left app) mock is drawn: the whole canvas is flipped, and text is flipped back so that it still reads. */
let flipped = false

/** Fills [s] at [x] (left edge, in the mock's own layout), reading the right way round also when the canvas is flipped. */
function fillText(c: C, s: string, x: number, baseline: number) {
  if (!flipped) { c.fillText(s, x, baseline); return }
  c.save()
  c.translate(x + c.measureText(s).width, 0)
  c.scale(-1, 1)
  c.fillText(s, 0, baseline)
  c.restore()
}

function label(c: C, s: string, x: number, baseline: number, size: number, bold: boolean, alpha: number) {
  font(c, size, bold)
  c.textAlign = 'left'
  c.textBaseline = 'alphabetic'
  c.direction = 'ltr'
  c.fillStyle = `rgba(255,255,255,${alpha})`
  c.shadowColor = 'rgba(0,0,0,0.53)'
  c.shadowBlur = size * 0.18
  c.shadowOffsetY = size * 0.06
  fillText(c, s, x, baseline)
  c.shadowColor = 'transparent'; c.shadowBlur = 0; c.shadowOffsetY = 0
}

function measure(c: C, s: string, size: number, bold: boolean) { font(c, size, bold); c.direction = 'ltr'; return c.measureText(s).width }

/**
 * The platform's own interface, imitated, over a frame at ([x], [y]) of [w] x [h], laid out by the
 * platform's [zone]: a tab strip at the top, a column of buttons at the right, the account, caption
 * and sound lines at the bottom and a thin progress bar. A soft shade behind the top and bottom
 * parts and a thin dashed outline around the clear middle help the eye. [mirrored]: the app in a right-to-left
 * language (Arabic, Hebrew): the whole picture is flipped, so the column of buttons is at the left (the text stays readable).
 */
export function drawSafeZone(c: C, platform: Platform, zone: Zone, x: number, y: number, w: number, h: number, reserved = 0, mirrored = false) {
  const dp = Math.max(1, w / 360)
  const topH = h * zone.top, botH = h * zone.bottom
  const leftW = w * zone.left, rightW = w * zone.right
  // The button column is centred in the platform's right margin (mirrored: the left one, the canvas is flipped), and its caption stops short of it.
  const colX = w - rightW / 2
  const railW = Math.min(rightW, w * RAIL_MAX)
  c.save()
  c.beginPath(); c.rect(x, y, w, h); c.clip()
  c.translate(x, y)
  flipped = mirrored
  if (mirrored) { c.translate(w, 0); c.scale(-1, 1) }
  // Light shade where the platform's screens sit, so white shapes read on any video.
  let g = c.createLinearGradient(0, 0, 0, Math.max(1, topH))
  g.addColorStop(0, 'rgba(0,0,0,0.34)'); g.addColorStop(1, 'rgba(0,0,0,0)')
  c.fillStyle = g; c.fillRect(0, 0, w, topH)
  g = c.createLinearGradient(0, h - botH, 0, h)
  g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(1, 'rgba(0,0,0,0.42)')
  c.fillStyle = g; c.fillRect(0, h - botH, w, botH)

  // Top: the tab strip (and, per platform, search / menu).
  const tabSize = w * 0.04
  // Below the platform chooser's floating pill ([reserved] px from the frame's top), which it must not sit under.
  const tabY = Math.max(topH * 0.62, reserved + tabSize * 1.15)
  const a = t('עוקבים'), b = t('בשבילך')
  const gap = w * 0.05
  const wa = measure(c, a, tabSize, false), wb = measure(c, b, tabSize, true)
  const sx = (w - (wa + wb + gap)) / 2
  label(c, a, sx, tabY, tabSize, false, 0.62)
  label(c, b, sx + wa + gap, tabY, tabSize, true, 0.95)
  bar(c, sx + wa + gap, tabY + tabSize * 0.35, wb, Math.max(2 * dp, tabSize * 0.1), INK)
  const topIcon = Math.min(w * 0.06, topH * 0.36)
  if (platform === 'tiktok') glyph(c, G.SEARCH, colX - topIcon * 0.1, tabY - tabSize * 0.35, topIcon)
  else if (platform === 'shorts') {
    glyph(c, G.SEARCH, colX - topIcon * 1.15, tabY - tabSize * 0.35, topIcon)
    glyph(c, G.DOTS, colX + topIcon * 0.1, tabY - tabSize * 0.35, topIcon)
  }

  // Right: the column of buttons, ending with the sound disc / thumbnail low in the bottom zone.
  const icon = Math.min(w * 0.07, Math.max(w * 0.045, rightW * 0.5))
  const cx = colX
  const step = icon * 2.05
  const last = h - botH * 0.52
  const items = platform === 'tiktok' ? ['avatar', 'heart', 'comment', 'bookmark', 'share', 'disc']
    : platform === 'reels' ? ['heart', 'comment', 'plane', 'dots', 'square']
    : ['like', 'dislike', 'comment', 'share', 'dots', 'square']
  const withBar = (d: string, cy: number, flip = false, turn = 0) => {
    glyph(c, d, cx, cy - icon * 0.2, icon, INK, flip, turn)
    bar(c, cx - icon * 0.3, cy + icon * 0.5, icon * 0.6, icon * 0.16)
  }
  items.forEach((kind, i) => {
    const cy = last - (items.length - 1 - i) * step
    switch (kind) {
      case 'avatar': {
        dot(c, cx, cy, icon * 0.62, SOFT)
        dot(c, cx, cy - icon * 0.12, icon * 0.2)
        // The shoulders: the upper half of an oval, closed through its centre.
        const ox = cx, oy = cy + icon * 0.12 + icon * 0.25
        c.beginPath(); c.moveTo(ox, oy); c.ellipse(ox, oy, icon * 0.38, icon * 0.25, 0, Math.PI, Math.PI * 2); c.closePath(); c.fillStyle = INK; c.fill()
        const by = cy + icon * 0.66
        dot(c, cx, by, icon * 0.22, '#fff')
        c.strokeStyle = 'rgba(0,0,0,0.55)'; c.lineWidth = icon * 0.05
        c.beginPath(); c.moveTo(cx - icon * 0.11, by); c.lineTo(cx + icon * 0.11, by); c.moveTo(cx, by - icon * 0.11); c.lineTo(cx, by + icon * 0.11); c.stroke()
        break
      }
      case 'heart': withBar(G.HEART, cy); break
      case 'like': withBar(G.THUMB, cy); break
      case 'dislike': withBar(G.THUMB, cy, false, 180); break
      case 'comment': withBar(G.COMMENT, cy); break
      case 'bookmark': withBar(G.BOOKMARK, cy); break
      case 'share': withBar(G.ARROW, cy, true); break
      case 'plane': glyph(c, G.PLANE, cx, cy, icon); break
      case 'dots': glyph(c, G.DOTS, cx, cy, icon); break
      case 'disc':
        dot(c, cx, cy, icon * 0.62, 'rgba(255,255,255,0.35)')
        dot(c, cx, cy, icon * 0.42, 'rgba(0,0,0,0.55)')
        glyph(c, G.NOTE, cx, cy, icon * 0.5)
        break
      default: {
        const s = icon * 0.95
        pill(c, cx - s / 2 + 2, cy - s / 2 + 3, s, s, s * 0.2, SHADOW)
        pill(c, cx - s / 2, cy - s / 2, s, s, s * 0.2, 'rgba(255,255,255,0.4)')
        c.beginPath(); c.roundRect(cx - s / 2, cy - s / 2, s, s, s * 0.2); c.strokeStyle = INK; c.lineWidth = Math.max(1.5, s * 0.07); c.stroke()
        glyph(c, G.NOTE, cx, cy, s * 0.55)
      }
    }
  })

  // Bottom: account, caption (two lines), sound line, then the progress bar.
  const x0 = Math.max(leftW * 0.5, w * 0.03)
  const textSize = w * 0.036
  const lineH = botH * 0.14
  const progH = Math.max(2 * dp, h * 0.0035)
  const soundY = h - botH * 0.3
  const cap2 = soundY - lineH * 1.35
  const cap1 = cap2 - lineH * 0.95
  const userY = cap1 - lineH * 1.15
  let ux = x0
  if (platform !== 'tiktok') {
    const ar = textSize * 0.9
    dot(c, ux + ar, userY - textSize * 0.3, ar, SOFT)
    dot(c, ux + ar, userY - textSize * 0.55, ar * 0.32)
    ux += ar * 2 + textSize * 0.5
  }
  const user = '@username'
  label(c, user, ux, userY, textSize, true, 0.96)
  const pillW0 = measure(c, user, textSize, true)
  if (platform !== 'tiktok') {
    const txt = platform === 'reels' ? t('עקוב') : t('הרשמה')
    const ps = textSize * 0.8
    const tw = measure(c, txt, ps, true)
    const px = ux + pillW0 + textSize * 0.7
    const ph = ps * 1.9
    const py = userY - textSize * 0.35 - ph / 2
    if (platform === 'shorts') {
      pill(c, px, py, tw + ps * 1.6, ph, ph / 2, 'rgba(255,255,255,0.92)')
      font(c, ps, true); c.textAlign = 'left'; c.fillStyle = '#202020'; c.shadowColor = 'transparent'
      fillText(c, txt, px + ps * 0.8, py + ph / 2 + ps * 0.35)
    } else {
      c.beginPath(); c.roundRect(px, py, tw + ps * 1.6, ph, ph / 2); c.strokeStyle = INK; c.lineWidth = Math.max(1.5, ps * 0.1); c.stroke()
      label(c, txt, px + ps * 0.8, py + ph / 2 + ps * 0.35, ps, true, 0.95)
    }
  }
  const barH = textSize * 0.42
  const capW = w - x0 - railW - w * 0.03
  bar(c, x0, cap1 - barH, capW * 0.9, barH, 'rgba(255,255,255,0.7)')
  bar(c, x0, cap2 - barH, capW * 0.55, barH, 'rgba(255,255,255,0.7)')
  glyph(c, G.NOTE, x0 + textSize * 0.55, soundY - textSize * 0.3, textSize * 1.1)
  label(c, t('סאונד מקורי') + ' · @username', x0 + textSize * 1.4, soundY, textSize * 0.85, false, 0.9)
  c.fillStyle = 'rgba(255,255,255,0.3)'; c.fillRect(0, h - progH, w, progH)
  c.fillStyle = 'rgba(255,255,255,0.9)'; c.fillRect(0, h - progH, w * 0.35, progH)

  // The clear middle.
  c.strokeStyle = 'rgba(255,255,255,0.55)'
  c.lineWidth = dp
  c.setLineDash([6 * dp, 4 * dp])
  c.strokeRect(leftW, topH, w - leftW - rightW, h - topH - botH)
  c.setLineDash([])
  c.restore()
  flipped = false
}
