import { memo } from 'preact/compat'
import { useEffect, useRef, useState } from 'preact/hooks'
import { t } from '../i18n'
import { deleteFont, listFonts, saveFont } from '../lib/projects'
import { FontAxes } from './FontAxes'
import { MAX_SPEAKERS, speakerColor, withSpeakerColor } from '../lib/speakers'
import { ACCENT_EFFECTS, DEFAULT_SUB_STYLE, loadSubFont, SUB_COLORS, SUB_EFFECTS, SUB_FONTS, type SubStyle } from '../lib/substyle'
import { SubPresetRow } from './SubPresets'
import { EffectCards, FontCard } from './StyleCards'

const BOX_COLORS = ['#000000b8', '#000000e6', '#fffffff2', '#ff2d55e6', '#4d8dffe6', '#34e07ae6']
const POSITIONS: [number, string][] = [[0.14, 'למעלה'], [0.5, 'באמצע'], [0.7, 'שליש תחתון'], [0.86, 'למטה']]

/** Whether [family] has Hebrew letters: they measure apart from the fallback's (letters only, no space, which every font has). */
function hasHebrew(family: string): boolean {
  const c = document.createElement('canvas').getContext('2d')!
  const letters = 'אבגדהוזחטיכלמנסעפצקרשת'
  c.font = `40px monospace`
  const a = c.measureText(letters).width
  c.font = `40px ${family}, monospace`
  return c.measureText(letters).width !== a
}

/**
 * The subtitles' design, as the app's style panel: ready-made looks, the font (bundled or one
 * imported), its weight, the effect, colors, outline, shadow, glow, a box, size and place.
 */
