import { t } from '../i18n'
import type { DetectMode } from '../lib/sensitivity'

/** What is chosen for detection: the mode and the sensitivity (0..1, lib/sensitivity.ts). */
export type DetectChoice = { mode: DetectMode; sensitivity: number }

export const NORMAL_DETECT: DetectChoice = { mode: 'normal', sensitivity: 0 }

/** The detection's name for a summary line: "Normal detection", "Fast detection · Sensitivity 40%"... */
export function detectSummary(d: DetectChoice): string {
  const name = d.mode === 'fast' ? t('זיהוי מהיר') : d.mode === 'selfie' ? t('זיהוי סלפי') : d.mode === 'extended' ? t('זיהוי מורחב') : t('זיהוי רגיל')
  const pct = Math.round(d.sensitivity * 100)
  return pct > 0 ? name + ' · ' + t('רגישות %d%%', pct).replace('%%', '%') : name
}

/**
 * How faces are looked for: fast, normal (the default), extended or selfie, with what the choice means
 * (fast's and selfie's warnings stand out), and the sensitivity, which only ever blurs more than the default.
 * As the app's DetectionControls.
 */
export function DetectControls({ value, onChange, cls }: { value: DetectChoice; onChange: (d: DetectChoice) => void; cls?: string }) {
  const modes: [DetectMode, string][] = [['fast', t('מהיר')], ['normal', t('רגיל')], ['extended', t('מורחב')], ['selfie', t('סלפי')]]
  const pct = Math.round(value.sensitivity * 100)
  return (
    <div class={'detect-controls ' + (cls ?? '')}>
      <h4 class="caption">{t('סוג הזיהוי')}</h4>
      <div class="seg detect-mode" role="radiogroup" aria-label={t('סוג הזיהוי')}>
        {modes.map(([m, label]) => (
          <button class={(value.mode === m ? 'on ' : '') + 'mode-' + m} role="radio" aria-checked={value.mode === m} onClick={() => onChange({ ...value, mode: m })}>{label}</button>
        ))}
      </div>
      {value.mode === 'selfie'
        ? <p class="small selfie-warning fast-warning" role="note">{t('מצב סלפי מהיר מאוד, אבל מיועד לאדם אחד קרוב למצלמה. פנים קטנות או רחוקות ברקע עלולות לא להיטשטש. צפו בתוצאה לפני שמשתפים.')}</p>
        : value.mode === 'fast'
        ? <p class="small fast-warning" role="note">{t('זיהוי מהיר בודק פחות פריימים, ולכן מהיר יותר. הוא עלול לפספס לרגע פנים קטנות, רחוקות או בקצה המסך. כדאי לצפות בתוצאה לפני שמשתפים.')}</p>
        : <p class="small muted detect-desc">{value.mode === 'extended'
          ? t('בודק כל פריים ומטשטש גם פנים שהמערכת פחות בטוחה בהן. מפספס פחות, אבל עלול לטשטש גם דברים מיותרים, ואיטי יותר.')
          : t('בודק כ-15 פריימים בכל שנייה של וידאו. מתאים לרוב הסרטונים.')}</p>}
      <div class="slider-row">
        <label class="row" style="justify-content:space-between">
          <span>{t('רגישות')}</span>
          <span class="small muted sensitivity-value">{pct > 0 ? <bdi dir="ltr">{pct}%</bdi> : t('רגילה')}</span>
        </label>
        {/* Steps of 5%: the same values come back from a template. */}
        <input type="range" class="sensitivity" min={0} max={1} step={0.05} value={value.sensitivity} aria-label={t('רגישות')}
          onInput={(e) => onChange({ ...value, sensitivity: Math.round(Number((e.currentTarget as HTMLInputElement).value) * 20) / 20 })} />
        <div class="range-ends small muted"><span>{t('רגילה')}</span><span>{t('הכי רגישה')}</span></div>
        <p class="small muted detect-desc">{t('רגישות גבוהה יותר מטשטשת גם פנים שהמערכת פחות בטוחה בהן, ולכן עלולה לטשטש גם דברים שאינם פנים. היא אף פעם לא מטשטשת פחות מהרגיל.')}</p>
      </div>
    </div>
  )
}
