// How burned-in subtitles look, as in the Android app: a font (bundled ones with Hebrew and Latin,
// SIL Open Font License, or one imported), colors, outline, shadow, glow, a box, an effect that
// moves them, and where they sit. Ready-made looks too.

export type SubShow = 'src' | 'tr' | 'both'

export type SubEffect =
  | 'none' | 'fade' | 'pop' | 'highlight' | 'wordBox' | 'oneWord' | 'typewriter' | 'slideUp' | 'zoom'
  | 'rotate' | 'glitch' | 'flicker' | 'letters' | 'wordPop' | 'wordFade' | 'activeZoom' | 'bounce' | 'shake' | 'typing'

export type SubFontId = 'rubik' | 'allo' | 'heebo' | 'assistant' | 'secular' | 'suez' | 'varela' | 'fredoka' | 'karantina' | 'amatic' | 'system'

/** A bundled font: its file, its name, its default weight if it's variable (and the range). */
export const SUB_FONTS: { id: SubFontId; file: string | null; label: string; weight: number; min?: number; max?: number }[] = [
  { id: 'rubik', file: 'rubik.ttf', label: 'Rubik', weight: 800, min: 300, max: 900 },
  { id: 'allo', file: 'allo_hebrew.ttf', label: 'Allo', weight: 800, min: 100, max: 900 },
  { id: 'heebo', file: 'heebo.ttf', label: 'Heebo', weight: 800, min: 100, max: 900 },
  { id: 'assistant', file: 'assistant.ttf', label: 'Assistant', weight: 800, min: 200, max: 800 },
  { id: 'secular', file: 'secular_one.ttf', label: 'Secular One', weight: 0 },
  { id: 'suez', file: 'suez_one.ttf', label: 'Suez One', weight: 0 },
  { id: 'varela', file: 'varela_round.ttf', label: 'Varela Round', weight: 0 },
  { id: 'fredoka', file: 'fredoka.ttf', label: 'Fredoka', weight: 700, min: 300, max: 700 },
  { id: 'karantina', file: 'karantina.ttf', label: 'Karantina', weight: 0 },
  { id: 'amatic', file: 'amatic_sc.ttf', label: 'Amatic SC', weight: 0 },
  { id: 'system', file: null, label: 'System', weight: 0 },
]

export interface SubStyle {
  font: SubFontId
  /** An imported font (its name in the browser's storage); overrides [font]. */
  customFont?: string | null
  /** Multiplier of the base text size (5.5% of the frame's short side). */
  size: number
  color: string
  /** The word being said, in effects that show it. */
  accent: string
  /** Outline width as a fraction of the text size (0: none). */
  outline: number
  outlineColor: string
  shadow: boolean
  glow: boolean
  box: boolean
  /** #rrggbbaa */
  boxColor: string
  effect: SubEffect
  /** The text's vertical center: 0 the top of the frame, 1 the bottom. */
  position: number
  /** A variable font's weight (0: its default). */
  weight: number
  /** Allo's roundness, 0..100 (50 its own). */
  round?: number
  /** Text colors of speakers 2, 3, ... (speaker 1: [color]). */
  speakerColors?: string[]
  /** Bleeped words shown as **** in the subtitles too (as they're said: false). */
  maskBleeped?: boolean
  /** The text, its translation or both. */
  show?: SubShow
}

const YELLOW = '#ffe135'
const RED = '#ff2d55'
const CYAN = '#3df2ff'
const GREEN = '#34e07a'
const PURPLE = '#9b5cff'

export const DEFAULT_SUB_STYLE: SubStyle = {
  font: 'heebo', customFont: null, size: 1, color: '#ffffff', accent: YELLOW, outline: 0.16, outlineColor: '#000000',
  shadow: true, glow: false, box: false, boxColor: '#000000b8', effect: 'none', position: 0.86, weight: 0, show: 'src',
}

const s = (p: Partial<SubStyle>): SubStyle => ({ ...DEFAULT_SUB_STYLE, ...p })

