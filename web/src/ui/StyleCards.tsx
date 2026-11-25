// The subtitle style's effect and font choices as small cards, as the app's style panel: each effect
// card plays its effect on a sample line (drawn by the same renderer as the export), each font card
// shows a sample in that font. Light: a card draws and loads its font only once it is near the
// screen, and an effect plays (about 30 pictures a second) only while its card is in view.
import { useEffect, useRef, useState } from 'preact/hooks'
import { t } from '../i18n'
import { drawCue } from '../lib/subrender'
import { getFont } from '../lib/projects'
import { DEFAULT_SUB_STYLE, fontFamily, fontWeight, loadSubFont, type FontChoice, type SubEffect, type SubStyle } from '../lib/substyle'
import { sampleText } from './SubPresets'

const motion = (() => { try { return matchMedia('(prefers-reduced-motion: reduce)') } catch { return null } })()
/** Less motion asked for: the effect cards stay still (the sample as it stands once every word is said). */
export const reducedMotion = () => !!motion?.matches

/** One loop for every playing card: they draw together, at most every 33 ms (30 pictures a second). */
const playing = new Set<(now: number) => void>()
let raf = 0, lastTick = 0
function loop(now: number) {
  raf = 0
  if (!playing.size) return
  if (now - lastTick >= 33) { lastTick = now; playing.forEach((f) => f(now)) }
  raf = requestAnimationFrame(loop)
}
function play(f: (now: number) => void): () => void {
  playing.add(f)
  if (!raf) raf = requestAnimationFrame(loop)
  return () => { playing.delete(f) }
}

/** Whether [el] is on screen (or near it: [margin] px); with no IntersectionObserver, always. */
export function useNear<T extends Element>(margin = 120): [(e: T | null) => void, boolean] {
  const [el, setEl] = useState<T | null>(null)
  const [near, setNear] = useState(typeof IntersectionObserver === 'undefined')
  useEffect(() => {
    if (!el || typeof IntersectionObserver === 'undefined') return
    const io = new IntersectionObserver((es) => setNear(es.some((e) => e.isIntersecting)), { rootMargin: margin + 'px' })
    io.observe(el)
    return () => io.disconnect()
  }, [el, margin])
  return [setEl, near]
}

const CARD_W = 240, CARD_H = 150
/** The sample's words come this far apart (seconds), and the cue is up this long. */
const GAP = 0.45
const length = (words: number) => words * GAP + 1.2

/** The font [style] draws with, loaded (an imported one from its bytes in the browser's storage). */
export async function loadStyleFont(c: FontChoice): Promise<boolean> {
  const bytes = c.customFont ? await getFont(c.customFont).catch(() => null) : null
  return loadSubFont({ ...DEFAULT_SUB_STYLE, ...c }, document.fonts, document.baseURI, bytes)
}

