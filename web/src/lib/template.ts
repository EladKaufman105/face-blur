// Edit templates, as in the Android app (docs/specs/templates-and-batch.md): a named set of
// design and technical choices for subtitles and editing, with a switch per category. An excluded
// category changes nothing. No platforms.
//
// The stored JSON has the app's own shape (Template.toJson in the Android code: enum names in
// capitals, colors as signed ARGB numbers, a subtitle style as a JSON string) wherever the two
// have the same feature, so a template can later be exported from one and imported in the other.
// What only the web has (a logo or background picture as a data URL, the subtitle style's
// bleep mask, speakers and show choice) goes in extra keys that the app ignores.
import { DEFAULT_LOOK, loadLook, type BlurMode, type Look } from './types'
import { DEFAULT_SUB_STYLE, normalizeStyle, SUB_EFFECTS, SUB_FONTS, type SubStyle } from './substyle'
import { NO_BACKGROUND, type Background, type BgFill, type BgKey } from './background'
import type { Aspect } from './camera'
import { DEFAULT_EXPORT, loadExportOptions, type ExportCodec, type ExportOptions, type ExportQuality } from './exportopts'
import { NO_WATERMARK, POSITIONS, type Watermark, type WatermarkLook, type WatermarkPosition } from './watermark'
import type { VoiceFx } from './voice'
import { storageKey } from './channel'

export type TemplateCategory = 'blur' | 'subs' | 'voice' | 'cuts' | 'background' | 'stabilize' | 'export'
export const CATEGORIES: TemplateCategory[] = ['blur', 'subs', 'voice', 'cuts', 'background', 'stabilize', 'export']

/** Which subtitles are burned in (as the app's SubLang). */
export type SubLang = 'NONE' | 'ORIGINAL' | 'TRANSLATED' | 'BOTH'

/** Face blur. An undefined field is left as it is; [none]: no face blur at all. */
export type BlurPart = { none?: boolean; mode?: BlurMode; strength?: number; padding?: number; sensitive?: boolean }
/** Subtitles. An undefined field is left as it is. [language]: 'he', 'en', 'ar', 'fa' or 'auto'. */
export type SubsPart = {
  create?: boolean
  model?: 'HEBREW' | 'MULTILINGUAL'
  language?: string
  translate?: boolean
  style?: SubStyle
  splitWords?: number
  splitChars?: number
  burn?: SubLang
  saveSrt?: boolean
}
export type VoicePart = { voice: VoiceFx; denoise: number; enhance: boolean; loud: boolean }
export type CutsPart = { silences: boolean; minPauseMs: number; fillers: boolean }
export type ExportPart = { aspect?: Aspect; shortSide?: number; fps?: number; quality?: ExportQuality; codec?: ExportCodec; watermark?: Watermark }

export type Template = {
  id: string
  name: string
  /** A user's own, or one of the built-in looks. */
  group: 'USER' | 'LOOK'
  builtIn: boolean
  blur?: BlurPart
  subs?: SubsPart
  voice?: VoicePart
  cuts?: CutsPart
  background?: Background
  stabilize?: number
  export?: ExportPart
}

export const includes = (t: Template, c: TemplateCategory): boolean => t[c] !== undefined

/** [t] without the categories not in [keep]. */
export function only(t: Template, keep: Set<TemplateCategory>): Template {
  const o: Template = { ...t }
  for (const c of CATEGORIES) if (!keep.has(c)) delete o[c]
  return o
}

/** The categories [t] includes. */
export const categoriesOf = (t: Template): TemplateCategory[] => CATEGORIES.filter((c) => includes(t, c))

// ------------------------------------------------------------------ what a video is processed with

/** Everything a template can set, as plain values: what a video is processed with. */
export type TemplateSettings = {
  blurOff: boolean
  look: Look
  sensitive: boolean
  createSubs: boolean
  /** The speech's language as Whisper names it (null: it finds it). */
  language: string | null
  translate: boolean
  subStyle: SubStyle
  split: { words: number; chars: number }
  burn: SubLang
  saveSrt: boolean
  voice: VoiceFx
  denoise: number
  enhance: boolean
  loud: boolean
  cutSilences: boolean
  minPauseMs: number
  cutFillers: boolean
  background: Background
  stabilize: number
  aspect: Aspect
  shortSide: number
  fps: number
  quality: ExportQuality
  codec: ExportCodec
  watermark: Watermark
}