/** Ready-made looks, as in the app (names in Hebrew, translated in the UI). */
export const SUB_PRESETS: [string, SubStyle][] = [
  ['קלאסי', s({})],
  ['טיקטוק', s({ font: 'rubik', size: 1.15, outline: 0.12, effect: 'pop', position: 0.7 })],
  ['מילה מודגשת', s({ font: 'rubik', size: 1.15, outline: 0.14, effect: 'highlight', position: 0.7 })],
  ['תיבה צבעונית', s({ font: 'rubik', size: 1.1, outline: 0, accent: RED, effect: 'wordBox', position: 0.7 })],
  ['מילה אחת', s({ font: 'secular', size: 1.7, outline: 0.14, effect: 'oneWord', position: 0.55 })],
  ['ויראלי צהוב', s({ font: 'secular', size: 1.25, color: YELLOW, outline: 0.2, effect: 'pop', position: 0.66 })],
  ['רקע שחור', s({ font: 'assistant', outline: 0, shadow: false, box: true, effect: 'fade', position: 0.82 })],
  ['רקע לבן', s({ font: 'rubik', color: '#000000', outline: 0, shadow: false, box: true, boxColor: '#fffffff2', effect: 'pop', position: 0.75 })],
  ['מכונת כתיבה', s({ font: 'varela', outline: 0, shadow: false, box: true, effect: 'typewriter', position: 0.8 })],
  ['ניאון', s({ font: 'fredoka', size: 1.15, color: CYAN, outline: 0, shadow: false, glow: true, effect: 'fade', position: 0.7 })],
  ['ירוק קריוקי', s({ font: 'heebo', size: 1.1, outline: 0.16, accent: GREEN, effect: 'highlight', position: 0.72 })],
  ['סגול', s({ font: 'fredoka', size: 1.1, outline: 0, accent: PURPLE, effect: 'wordBox', position: 0.72 })],
  ['כותרת', s({ font: 'karantina', size: 1.6, outline: 0.12, effect: 'pop', position: 0.5 })],
  ['כתב יד', s({ font: 'amatic', size: 1.5, outline: 0.1, effect: 'fade', position: 0.78 })],
  ['מילים קופצות', s({ font: 'rubik', size: 1.2, outline: 0.14, effect: 'wordPop', position: 0.68 })],
  ['זום למילה', s({ font: 'rubik', size: 1.15, outline: 0.14, accent: GREEN, effect: 'activeZoom', position: 0.68 })],
  ['קפיצה למילה', s({ font: 'secular', size: 1.2, outline: 0.16, effect: 'bounce', position: 0.68 })],
  ['רעידה', s({ font: 'secular', size: 1.2, outline: 0.16, accent: RED, effect: 'shake', position: 0.66 })],
  ["גליץ'", s({ font: 'karantina', size: 1.5, outline: 0, effect: 'glitch', position: 0.6 })],
  ['החלקה', s({ font: 'assistant', size: 1.05, outline: 0, shadow: false, box: true, effect: 'slideUp', position: 0.8 })],
  ['זום', s({ font: 'heebo', size: 1.2, outline: 0.16, effect: 'zoom', position: 0.7 })],
  ['סיבוב', s({ font: 'fredoka', size: 1.2, color: YELLOW, outline: 0.14, effect: 'rotate', position: 0.66 })],
  ['ניאון מהבהב', s({ font: 'fredoka', size: 1.15, color: '#ff6fb5', outline: 0, shadow: false, glow: true, effect: 'flicker', position: 0.7 })],
  ['אות אחר אות', s({ font: 'varela', size: 1.05, outline: 0.12, effect: 'letters', position: 0.78 })],
  ['הקלדה', s({ font: 'assistant', size: 1.05, outline: 0, shadow: false, box: true, boxColor: '#000000e6', effect: 'typing', position: 0.8 })],
  ['מילים בהדרגה', s({ font: 'assistant', size: 1.1, outline: 0.1, effect: 'wordFade', position: 0.75 })],
  ['עגול', s({ font: 'allo', weight: 900, size: 1.2, outline: 0.14, effect: 'pop', position: 0.68 })],
  ['אלגנטי', s({ font: 'allo', weight: 300, size: 1.1, outline: 0, effect: 'wordFade', position: 0.8 })],
]

/** The effects, in the app's order and names. */
export const SUB_EFFECTS: [SubEffect, string][] = [
  ['none', 'בלי'], ['fade', 'הופעה רכה'], ['pop', 'קפיצה'], ['slideUp', 'החלקה למעלה'], ['zoom', 'זום'], ['rotate', 'סיבוב'],
  ['typewriter', 'מכונת כתיבה'], ['typing', 'הקלדה'], ['letters', 'אות אחר אות'], ['wordFade', 'מילים בהדרגה'], ['wordPop', 'מילים קופצות'],
  ['oneWord', 'מילה אחת'], ['highlight', 'מילה מודגשת'], ['wordBox', 'תיבה על המילה'], ['activeZoom', 'זום למילה'], ['bounce', 'קפיצה למילה'],
  ['shake', 'רעידה'], ['glitch', "גליץ'"], ['flicker', 'ניאון מהבהב'],
]

/** Effects that show the word being said (and so use the accent color). */
export const ACCENT_EFFECTS: SubEffect[] = ['highlight', 'wordBox', 'activeZoom', 'bounce', 'shake']

export const SUB_COLORS = ['#ffffff', YELLOW, '#ffb020', RED, '#ff6fb5', PURPLE, '#4d8dff', CYAN, GREEN, '#000000']

