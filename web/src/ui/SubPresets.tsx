// The ready-made subtitle looks as a row of cards (as the app's style panel): each card is a
// small picture of a sample line drawn with the real subtitle renderer, the chosen one outlined.
import { useEffect, useRef } from 'preact/hooks'
import { lang, t } from '../i18n'
import { drawCue } from '../lib/subrender'
import { DEFAULT_SUB_STYLE, loadSubFont, SUB_PRESETS, type SubStyle } from '../lib/substyle'
import { useFrame } from './TemplatePreview'

const CARD_W = 132, CARD_H = 84

/** The sample line: Hebrew in a Hebrew UI, English in an English one. */
export function sampleText(): string {
  return lang.value === 'en' ? t('כך נראות הכתוביות') : 'כך נראות הכתוביות'
}

/** Whether [style] is [preset]'s look (what a card sets; the shown language and the imported font are not part of it). */
export function samePreset(style: SubStyle, preset: SubStyle): boolean {
  if (style.customFont) return false
  const keys: (keyof SubStyle)[] = ['font', 'size', 'color', 'accent', 'outline', 'shadow', 'glow', 'box', 'effect', 'position', 'weight']
  return keys.every((k) => (style[k] ?? 0) === (preset[k] ?? 0)) && (!preset.box || style.boxColor === preset.boxColor)
}

function Card({ name, preset, frame, on, onPick }: { name: string; preset: SubStyle; frame: HTMLCanvasElement | null; on: boolean; onPick: () => void }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const text = sampleText()
  useEffect(() => {
    const c = canvas.current
    if (!c) return
    let live = true
    const dpr = 2
    c.width = CARD_W * dpr
    c.height = CARD_H * dpr
    const draw = () => {
      const g = c.getContext('2d')!
      g.clearRect(0, 0, c.width, c.height)
      if (frame) {
        const s = Math.max(c.width / frame.width, c.height / frame.height)
        g.drawImage(frame, (c.width - frame.width * s) / 2, (c.height - frame.height * s) / 2, frame.width * s, frame.height * s)
        g.fillStyle = 'rgba(0,0,0,0.25)'
      } else {
        const grad = g.createLinearGradient(0, 0, c.width, c.height)
        grad.addColorStop(0, '#3a4256'); grad.addColorStop(1, '#1c2030')
        g.fillStyle = grad
      }
      g.fillRect(0, 0, c.width, c.height)
      const words = text.split(' ')
      // A preview frame, not a real video's: the subtitles are drawn as they look once all said.
      drawCue(g, [text], 0, 4, 1.4, { ...preset, size: preset.size * 1.9 }, c.width, c.height, [words.map((_, i) => i * 0.3)])
    }
    loadSubFont({ ...DEFAULT_SUB_STYLE, ...preset }, document.fonts, document.baseURI).then(() => { if (live) draw() })
    draw()
    return () => { live = false }
  }, [frame, text, name])
  return (
    <button class={'chip preset' + (on ? ' on' : '')} role="radio" aria-checked={on} onClick={onPick}>
      <canvas ref={canvas} style={{ width: CARD_W + 'px', height: CARD_H + 'px' }} aria-hidden="true" />
      <span class="preset-name">{t(name)}</span>
    </button>
  )
}

/** A horizontally scrolling row of look cards. */
export function SubPresetRow({ style, onStyle, file }: { style: SubStyle; onStyle: (s: SubStyle) => void; file: File | null }) {
  const frame = useFrame(file)
  const row = useRef<HTMLDivElement>(null)
  // The chosen card is in view when the row first shows.
  useEffect(() => { row.current?.querySelector('.on')?.scrollIntoView({ inline: 'center', block: 'nearest' }) }, [])
  return (
    <div class="chips presets preset-row" role="radiogroup" aria-label={t('סגנונות')} ref={row}>
      {SUB_PRESETS.map(([name, p]) => (
        <Card key={name} name={name} preset={p} frame={frame} on={samePreset(style, p)} onPick={() => onStyle({ ...p, show: style.show, customFont: null, speakerColors: style.speakerColors, maskBleeped: style.maskBleeped })} />
      ))}
    </div>
  )
}