/** The speech language the user chose last (the subtitles tab keeps it), else Hebrew in the Hebrew UI. */
export function savedLanguage(hebrewUi: boolean): string | null {
  try {
    const l = localStorage.getItem(storageKey('subs.lang'))
    if (l) return l
  } catch {}
  return hebrewUi ? 'hebrew' : null
}

/** Settings as they stand with no template: the last look, subtitle design, watermark and export choices. */
export function currentSettings(opts: { sensitive: boolean; hebrewUi: boolean; watermark: Watermark; options?: ExportOptions }): TemplateSettings {
  let style = DEFAULT_SUB_STYLE
  try { style = normalizeStyle(JSON.parse(localStorage.getItem(storageKey('substyle')) ?? 'null')) } catch {}
  const o = opts.options ?? { ...loadExportOptions(), aspect: 'original' as Aspect }
  return {
    blurOff: false, look: loadLook(), sensitive: opts.sensitive,
    createSubs: false, language: savedLanguage(opts.hebrewUi), translate: false, subStyle: style, split: { words: 0, chars: 0 },
    burn: o.burn === false ? 'NONE' : showToBurn(style.show), saveSrt: o.srt,
    voice: 'none', denoise: 0, enhance: false, loud: false,
    cutSilences: false, minPauseMs: 800, cutFillers: false,
    background: NO_BACKGROUND, stabilize: 0,
    aspect: o.aspect, shortSide: o.shortSide, fps: o.fps, quality: o.quality, codec: o.codec, watermark: opts.watermark,
  }
}

const showToBurn = (s: SubStyle['show']): SubLang => (s === 'tr' ? 'TRANSLATED' : s === 'both' ? 'BOTH' : 'ORIGINAL')

/** Something is done to the video besides the face blur. */
export const hasProcessing = (s: TemplateSettings) =>
  s.createSubs || s.voice !== 'none' || s.denoise > 0 || s.enhance || s.loud || s.cutSilences || s.cutFillers || s.background.fill !== 'none' || s.stabilize > 0

/** The export options [s] stands for (over [base]'s format). */
export function exportOptionsOf(s: TemplateSettings, base: ExportOptions = DEFAULT_EXPORT): ExportOptions {
  // The template's settings are the video's: an audio export (MP3) chosen on the ready screen stays.
  return { ...base, format: base.format === 'mp3' ? 'mp3' : 'mp4', shortSide: s.shortSide, fps: s.fps, quality: s.quality, codec: s.codec, srt: s.saveSrt, burn: s.burn === 'NONE' ? false : undefined, aspect: s.aspect }
}

/** The subtitle style as burned in: its "show" follows the burned-in language. */
export function styleOf(s: TemplateSettings): SubStyle {
  return { ...s.subStyle, show: s.burn === 'TRANSLATED' ? 'tr' : s.burn === 'BOTH' ? 'both' : 'src' }
}

/** [s] with [o]'s export choices (the ready screen's export sheet). */
export function withExportOptions(s: TemplateSettings, o: ExportOptions): TemplateSettings {
  return { ...s, shortSide: o.shortSide, fps: o.fps, quality: o.quality, codec: o.codec, saveSrt: o.srt, aspect: o.aspect, burn: o.burn === false ? 'NONE' : s.burn === 'NONE' ? 'ORIGINAL' : s.burn }
}

/** Whisper's name of the app's language code. */
const LANG_OF: Record<string, string | null> = { he: 'hebrew', en: 'english', ar: 'arabic', fa: 'persian', auto: null }
const CODE_OF: Record<string, string> = { hebrew: 'he', english: 'en', arabic: 'ar', persian: 'fa' }
/** The app's language code of Whisper's name (null: it finds it). */
export const languageCode = (whisper: string | null): string => CODE_OF[whisper ?? ''] ?? 'auto'
/** Whisper's name of the app's language code. */
export const languageName = (code: string): string | null => LANG_OF[code] ?? null

