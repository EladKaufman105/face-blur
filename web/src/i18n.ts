import { signal } from '@preact/signals'

// Hebrew first (the strings are the keys), English on request, as in the Android app.
export type Lang = 'he' | 'en'

function initial(): Lang {
  try {
    const saved = localStorage.getItem('onit.lang')
    if (saved === 'he' || saved === 'en') return saved
  } catch {}
  return navigator.language?.startsWith('he') || !navigator.language ? 'he' : 'en'
}

export const lang = signal<Lang>(initial())

export function setLang(l: Lang) {
  lang.value = l
  try { localStorage.setItem('onit.lang', l) } catch {}
  document.documentElement.lang = l
  document.documentElement.dir = l === 'he' ? 'rtl' : 'ltr'
}

const EN: Record<string, string> = {
  'הכל מעובד על המכשיר. שום דבר לא עולה לרשת.': 'Everything runs on your device. Nothing is uploaded.',
  'טשטוש פנים בסרטון': 'Blur faces in a video',
  'הפנים מזוהות ומטושטשות אוטומטית. אפשר לבדוק ולתקן לפני השמירה.': 'Faces are found and blurred automatically. Check and fix before saving.',
  'בחר סרטון': 'Choose a video',
  'או גרור סרטון לכאן': 'or drop a video here',
  'זיהוי מורחב': 'Extended detection',
  'מוצא יותר פנים קטנות ורחוקות, עם יותר טשטושים מיותרים.': 'Finds more small and distant faces, with more needless blurs.',
  'מזהה פנים': 'Finding faces',
  'מכין את המודל…': 'Preparing the model…',
  'ביטול': 'Cancel',
  'פנים בפריים': 'faces in frame',
  'נותרו כ-%s': 'About %s left',
  'עריכה': 'Edit',
  'ייצוא': 'Export',
  'פנים': 'Faces',
  'הכל': 'All',
  'אף אחד': 'None',
  'הקש על פנים כדי לבטל או להחזיר את הטשטוש שלהן.': 'Tap a face to remove or bring back its blur.',
  'לא נמצאו פנים בסרטון.': 'No faces found in this video.',
  'מראה': 'Look',
  'טשטוש': 'Blur',
  'פיקסלים': 'Pixels',
  'כיסוי': 'Cover',
  'עוצמה': 'Strength',
  'גודל': 'Size',
  'נגן': 'Play',
  'השהה': 'Pause',
  'מייצא': 'Exporting',
  'הסרטון מוכן': 'Your video is ready',
  'שמור': 'Save',
  'שתף': 'Share',
  'חזרה לעריכה': 'Back to editing',
  'סרטון חדש': 'New video',
  'לא ניתן לפתוח את הסרטון': "Can't open the video",
  'הדפדפן לא מצליח לפענח את הסרטון הזה (%s). נסה Chrome או Safari מעודכנים.': "This browser can't decode the video (%s). Try an up-to-date Chrome or Safari.",
  'הדפדפן הזה לא תומך בעיבוד וידאו (WebCodecs). נסה Chrome, Edge או Safari מעודכנים.': "This browser doesn't support video processing (WebCodecs). Try an up-to-date Chrome, Edge or Safari.",
  'משהו השתבש': 'Something went wrong',
  'נסה שוב': 'Try again',
  'הזיהוי רץ על %s': 'Detection runs on %s',
  'המעבד הגרפי': 'the GPU',
  'המעבד': 'the CPU',
  'אל תסגור את הלשונית עד הסיום.': "Don't close this tab until it's done.",
  'בטלפון: השאר את המסך פתוח עד הסיום.': 'On a phone: keep the screen on until it finishes.',
  'גרסת ווב': 'Web version',
  'האפליקציה המלאה לאנדרואיד': 'The full Android app',
  'פנים %d': 'Face %d',
  'מטושטש': 'Blurred',
  'גלוי': 'Visible',
  '%d מתוך %d מטושטשות': '%d of %d blurred',
  'זמן עיבוד: %s': 'Processing time: %s',
}

export function t(he: string, ...args: (string | number)[]): string {
  let s = lang.value === 'en' ? EN[he] ?? he : he
  for (const a of args) s = s.replace(/%[sd]/, String(a))
  return s
}

export function duration(sec: number): string {
  const s = Math.max(0, Math.round(sec))
  const m = Math.floor(s / 60)
  return `${m}:${String(s % 60).padStart(2, '0')}`
}