/** A style from what was saved, even in the web app's first shape (position, look, color, weight). */
export function normalizeStyle(o: any): SubStyle {
  if (!o || typeof o !== 'object') return DEFAULT_SUB_STYLE
  const st: SubStyle = { ...DEFAULT_SUB_STYLE }
  if (typeof o.font === 'string' && SUB_FONTS.some((f) => f.id === o.font)) st.font = o.font
  if (typeof o.customFont === 'string') st.customFont = o.customFont
  if (typeof o.size === 'number') st.size = o.size
  if (typeof o.color === 'string') st.color = o.color
  if (typeof o.accent === 'string') st.accent = o.accent
  if (typeof o.outline === 'number') st.outline = o.outline
  if (typeof o.outlineColor === 'string') st.outlineColor = o.outlineColor
  if (typeof o.shadow === 'boolean') st.shadow = o.shadow
  if (typeof o.glow === 'boolean') st.glow = o.glow
  if (typeof o.box === 'boolean') st.box = o.box
  if (typeof o.boxColor === 'string') st.boxColor = o.boxColor
  if (typeof o.effect === 'string' && SUB_EFFECTS.some(([e]) => e === o.effect)) st.effect = o.effect
  if (typeof o.weight === 'number') st.weight = o.weight
  if (typeof o.round === 'number') st.round = snapRound(o.round)
  if (typeof o.maskBleeped === 'boolean') st.maskBleeped = o.maskBleeped
  if (Array.isArray(o.speakerColors) && o.speakerColors.every((c: unknown) => typeof c === 'string')) st.speakerColors = o.speakerColors
  if (o.show === 'src' || o.show === 'tr' || o.show === 'both') st.show = o.show
  if (typeof o.position === 'number') st.position = Math.min(0.95, Math.max(0.05, o.position))
  // The first web shape.
  if (o.position === 'top') st.position = 0.14
  else if (o.position === 'middle') st.position = 0.5
  else if (o.position === 'bottom') st.position = 0.86
  if (o.look === 'box') { st.box = true; st.outline = 0; st.shadow = false }
  else if (o.look === 'outline') { st.outline = 0.16; st.shadow = false }
  else if (o.look === 'shadow') { st.outline = 0; st.shadow = true }
  if (o.look && typeof o.weight === 'number' && o.weight === 700) st.weight = 0
  return st
}

/** A font as chosen for subtitles or the watermark: bundled (with its weight and roundness) or imported. */
export type FontChoice = { font: SubFontId; customFont?: string | null; weight: number; round?: number }

/** Allo's roundness choices: each a copy of the font with the roundness set (made at build time); 50 is its own. */
export const ROUNDS = [0, 25, 50, 75, 100]
export const ROUND_DEFAULT = 50
export const snapRound = (r?: number) => ROUNDS.reduce((a, b) => (Math.abs(b - (r ?? ROUND_DEFAULT)) < Math.abs(a - (r ?? ROUND_DEFAULT)) ? b : a))
/** Whether [font] has a roundness to choose (Allo). */
export const isRounded = (font: SubFontId) => font === 'allo'

/** The family name and file of a bundled font (Allo per roundness). */
function bundled(c: FontChoice): { family: string; file: string } | null {
  const f = SUB_FONTS.find((x) => x.id === c.font)
  if (!f?.file) return null
  const r = isRounded(c.font) ? snapRound(c.round) : ROUND_DEFAULT
  if (r === ROUND_DEFAULT) return { family: `ONIT ${f.label}`, file: f.file }
  return { family: `ONIT ${f.label} r${r}`, file: f.file.replace('.ttf', `_r${r}.ttf`) }
}

/** The font family the canvas draws [c] with. */
export function fontFamily(c: FontChoice): string {
  if (c.customFont) return `'ONIT custom ${c.customFont}', system-ui, sans-serif`
  const b = bundled(c)
  return b ? `'${b.family}', system-ui, sans-serif` : 'system-ui, sans-serif'
}

/** The weight drawn with: the variable font's chosen one (within its range), else bold. */
export function fontWeight(c: FontChoice): number {
  if (c.customFont) return 700
  const f = SUB_FONTS.find((x) => x.id === c.font)
  if (!f || !f.weight) return f?.file ? 400 : 700
  const w = c.weight > 0 ? c.weight : f.weight
  return Math.min(f.max ?? 900, Math.max(f.min ?? 100, w))
}

const loaded = new Map<string, Promise<boolean>>()

/**
 * Loads [c]'s font into [fonts] (the page's or a worker's), from [base] (the site's root URL) or,
 * for an imported one, [custom] (its bytes). Resolves when it can be drawn with.
 */
export function loadSubFont(c: FontChoice, fonts: FontFaceSet, base: string, custom?: ArrayBuffer | null): Promise<boolean> {
  const f = SUB_FONTS.find((x) => x.id === c.font)
  const b = c.customFont ? null : bundled(c)
  const key = c.customFont ? 'custom:' + c.customFont : b?.file ?? ''
  if (!key) return Promise.resolve(true)
  if (c.customFont && !custom && !loaded.has(key)) return Promise.resolve(false)
  let p = loaded.get(key)
  if (!p) {
    p = (async () => {
      try {
        const face = c.customFont
          ? custom ? new FontFace(`ONIT custom ${c.customFont}`, custom) : null
          : new FontFace(b!.family, `url(${new URL('fonts/' + b!.file, base).href})`, f!.weight ? { weight: `${f!.min ?? 100} ${f!.max ?? 900}` } : {})
        if (!face) return false
        await face.load()
        fonts.add(face)
        return true
      } catch {
        return false
      }
    })()
    loaded.set(key, p)
    // A font that failed to load (a dropped connection) is tried again next time, not remembered as lost.
    p.then((ok) => { if (!ok && loaded.get(key) === p) loaded.delete(key) })
  }
  return p
}
