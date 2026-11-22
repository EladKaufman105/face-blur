import { useRef, useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { previewSfx, SFX_GROUPS, SFX_NAMES, type SfxClip } from '../lib/sfx'
import { NO_WATERMARK, POSITIONS, type Watermark, type WatermarkLook } from '../lib/watermark'
import { CloseIcon, PlayIcon } from './icons'

let nextClip = 1

/** The sound effects: pick a group, ▶ to hear, tap to add at the playhead; the added ones below. */
export function SoundTab({ clips, time, onChange, onSeek }: { clips: SfxClip[]; time: number; onChange: (c: SfxClip[]) => void; onSeek: (at: number) => void }) {
  const [group, setGroup] = useState(0)
  const add = (sound: string) => onChange([...clips, { id: nextClip++, sound, at: time, gain: 1 }].sort((a, b) => a.at - b.at))
  const update = (id: number, patch: Partial<SfxClip>) => onChange(clips.map((c) => (c.id === id ? { ...c, ...patch } : c)).sort((a, b) => a.at - b.at))
  return (
    <>
      <section class="card">
        <h3 class="section-title">{t('אפקטים קוליים')}</h3>
        <div class="chips">
          {SFX_GROUPS.map((g, k) => (
            <button class={'chip' + (k === group ? ' on' : '')} onClick={() => setGroup(k)}>{t(g.label)}</button>
          ))}
        </div>
        <p class="small muted" style="margin:10px 0">{t('הקש על צליל כדי להוסיף אותו במיקום הנוכחי. ▶ כדי לשמוע.')}</p>
        <div class="sounds">
          {SFX_GROUPS[group].sounds.map((s) => (
            <div class="sound">
              <button class="play" aria-label={t('השמע')} onClick={() => previewSfx(s.id)}><PlayIcon size={18} /></button>
              <button class="name" onClick={() => add(s.id)}>{t(s.name)}</button>
            </div>
          ))}
        </div>
      </section>
      {clips.length > 0 && (
        <section class="card">
          <h3 class="section-title">{t('בסרטון')}</h3>
          <div class="clips">
            {clips.map((c) => (
              <div class="clip">
                <div class="row">
                  <button class="time-btn" onClick={() => onSeek(c.at)} dir="ltr">{duration(c.at)}.{Math.floor((c.at % 1) * 10)}</button>
                  <strong class="grow">{t(SFX_NAMES.get(c.sound) ?? c.sound)}</strong>
                  <button class="btn ghost small-btn" onClick={() => update(c.id, { at: time })}>{t('העבר לכאן')}</button>
                  <button class="icon-btn" aria-label={t('מחק')} onClick={() => onChange(clips.filter((x) => x.id !== c.id))}><CloseIcon size={18} /></button>
                </div>
                <div class="row small">
                  <span class="muted">{t('עוצמה')}</span>
                  <input type="range" min={0} max={2} step={0.05} value={c.gain} onInput={(e) => update(c.id, { gain: Number((e.currentTarget as HTMLInputElement).value) })} />
                  <span class="muted" dir="ltr" style="min-width:40px">{Math.round(c.gain * 100)}%</span>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  )
}

export function loadWatermark(): Watermark {
  try {
    const w = JSON.parse(localStorage.getItem('onit.watermark') ?? 'null')
    if (w && typeof w.text === 'string') return { ...NO_WATERMARK, ...w, image: null }
  } catch {}
  return NO_WATERMARK
}

const COLORS = ['#ffffff', '#000000', '#ff4d6d', '#ffc53d', '#3ddcff', '#8b6cff']
const LOOKS: [WatermarkLook, string][] = [['plain', 'רגיל'], ['shadow', 'צל'], ['outline', 'קו מתאר'], ['box', 'רקע']]
const ARROWS: Record<string, string> = { tl: '↖', tc: '↑', tr: '↗', cl: '←', c: '•', cr: '→', bl: '↙', bc: '↓', br: '↘' }

/** A line of text or a logo, where and how it shows. */
export function WatermarkTab({ mark, onChange }: { mark: Watermark; onChange: (w: Watermark) => void }) {
  const file = useRef<HTMLInputElement>(null)
  const set = (patch: Partial<Watermark>) => {
    const w = { ...mark, ...patch }
    onChange(w)
    try { localStorage.setItem('onit.watermark', JSON.stringify({ ...w, image: null })) } catch {}
  }
  return (
    <section class="card">
      <h3 class="section-title">{t('סימן מים')}</h3>
      <div class="seg" style="grid-template-columns:1fr 1fr">
        <button class={!mark.image ? 'on' : ''} onClick={() => set({ image: null })}>{t('טקסט')}</button>
        <button class={mark.image ? 'on' : ''} onClick={() => file.current?.click()}>{t('לוגו')}</button>
      </div>
      <input
        ref={file}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => {
          const f = (e.currentTarget as HTMLInputElement).files?.[0]
          if (f) set({ image: URL.createObjectURL(f) })
          ;(e.currentTarget as HTMLInputElement).value = ''
        }}
      />
      {!mark.image && (
        <input class="text-in" type="text" dir="auto" placeholder={t('למשל @שם_הערוץ')} value={mark.text} onInput={(e) => set({ text: (e.currentTarget as HTMLInputElement).value })} />
      )}
      {mark.image && (
        <div class="row" style="margin-top:10px">
          <img src={mark.image} alt="" style="height:44px;max-width:120px;object-fit:contain;border-radius:8px;background:var(--surface-3)" />
          <div class="grow" />
          <button class="btn ghost" onClick={() => file.current?.click()}>{t('החלף תמונה')}</button>
        </div>
      )}
      <div class="mark-grid">
        <div class="positions" dir="ltr">
          {POSITIONS.map((p) => (
            <button class={mark.position === p ? 'on' : ''} aria-label={p} onClick={() => set({ position: p })}>{ARROWS[p]}</button>
          ))}
        </div>
        <div class="grow">
          {!mark.image && (
            <>
              <div class="chips">
                {LOOKS.map(([l, label]) => (
                  <button class={'chip' + (mark.look === l ? ' on' : '')} onClick={() => set({ look: l })}>{t(label)}</button>
                ))}
              </div>
              <div class="swatches">
                {COLORS.map((c) => (
                  <button class={'swatch' + (mark.color === c ? ' on' : '')} style={{ background: c }} aria-label={c} onClick={() => set({ color: c })} />
                ))}
              </div>
            </>
          )}
        </div>
      </div>
      <div class="slider-row">
        <label>{t('גודל')}</label>
        <input type="range" min={0.5} max={3} step={0.05} value={mark.size} onInput={(e) => set({ size: Number((e.currentTarget as HTMLInputElement).value) })} />
      </div>
      <div class="slider-row">
        <label>{t('שקיפות')}</label>
        <input type="range" min={0.2} max={1} step={0.05} value={mark.opacity} onInput={(e) => set({ opacity: Number((e.currentTarget as HTMLInputElement).value) })} />
      </div>
    </section>
  )
}