function StyleCard({ style, onStyle, file, part }: { style: SubStyle; onStyle: (s: SubStyle) => void; file?: File | null; part?: string }) {
  // On a phone one tab of it at a time (as the app's style panel); on a wide screen everything.
  const P = (k: string) => !part || part === k
  const set = (patch: Partial<SubStyle>) => onStyle({ ...style, ...patch })
  const [custom, setCustom] = useState<string[]>([])
  const [note, setNote] = useState<string | null>(null)
  const [speaker, setSpeaker] = useState(2)
  const input = useRef<HTMLInputElement>(null)
  // The imported fonts' names are listed at once (they are the user's own, small); each card loads its font when it is near the screen.
  useEffect(() => { listFonts().then((list) => setCustom(list.map((f) => f.name))) }, [])
  const outline = style.outline <= 0 ? 0 : style.outline < 0.13 ? 1 : 2
  return (
    <section class="card sub-style">
      {!part && <h3 class="section-title">{t('עיצוב כתוביות')}</h3>}

      {P('looks') && <SubPresetRow style={style} onStyle={onStyle} file={file ?? null} />}

      {(() => { const Fine = (part ? 'div' : 'details') as 'details'; return (
      <Fine class="sub-fine">
      {!part && <summary>{t('כוונון ידני')}</summary>}

{P('font') && <>
      <h4 class="caption tab-name">{t('גופן')}</h4>
      {custom.length > 0 && <h4 class="caption">{t('הגופנים שלי')}</h4>}
      {custom.length > 0 && (
        <div class="cards fonts custom-fonts" role="radiogroup" aria-label={t('הגופנים שלי')}>
          {custom.map((name) => (
            <FontCard key={name} choice={{ ...DEFAULT_SUB_STYLE, customFont: name }} label={name} on={style.customFont === name} onPick={() => set({ customFont: name })}
              onDelete={async () => { await deleteFont(name); setCustom(custom.filter((x) => x !== name)); if (style.customFont === name) set({ customFont: null }) }} />
          ))}
        </div>
      )}
      {custom.length > 0 && <h4 class="caption">{t('גופנים מובנים')}</h4>}
      <div class="cards fonts builtin-fonts" role="radiogroup" aria-label={t('גופן')}>
        {SUB_FONTS.map((f) => (
          <FontCard key={f.id} choice={{ ...DEFAULT_SUB_STYLE, font: f.id }} label={f.id === 'system' ? t('של המכשיר') : f.label} on={!style.customFont && style.font === f.id} onPick={() => set({ font: f.id, customFont: null, weight: 0, round: undefined })} />
        ))}
      </div>
      <button class="btn ghost small-btn font-import" onClick={() => input.current?.click()}>{t('ייבוא גופן מהמכשיר (TTF / OTF)')}</button>
      <input ref={input} type="file" accept=".ttf,.otf,font/ttf,font/otf" hidden onChange={async (e) => {
        const f = (e.currentTarget as HTMLInputElement).files?.[0]
        ;(e.currentTarget as HTMLInputElement).value = ''
        if (!f) return
        const name = f.name.replace(/\.[^.]+$/, '')
        const data = await f.arrayBuffer()
        const ok = await loadSubFont({ ...DEFAULT_SUB_STYLE, customFont: name }, document.fonts, document.baseURI, data.slice(0))
        if (!ok) { setNote(t('לא הצלחתי לייבא: צריך קובץ גופן TTF או OTF.')); return }
        await saveFont(name, data).catch(() => {})
        setCustom((c) => [...c.filter((x) => x !== name), name])
        set({ customFont: name })
        setNote(hasHebrew(`'ONIT custom ${name}'`) ? t('הגופן נוסף.') : t('הגופן נוסף, אבל אין בו אותיות עבריות: טקסט בעברית יוצג בגופן אחר.'))
      }} />
      {note && <p class="small muted" style="margin:0">{note}</p>}
      <FontAxes choice={style} onChange={(weight, round) => set({ weight, round })} />

</>}
{P('effect') && <>
      <h4 class="caption tab-name">{t('אפקט')}</h4>
      <EffectCards style={style} effects={SUB_EFFECTS} onPick={(e) => set({ effect: e })} />

</>}
{P('color') && <>
      <h4 class="caption">{t('צבע הטקסט')}</h4>
      <div class="swatches">
        {SUB_COLORS.map((c) => <button class={'swatch' + (style.color === c ? ' on' : '')} style={{ background: c }} aria-label={c} onClick={() => set({ color: c })} />)}
      </div>
      {ACCENT_EFFECTS.includes(style.effect) && (
        <>
          <h4 class="caption">{t('צבע ההדגשה')}</h4>
          <div class="swatches">
            {SUB_COLORS.map((c) => <button class={'swatch' + (style.accent === c ? ' on' : '')} style={{ background: c }} aria-label={c} onClick={() => set({ accent: c })} />)}
          </div>
        </>
      )}

      <h4 class="caption">{t('קו מתאר')}</h4>
      <div class="seg outline" role="radiogroup">
        {[t('בלי'), t('דק'), t('עבה')].map((label, k) => (
          <button class={outline === k ? 'on' : ''} role="radio" onClick={() => set({ outline: [0, 0.1, 0.18][k] })}>{label}</button>
        ))}
      </div>
      <div class="row wrap">
        <label class="check"><input type="checkbox" checked={style.shadow} onChange={(e) => set({ shadow: (e.currentTarget as HTMLInputElement).checked })} /> {t('צל')}</label>
        <label class="check"><input type="checkbox" checked={style.glow} onChange={(e) => set({ glow: (e.currentTarget as HTMLInputElement).checked })} /> {t('זוהר')}</label>
        <label class="check"><input type="checkbox" class="box-check" checked={style.box} onChange={(e) => set({ box: (e.currentTarget as HTMLInputElement).checked })} /> {t('רקע')}</label>
      </div>
      {style.box && (
        <>
          <h4 class="caption">{t('צבע הרקע')}</h4>
          <div class="swatches">
            {BOX_COLORS.map((c) => <button class={'swatch' + (style.boxColor === c ? ' on' : '')} style={{ background: c }} aria-label={c} onClick={() => set({ boxColor: c })} />)}
          </div>
        </>
      )}

      <h4 class="caption">{t('צבעי דוברים')}</h4>
      <div class="row speaker-colors">
        {Array.from({ length: MAX_SPEAKERS }, (_, k) => k + 1).map((n) => (
          <button class={'speaker-dot pick' + (n === speaker ? ' on' : '')} disabled={n === 1} style={{ background: speakerColor(style.color, n, style.speakerColors) }} aria-label={t('דובר %d', n)} title={t('דובר %d', n)} onClick={() => setSpeaker(n)}>{n}</button>
        ))}
      </div>
      <div class="swatches speaker-swatches">
        {SUB_COLORS.filter((c) => c !== style.color).map((c) => (
          <button class={'swatch' + (speakerColor(style.color, speaker, style.speakerColors) === c ? ' on' : '')} style={{ background: c }} aria-label={c} onClick={() => set({ speakerColors: withSpeakerColor(style.color, style.speakerColors, speaker, c) })} />
        ))}
      </div>

</>}
{P('layout') && <>
      <h4 class="caption tab-name">{t('גודל ומיקום')}</h4>
      <div class="slider-row">
        <label>{t('גודל')}</label>
        <input type="range" min={0.6} max={2.2} step={0.05} value={style.size} onInput={(e) => set({ size: Number((e.currentTarget as HTMLInputElement).value) })} />
      </div>
      <div class="seg seg4 positions-seg" role="radiogroup">
        {POSITIONS.map(([v, label]) => (
          <button class={Math.abs(style.position - v) < 0.03 ? 'on' : ''} role="radio" onClick={() => set({ position: v })}>{t(label)}</button>
        ))}
      </div>
      <div class="slider-row">
        <label>{t('גובה מדויק')}</label>
        <input type="range" min={0.05} max={0.95} step={0.01} value={style.position} onInput={(e) => set({ position: Number((e.currentTarget as HTMLInputElement).value) })} />
      </div>
</>}
      </Fine>
      ) })()}
    </section>
  )
}

/** Not drawn again with every frame of the playing video: only when the style or the file changes. */
export const SubStyleCard = memo(StyleCard)