/** Lays [t] over [base]: what the template includes is replaced, the rest stays. */
export function applyTemplate(t: Template, base: TemplateSettings): TemplateSettings {
  let s = base
  if (t.blur) {
    const b = t.blur
    s = { ...s, blurOff: b.none ?? s.blurOff, look: { mode: b.mode ?? s.look.mode, strength: b.strength ?? s.look.strength, padding: b.padding ?? s.look.padding }, sensitive: b.sensitive ?? s.sensitive }
  }
  if (t.subs) {
    const p = t.subs
    s = {
      ...s,
      createSubs: p.create ?? s.createSubs,
      language: p.language !== undefined ? (LANG_OF[p.language] ?? null) : s.language,
      translate: p.translate ?? s.translate,
      subStyle: p.style ? { ...p.style, show: s.subStyle.show } : s.subStyle,
      split: { words: p.splitWords ?? s.split.words, chars: p.splitChars ?? s.split.chars },
      burn: p.burn ?? s.burn,
      saveSrt: p.saveSrt ?? s.saveSrt,
    }
  }
  if (t.voice) s = { ...s, voice: t.voice.voice, denoise: t.voice.denoise, enhance: t.voice.enhance, loud: t.voice.loud }
  if (t.cuts) s = { ...s, cutSilences: t.cuts.silences, minPauseMs: t.cuts.minPauseMs, cutFillers: t.cuts.fillers }
  if (t.background) s = { ...s, background: t.background }
  if (t.stabilize !== undefined) s = { ...s, stabilize: Math.min(3, Math.max(0, t.stabilize)) }
  if (t.export) {
    const e = t.export
    s = { ...s, aspect: e.aspect ?? s.aspect, shortSide: e.shortSide ?? s.shortSide, fps: e.fps ?? s.fps, quality: e.quality ?? s.quality, codec: e.codec ?? s.codec, watermark: e.watermark ?? s.watermark }
  }
  return s
}

/** The template made of [s]'s values, with only the [keep] categories (the editor's "Save as template"). */
export function templateOf(s: TemplateSettings, id: string, name: string, keep: Set<TemplateCategory>): Template {
  return only({
    id, name, group: 'USER', builtIn: false,
    blur: { none: s.blurOff, mode: s.look.mode, strength: s.look.strength, padding: s.look.padding, sensitive: s.sensitive },
    subs: { create: s.createSubs, language: CODE_OF[s.language ?? ''] ?? 'auto', translate: s.translate, style: { ...s.subStyle, show: undefined }, splitWords: s.split.words, splitChars: s.split.chars, burn: s.burn, saveSrt: s.saveSrt },
    voice: { voice: s.voice, denoise: s.denoise, enhance: s.enhance, loud: s.loud },
    cuts: { silences: s.cutSilences, minPauseMs: s.minPauseMs, fillers: s.cutFillers },
    background: s.background,
    stabilize: s.stabilize,
    export: { aspect: s.aspect, shortSide: s.shortSide, fps: s.fps, quality: s.quality, codec: s.codec, watermark: s.watermark },
  }, keep)
}

/** A template with every category, at the neutral values (a new one to edit). */
export function blankTemplate(id: string, name: string): Template {
  return templateOf(currentSettings({ sensitive: false, hebrewUi: true, watermark: NO_WATERMARK, options: { ...DEFAULT_EXPORT } }), id, name, new Set(CATEGORIES))
}

// ------------------------------------------------------------------ built-in looks

const look = (id: string, name: string, style: Partial<SubStyle>, mode: BlurMode, strength: number, splitChars = 0): Template => ({
  id, name, group: 'LOOK', builtIn: true,
  blur: { mode, strength },
  // The language isn't set: the user's own chosen one is used (the app's Hebrew model has no
  // counterpart here: one multilingual model).
  subs: { create: true, burn: 'ORIGINAL', style: { ...DEFAULT_SUB_STYLE, ...style }, splitWords: 0, splitChars },
})

/** The design presets that ship with the app, as the Android app's. Names are Hebrew (translated in the UI). */
export const BUILT_IN: Template[] = [
  look('look.clean', 'נקי', { font: 'heebo', size: 1, outline: 0.06, shadow: true, effect: 'fade', position: 0.82 }, 'blur', 0.6),
  look('look.bold', 'בולט', { font: 'rubik', size: 1.15, outline: 0.14, effect: 'highlight', position: 0.7 }, 'blur', 0.85),
  look('look.news', 'חדשות', { font: 'assistant', size: 1, outline: 0, shadow: false, box: true, boxColor: '#000000cc', effect: 'none', position: 0.84 }, 'pixelate', 0.7),
  look('look.minimal', 'מינימלי', { font: 'assistant', size: 0.8, outline: 0.04, shadow: false, effect: 'fade', position: 0.9 }, 'blur', 0.5, 32),
]

export const builtIn = (id: string): Template | undefined => BUILT_IN.find((b) => b.id === id)

// ------------------------------------------------------------------ the JSON, in the app's shape