function EffectCard({ style, effect, label, on, onPick }: { style: SubStyle; effect: SubEffect; label: string; on: boolean; onPick: () => void }) {
  const [ref, near] = useNear<HTMLButtonElement>()
  // In view (not only near): the loop runs only for what is seen.
  const [seen, setSeen] = useState(false)
  const [fontReady, setFontReady] = useState(0)
  const [moving, setMoving] = useState(false)
  const btn = useRef<HTMLButtonElement | null>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const text = sampleText()
  const s: SubStyle = { ...style, effect, size: Math.min(1.6, Math.max(0.8, style.size)) * 3.1, position: 0.5 }
  const key = JSON.stringify([s, text])
  useEffect(() => {
    const e = btn.current
    if (!e || typeof IntersectionObserver === 'undefined') { setSeen(true); return }
    const io = new IntersectionObserver((es) => setSeen(es.some((x) => x.isIntersecting)))
    io.observe(e)
    return () => io.disconnect()
  }, [])
  useEffect(() => {
    if (!near) return
    let live = true
    loadStyleFont(style).then(() => { if (live) setFontReady((n) => n + 1) })
    return () => { live = false }
  }, [near, style.font, style.customFont])
  useEffect(() => {
    const c = canvas.current
    if (!c || !near) return
    c.width = CARD_W
    c.height = CARD_H
    const g = c.getContext('2d')!
    const words = text.split(' ')
    const times = words.map((_, i) => i * GAP)
    const len = length(words.length)
    const at = (tm: number) => {
      g.clearRect(0, 0, CARD_W, CARD_H)
      const bg = g.createLinearGradient(0, 0, CARD_W, CARD_H)
      bg.addColorStop(0, '#3a4256'); bg.addColorStop(1, '#1c2030')
      g.fillStyle = bg
      g.fillRect(0, 0, CARD_W, CARD_H)
      drawCue(g, [text], 0, len, tm, s, CARD_W, CARD_H, [times])
    }
    // At rest: every word said.
    const rest = words.length * GAP
    at(rest)
    // One that does not move (no effect, or less motion asked for) stays as it is.
    if (!seen || effect === 'none' || reducedMotion()) { setMoving(false); return }
    setMoving(true)
    const period = len + 0.5
    const t0 = performance.now()
    const stop = play((now) => { const tm = ((now - t0) / 1000) % period; at(tm < len ? tm : rest) })
    return () => { stop(); setMoving(false) }
  }, [key, near, seen, fontReady])
  return (
    <button ref={(e) => { btn.current = e; ref(e) }} class={'chip fx-card' + (on ? ' on' : '')} role="radio" aria-checked={on} data-effect={effect} data-moving={moving ? '1' : '0'} onClick={onPick}>
      <canvas ref={canvas} aria-hidden="true" />
      <span class="fx-name">{label}</span>
    </button>
  )
}

/** The effects, three to a row, each playing on a sample line in the style's own look. */
export function EffectCards({ style, effects, onPick }: { style: SubStyle; effects: [SubEffect, string][]; onPick: (e: SubEffect) => void }) {
  return (
    <div class="cards effects" role="radiogroup" aria-label={t('אפקט')}>
      {effects.map(([e, label]) => <EffectCard key={e} style={style} effect={e} label={t(label)} on={style.effect === e} onPick={() => onPick(e)} />)}
    </div>
  )
}

/** The sample every font card shows: Hebrew letters and Latin ones, as the app's. */
const SAMPLE = 'אבג Abc'

/** A font as a card: the sample in that font (loaded when the card is near the screen), the name under it. */
export function FontCard({ choice, label, on, onPick, onDelete }: { choice: FontChoice; label: string; on: boolean; onPick: () => void; onDelete?: () => void }) {
  const [ref, near] = useNear<HTMLSpanElement>()
  const [ready, setReady] = useState(false)
  useEffect(() => {
    if (!near || ready) return
    let live = true
    loadStyleFont(choice).then(() => { if (live) setReady(true) })
    return () => { live = false }
  }, [near, ready, choice.font, choice.customFont])
  const family = fontFamily(choice)
  const sample = <span class="font-sample" aria-hidden="true" data-ready={ready ? '1' : '0'} style={{ fontFamily: family, fontWeight: fontWeight({ ...choice, weight: 0 }) }}>{SAMPLE}</span>
  const name = <span class="font-name">{label}</span>
  // An imported font has its delete beside the choice (a span holds both, as before).
  if (onDelete) {
    return (
      <span ref={ref} class={'chip font-card custom-font' + (on ? ' on' : '')}>
        <button class="link-btn font-pick" aria-pressed={on} onClick={onPick}>{sample}{name}</button>
        <button class="link-btn font-del" aria-label={t('מחק')} title={t('מחק')} onClick={onDelete}><span aria-hidden="true">×</span></button>
      </span>
    )
  }
  return (
    <button ref={ref as any} class={'chip font-card' + (on ? ' on' : '')} role="radio" aria-checked={on} data-font={choice.font} onClick={onPick}>
      {sample}{name}
    </button>
  )
}
