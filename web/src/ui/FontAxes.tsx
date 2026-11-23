import { t } from '../i18n'
import { fontWeight, isRounded, snapRound, SUB_FONTS, type FontChoice } from '../lib/substyle'

/** A variable font's own controls, as the app's: its weight, and Allo's roundness. */
export function FontAxes({ choice, onChange }: { choice: FontChoice; onChange: (weight: number, round: number) => void }) {
  if (choice.customFont) return null
  const f = SUB_FONTS.find((x) => x.id === choice.font)
  if (!f?.weight) return null
  const w = fontWeight(choice)
  const r = snapRound(choice.round)
  return (
    <div class="font-axes">
      <div class="slider-row">
        <label>{t('עובי')}</label>
        <div class="axis">
          <input type="range" class="weight-slider" min={f.min ?? 100} max={f.max ?? 900} step={50} value={w} onInput={(e) => onChange(Number((e.currentTarget as HTMLInputElement).value), r)} />
          <span class="axis-value small muted" dir="ltr">{w}</span>
        </div>
      </div>
      {isRounded(choice.font) && (
        <div class="slider-row">
          <label>{t('עיגול')}</label>
          <div class="axis">
            <input type="range" class="round-slider" min={0} max={100} step={25} value={r} onInput={(e) => onChange(w, Number((e.currentTarget as HTMLInputElement).value))} />
            <span class="axis-value small muted" dir="ltr">{r}%</span>
          </div>
        </div>
      )}
    </div>
  )
}