const MODE_OUT: Record<BlurMode, string> = { blur: 'BLUR', pixelate: 'PIXELATE', solid: 'SOLID' }
const MODE_IN: Record<string, BlurMode> = { BLUR: 'blur', PIXELATE: 'pixelate', SOLID: 'solid', COVER: 'solid' }
const ASPECT_OUT: Record<Aspect, string> = { original: 'ORIGINAL', '9:16': 'VERTICAL', '1:1': 'SQUARE', '4:5': 'PORTRAIT' }
const ASPECT_IN: Record<string, Aspect> = { ORIGINAL: 'original', VERTICAL: '9:16', SQUARE: '1:1', PORTRAIT: '4:5' }
const QUALITY_OUT: Record<ExportQuality, string> = { original: 'ORIGINAL', high: 'HIGH', saver: 'SAVER' }
const QUALITY_IN: Record<string, ExportQuality> = { ORIGINAL: 'original', HIGH: 'high', SAVER: 'saver' }
const CODEC_OUT: Record<ExportCodec, string> = { same: 'SAME', avc: 'H264', hevc: 'HEVC' }
const CODEC_IN: Record<string, ExportCodec> = { SAME: 'same', H264: 'avc', HEVC: 'hevc' }
const POS_NAMES = ['TOP_LEFT', 'TOP_CENTER', 'TOP_RIGHT', 'CENTER_LEFT', 'CENTER', 'CENTER_RIGHT', 'BOTTOM_LEFT', 'BOTTOM_CENTER', 'BOTTOM_RIGHT']
const snake = (s: string) => s.replace(/[A-Z]/g, (c) => '_' + c).toUpperCase()
const camel = (s: string) => s.toLowerCase().replace(/_([a-z])/g, (_, c) => c.toUpperCase())

/** '#rrggbb' or '#rrggbbaa' as a signed ARGB number. */
export function colorToInt(hex: string): number {
  const h = hex.replace('#', '')
  const rgb = parseInt(h.slice(0, 6), 16) || 0
  const a = h.length >= 8 ? parseInt(h.slice(6, 8), 16) : 255
  return ((a << 24) | rgb) | 0
}

/** A signed ARGB number as '#rrggbb' (or '#rrggbbaa' when not opaque). */
export function intToColor(n: number): string {
  const u = n >>> 0
  const a = u >>> 24
  const rgb = (u & 0xffffff).toString(16).padStart(6, '0')
  return '#' + rgb + (a === 255 ? '' : a.toString(16).padStart(2, '0'))
}

function styleToJson(s: SubStyle): string {
  const o: Record<string, unknown> = {
    font: s.font.toUpperCase(), size: s.size, text: colorToInt(s.color), accent: colorToInt(s.accent), outline: s.outline, outlineColor: colorToInt(s.outlineColor),
    shadow: s.shadow, glow: s.glow, box: s.box, boxColor: colorToInt(s.boxColor), effect: snake(s.effect), position: s.position,
  }
  if (s.customFont) o.customFont = s.customFont
  if (s.speakerColors) o.speakers = s.speakerColors.map(colorToInt)
  if (s.weight > 0) o.weight = s.weight
  if (s.round !== undefined && s.round !== 50) o.round = s.round
  if (s.maskBleeped !== undefined) o.maskBleeped = s.maskBleeped
  return JSON.stringify(o)
}

