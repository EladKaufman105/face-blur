// The sound effects, the same 51 real recordings (CC0) as the Android app, in its groups.
// Files: public/sfx/<id>.m4a (made from the app's Ogg files by scripts/copy-assets.mjs).

export type SfxGroup = { label: string; sounds: { id: string; name: string }[] }

export const SFX_GROUPS: SfxGroup[] = [
  { label: 'מעברים', sounds: [
    { id: 'whoosh', name: 'ווש' },
    { id: 'swoosh', name: 'ווש מהיר' },
    { id: 'whip', name: 'וויפ' },
    { id: 'swipe', name: 'החלקה' },
    { id: 'reverse', name: 'ווש הפוך' },
    { id: 'riser', name: 'עלייה' },
    { id: 'buildup', name: 'בילד-אפ' },
    { id: 'reverse_cymbal', name: 'מצילה הפוכה' },
    { id: 'glitch', name: 'גליץ\'' },
    { id: 'static', name: 'רעש רדיו' },
    { id: 'rewind', name: 'הרצה אחורה' },
  ] },
  { label: 'אימפקט', sounds: [
    { id: 'boom', name: 'בום' },
    { id: 'impact', name: 'פיצוץ' },
    { id: 'bass_drop', name: 'בס דרופ' },
    { id: 'braam', name: 'בראאם' },
    { id: 'drama', name: 'דרמה' },
    { id: 'thump', name: 'חבטה' },
    { id: 'punch', name: 'אגרוף' },
    { id: 'crash', name: 'מצילה' },
    { id: 'zap', name: 'לייזר' },
  ] },
  { label: 'תגובות', sounds: [
    { id: 'laugh', name: 'צחוק' },
    { id: 'cheer', name: 'קהל מריע' },
    { id: 'boo', name: 'בוז' },
    { id: 'applause', name: 'מחיאות כפיים' },
    { id: 'crickets', name: 'צרצרים' },
    { id: 'rimshot', name: 'בדום-טסס' },
    { id: 'fail', name: 'כישלון' },
    { id: 'buzzer', name: 'תשובה שגויה' },
    { id: 'drumroll', name: 'תופים' },
    { id: 'scratch', name: 'שריטת תקליט' },
    { id: 'pop', name: 'פופ' },
    { id: 'bubble', name: 'בועה' },
    { id: 'cork', name: 'פקק' },
    { id: 'poof', name: 'פוף' },
    { id: 'confetti', name: 'קונפטי' },
  ] },
  { label: 'התראות', sounds: [
    { id: 'ding', name: 'דינג' },
    { id: 'bell', name: 'פעמון' },
    { id: 'notify', name: 'התראה' },
    { id: 'sparkle', name: 'נצנוץ' },
    { id: 'tada', name: 'טה-דה' },
    { id: 'levelup', name: 'לבל אפ' },
    { id: 'error', name: 'שגיאה' },
    { id: 'countdown', name: 'ספירה לאחור' },
  ] },
  { label: 'חפצים', sounds: [
    { id: 'click', name: 'קליק' },
    { id: 'typing', name: 'הקלדה' },
    { id: 'camera', name: 'מצלמה' },
    { id: 'page', name: 'דפדוף' },
    { id: 'cash', name: 'קופה' },
    { id: 'coins', name: 'מטבעות' },
    { id: 'snap', name: 'נקישת אצבע' },
    { id: 'heartbeat', name: 'דופק' },
  ] },
]

export const SFX_NAMES = new Map(SFX_GROUPS.flatMap((g) => g.sounds.map((s) => [s.id, s.name] as const)))
