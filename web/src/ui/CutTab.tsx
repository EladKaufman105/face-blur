import { useState } from 'preact/hooks'
import { t, duration } from '../i18n'
import { addCut, CUT_JOIN, covers, cutAt, cutTotal, uncut, type Range } from '../lib/cutmap'
import { cueWi, type Cue, type Word } from '../lib/subtitles'
import { CutIcon } from './icons'

/** A spoken word's stretch (a word without a length gets the app's 80 ms). */
import { wordSpan } from '../lib/subtitles'
export { wordSpan }

/** The recognized words a subtitle shows. */
export function cueWords(c: Cue, words: Word[]): Word[] {
  return cueWi(c).map((k) => words[k]).filter((w): w is Word => !!w)
}

type Props = {
  cuts: Range[]
  onCuts: (r: Range[]) => void
  duration: number
  time: number
  cues: Cue[]
  words: Word[]
  cutStart: number | null
  onCutStart: (at: number | null) => void
  busy: boolean
  onSilences: (minPause: number) => void
  onFillers: () => void
  onMakeSubtitles: () => void
  onPause: () => void
}

/**
 * Cutting stretches out of the video, as in the app: pauses and filler sounds automatically, a
 * stretch by hand, or words and sentences by tapping them in the text. Nothing is lost: every
 * cut can be put back, and the player jumps over them.
 */
export function CutTab(p: Props) {
  const [pause, setPause] = useState(0.8)
  const total = cutTotal(p.cuts)
  const here = cutAt(p.cuts, p.time)
  const toggle = ([a, b]: Range) => p.onCuts(covers(p.cuts, a, b) ? uncut(p.cuts, a, b) : addCut(p.cuts, a, b, CUT_JOIN))
  const cueSpan = (c: Cue): Range => {
    const ws = cueWords(c, p.words)
    const a = ws.length ? ws[0].start : c.start
    return [a, Math.max(ws.length ? wordSpan(ws[ws.length - 1])[1] : c.end, a + 0.001)]
  }
  return (
    <section class="card cut" data-total={total.toFixed(3)}>
      <h3 class="section-title">{t('חיתוך')}</h3>
      <p class="small" style="margin:0">
        {p.cuts.length === 0
          ? t('עדיין לא נחתך כלום. חיתוך לא מוחק מהמקור, ואפשר להחזיר כל קטע.')
          : t('נחתכו %d קטעים · הסרטון %s במקום %s', p.cuts.length, duration(p.duration - total), duration(p.duration))}
      </p>

      <h4 class="caption">{t('אוטומטי')}</h4>
      <div class="row wrap">
        <span class="small">{t('שתיקה מעל')}</span>
        {[0.5, 0.8, 1.2].map((s) => (
          <button class={'chip' + (pause === s ? ' on' : '')} onClick={() => setPause(s)}><bdi dir="ltr">{s.toFixed(1)}</bdi> {t('שנ׳')}</button>
        ))}
      </div>
      <div class="row wrap">
        <button class="btn primary cut-silences" disabled={p.busy} onClick={() => p.onSilences(pause)}>{t('הסר שתיקות')}</button>
        <button class="btn tonal" disabled={p.busy || p.words.length === 0} onClick={p.onFillers}>{t('הסר "אממ" ו"אה"')}</button>
        {p.busy && <span class="spinner" aria-label={t('מחפש…')} />}
      </div>

      <h4 class="caption">{t('ידני')}</h4>
      <div class="row wrap">
        {p.cutStart === null ? (
          <button class="btn tonal cut-start" onClick={() => { p.onPause(); p.onCutStart(p.time) }}>{t('התחל חיתוך כאן')}</button>
        ) : (
          <>
            <button class="btn primary cut-end" onClick={() => {
              const a = Math.min(p.cutStart!, p.time), b = Math.max(p.cutStart!, p.time)
              p.onCutStart(null)
              if (b - a >= 0.04) p.onCuts(addCut(p.cuts, a, b))
            }}>{t('סיים כאן (%s שנ׳)', Math.abs(p.time - p.cutStart).toFixed(1))}</button>
            <button class="btn ghost" onClick={() => p.onCutStart(null)}>{t('ביטול')}</button>
          </>
        )}
      </div>
      {p.cutStart !== null && <p class="small muted" style="margin:0">{t('הזז את ציר הזמן לסוף הקטע ולחץ "סיים כאן".')}</p>}
      {(here || p.cuts.length > 0) && (
        <div class="row wrap">
          {here && <button class="btn ghost" onClick={() => p.onCuts(uncut(p.cuts, here[0], here[1]))}>{t('החזר את הקטע כאן')}</button>}
          {p.cuts.length > 0 && <button class="btn ghost cut-clear" onClick={() => p.onCuts([])}>{t('החזר הכל')}</button>}
        </div>
      )}

      <hr />
      <h4 class="caption">{t('לפי הטקסט')}</h4>
      {p.cues.length === 0 ? (
        <>
          <p class="small muted" style="margin:0">{t('כדי לחתוך לפי הטקסט צריך קודם כתוביות: הן מסמנות מתי כל מילה נאמרת.')}</p>
          <button class="btn tonal" onClick={p.onMakeSubtitles}>{t('צור כתוביות')}</button>
        </>
      ) : (
        <>
          <p class="small muted" style="margin:0">{t('הקש על מילה כדי לחתוך אותה מהסרטון, ושוב כדי להחזיר. המספריים חותכים משפט שלם.')}</p>
          <div class="cut-cues">
            {[...p.cues].sort((a, b) => a.start - b.start).map((c) => {
              const ws = cueWords(c, p.words)
              const span = cueSpan(c)
              const all = covers(p.cuts, span[0], span[1])
              return (
                <div class="cut-cue">
                  <button class={'icon-btn' + (all ? ' danger' : '')} aria-label={t('חתוך את כל המשפט')} title={t('חתוך את כל המשפט')} onClick={() => toggle(span)}><CutIcon size={19} /></button>
                  <div class="word-chips" dir="auto">
                    {ws.length > 0
                      ? ws.map((w) => {
                          const s = wordSpan(w)
                          return <button class={'word' + (covers(p.cuts, s[0], s[1]) ? ' on' : '')} onClick={() => toggle(s)}>{w.text.trim()}</button>
                        })
                      : <button class={'word' + (all ? ' on' : '')} onClick={() => toggle(span)}>{c.text}</button>}
                  </div>
                </div>
              )
            })}
          </div>
        </>
      )}
    </section>
  )
}