function styleFromJson(raw: unknown): SubStyle | undefined {
  let o: any
  try { o = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { return undefined }
  if (!o || typeof o !== 'object') return undefined
  const d = DEFAULT_SUB_STYLE
  const font = typeof o.font === 'string' ? o.font.toLowerCase() : d.font
  const effect = typeof o.effect === 'string' ? camel(o.effect) : d.effect
  const num = (v: unknown, def: number) => (typeof v === 'number' ? v : def)
  const col = (v: unknown, def: string) => (typeof v === 'number' ? intToColor(v) : def)
  return normalizeStyle({
    font: SUB_FONTS.some((f) => f.id === font) ? font : d.font,
    customFont: typeof o.customFont === 'string' ? o.customFont : null,
    size: num(o.size, d.size), color: col(o.text, d.color), accent: col(o.accent, d.accent),
    outline: num(o.outline, d.outline), outlineColor: col(o.outlineColor, d.outlineColor),
    shadow: typeof o.shadow === 'boolean' ? o.shadow : d.shadow, glow: typeof o.glow === 'boolean' ? o.glow : d.glow,
    box: typeof o.box === 'boolean' ? o.box : d.box, boxColor: col(o.boxColor, d.boxColor),
    effect: SUB_EFFECTS.some(([e]) => e === effect) ? effect : d.effect,
    position: num(o.position, d.position), weight: num(o.weight, 0), round: typeof o.round === 'number' ? o.round : undefined,
    speakerColors: Array.isArray(o.speakers) && o.speakers.every((x: unknown) => typeof x === 'number') && o.speakers.length ? o.speakers.map(intToColor) : undefined,
    maskBleeped: typeof o.maskBleeped === 'boolean' ? o.maskBleeped : undefined,
  })
}

function watermarkToJson(w: Watermark): string {
  const o: Record<string, unknown> = {
    text: w.text, image: w.image !== null, corner: POS_NAMES[POSITIONS.indexOf(w.position)] ?? 'BOTTOM_RIGHT', size: w.size, opacity: w.opacity, stamp: 0,
    font: w.font.toUpperCase(), color: colorToInt(w.color), look: w.look.toUpperCase(), weight: w.weight, round: w.round ?? 50,
  }
  // Only the web has the picture itself (the app keeps one logo file of its own).
  if (w.image) o.imageData = w.image
  return JSON.stringify(o)
}

function watermarkFromJson(raw: unknown): Watermark | undefined {
  let o: any
  try { o = typeof raw === 'string' ? JSON.parse(raw) : raw } catch { return undefined }
  if (!o || typeof o !== 'object') return undefined
  const k = POS_NAMES.indexOf(String(o.corner))
  const font = typeof o.font === 'string' ? o.font.toLowerCase() : 'rubik'
  const look = typeof o.look === 'string' ? o.look.toLowerCase() : 'shadow'
  return {
    text: typeof o.text === 'string' ? o.text : '',
    image: typeof o.imageData === 'string' ? o.imageData : null,
    position: (k >= 0 ? POSITIONS[k] : 'br') as WatermarkPosition,
    size: typeof o.size === 'number' ? o.size : 1,
    opacity: typeof o.opacity === 'number' ? o.opacity : 0.8,
    color: typeof o.color === 'number' ? intToColor(o.color).slice(0, 7) : '#ffffff',
    look: (['plain', 'shadow', 'outline', 'box'].includes(look) ? look : 'shadow') as WatermarkLook,
    font: SUB_FONTS.some((f) => f.id === font) ? (font as Watermark['font']) : 'rubik',
    weight: typeof o.weight === 'number' ? o.weight : 0,
    round: typeof o.round === 'number' ? o.round : undefined,
  }
}

/** The template as JSON, in the Android app's shape (plus the web's extra keys). */
export function templateToJson(t: Template): Record<string, unknown> {
  const o: Record<string, unknown> = { v: 1, id: t.id, name: t.name, group: t.group }
  if (t.blur) {
    const b = t.blur
    const j: Record<string, unknown> = {}
    if (b.none !== undefined) j.none = b.none
    if (b.mode) j.mode = MODE_OUT[b.mode]
    if (b.strength !== undefined) j.strength = b.strength
    if (b.padding !== undefined) j.padding = b.padding
    if (b.sensitive !== undefined) j.sensitive = b.sensitive
    o.blur = j
  }
  if (t.subs) {
    const s = t.subs
    const j: Record<string, unknown> = {}
    if (s.create !== undefined) j.create = s.create
    if (s.model) j.model = s.model
    if (s.language !== undefined) j.language = s.language
    if (s.translate !== undefined) j.translate = s.translate
    if (s.style) j.style = styleToJson(s.style)
    if (s.splitWords !== undefined) j.splitWords = s.splitWords
    if (s.splitChars !== undefined) j.splitChars = s.splitChars
    if (s.burn) j.burn = s.burn
    if (s.saveSrt !== undefined) j.saveSrt = s.saveSrt
    o.subs = j
  }
  if (t.voice) o.voice = { voice: t.voice.voice.toUpperCase(), tone: true, mask: true, denoiseLevel: t.voice.denoise, enhance: t.voice.enhance, loud: t.voice.loud, gainDb: 0 }
  if (t.cuts) o.cuts = { silences: t.cuts.silences, minPauseMs: t.cuts.minPauseMs, fillers: t.cuts.fillers }
  if (t.background) {
    const b = t.background
    o.background = { fill: b.fill.toUpperCase(), key: b.key.toUpperCase(), color: colorToInt(b.color), image: '', tolerance: b.tolerance, ...(b.image ? { imageData: b.image } : {}) }
  }
  if (t.stabilize !== undefined) o.stabilize = t.stabilize
  if (t.export) {
    const e = t.export
    const j: Record<string, unknown> = {}
    if (e.aspect) j.aspect = ASPECT_OUT[e.aspect]
    if (e.shortSide !== undefined) j.shortSide = e.shortSide
    if (e.fps !== undefined) j.fps = e.fps
    if (e.quality) j.quality = QUALITY_OUT[e.quality]
    if (e.codec) j.codec = CODEC_OUT[e.codec]
    if (e.watermark) j.watermark = watermarkToJson(e.watermark)
    o.export = j
  }
  return o
}

const bool = (v: unknown) => (typeof v === 'boolean' ? v : undefined)
const int = (v: unknown) => (typeof v === 'number' && isFinite(v) ? Math.round(v) : undefined)
const float = (v: unknown) => (typeof v === 'number' && isFinite(v) ? v : undefined)

/** A template from its JSON (the app's shape); null if [o] isn't one. */
export function templateFromJson(o: any): Template | null {
  if (!o || typeof o !== 'object' || typeof o.id !== 'string' || !o.id) return null
  const t: Template = { id: o.id, name: typeof o.name === 'string' ? o.name : '', group: o.group === 'LOOK' ? 'LOOK' : 'USER', builtIn: false }
  if (o.blur && typeof o.blur === 'object') {
    t.blur = { none: bool(o.blur.none), mode: MODE_IN[o.blur.mode], strength: float(o.blur.strength), padding: float(o.blur.padding), sensitive: bool(o.blur.sensitive) }
  }
  if (o.subs && typeof o.subs === 'object') {
    const s = o.subs
    t.subs = {
      create: bool(s.create), model: s.model === 'HEBREW' || s.model === 'MULTILINGUAL' ? s.model : undefined,
      language: typeof s.language === 'string' ? s.language : undefined, translate: bool(s.translate),
      style: s.style !== undefined ? styleFromJson(s.style) : undefined, splitWords: int(s.splitWords), splitChars: int(s.splitChars),
      burn: ['NONE', 'ORIGINAL', 'TRANSLATED', 'BOTH'].includes(s.burn) ? s.burn : undefined, saveSrt: bool(s.saveSrt),
    }
  }
  if (o.voice && typeof o.voice === 'object') {
    const v = String(o.voice.voice ?? 'NONE').toLowerCase()
    t.voice = {
      voice: (['none', 'deep', 'high', 'robot', 'disguise', 'mute'].includes(v) ? v : 'none') as VoiceFx,
      denoise: Math.min(3, Math.max(0, int(o.voice.denoiseLevel) ?? (o.voice.denoise ? 2 : 0))), enhance: !!o.voice.enhance, loud: !!o.voice.loud,
    }
  }
  if (o.cuts && typeof o.cuts === 'object') t.cuts = { silences: !!o.cuts.silences, minPauseMs: int(o.cuts.minPauseMs) ?? 800, fillers: !!o.cuts.fillers }
  if (o.background && typeof o.background === 'object') {
    const b = o.background
    const fill = String(b.fill ?? 'NONE').toLowerCase()
    const key = String(b.key ?? 'PERSON').toLowerCase()
    t.background = {
      fill: (['none', 'blur', 'color', 'image'].includes(fill) ? fill : 'none') as BgFill,
      key: (['person', 'green', 'blue'].includes(key) ? key : 'person') as BgKey,
      color: typeof b.color === 'number' ? intToColor(b.color).slice(0, 7) : NO_BACKGROUND.color,
      tolerance: float(b.tolerance) ?? 0.5,
      image: typeof b.imageData === 'string' ? b.imageData : null,
    }
    // A picture that only the app has is gone here.
    if (t.background.fill === 'image' && !t.background.image) t.background = { ...t.background, fill: 'none' }
  }
  const st = int(o.stabilize)
  if (st !== undefined) t.stabilize = Math.min(3, Math.max(0, st))
  if (o.export && typeof o.export === 'object') {
    const e = o.export
    t.export = {
      aspect: ASPECT_IN[e.aspect], shortSide: int(e.shortSide), fps: int(e.fps), quality: QUALITY_IN[e.quality], codec: CODEC_IN[e.codec],
      watermark: e.watermark !== undefined ? watermarkFromJson(e.watermark) : undefined,
    }
  }
  return t
}

/** A copy of [t] with a new id and name, as a user's own. */
export function copyOf(t: Template, id: string, name: string): Template {
  return JSON.parse(JSON.stringify({ ...t, id, name, group: 'USER', builtIn: false }))
}

export { DEFAULT_LOOK }
