// The data behind the "Open-source licences" screen: the models' licences (the Android app's
// model-registry.json, copied to licences/ by scripts/copy-assets.mjs), the libraries' and fonts'
// (licences/components.json, from shared/assets/licences, shared with the app) and the texts. All of it is
// served by this site, so the screen and the full texts work offline. Pure parsing, so a Node test can import it.

/** One licence, as in the registry and components.json (the Android app's LicenceInfo). */
export type Licence = {
  title: string
  licence: string
  copyright: string
  source: string
  /** A path under the site's root (licences/MIT.txt, fonts/licenses/OFL-heebo.txt); '' when there is no bundled text. */
  licenceFile: string
  note: string
  /** Why a field couldn't be confirmed upstream: for developers, never shown. */
  unverified: string
}

export type LicenceEntry = { id: string; kind: 'model' | 'library' | 'font'; info: Licence }

/** The registry's files the web app also runs (the GPU TFLite files, LR-ASPP and the ggml speech models are the Android app's only). */
export const WEB_MODEL_FILES = new Set([
  'retinaface_mv2.onnx',
  'face_detection_yunet_2023mar.onnx',
  'human_segmentation_pphumanseg_2023mar.onnx',
  'speaker-wespeaker-r34lm-fp16.onnx',
  'blaze_face_full_range.tflite',
])

type RegistryFile = { name: string; title?: string; licence?: string; copyright?: string; source?: string; licenceFile?: string; note?: string; unverified?: string }
type Registry = { packs: { id: string; files: RegistryFile[] }[] }
type Component = { id: string; name: string; kind: string; used?: string; licence: string; copyright?: string; source?: string; licenceFile?: string; note?: string; unverified?: string }

const join = (a: string, b: string, sep: string) => (!b || b === a ? a : !a ? b : a + sep + b)

/** The models the web app runs, one entry per set of files with the same terms and copyright (as the app's LicenceText.group). */
export function modelEntries(reg: Registry): LicenceEntry[] {
  const out: LicenceEntry[] = []
  for (const p of reg.packs) {
    const groups = new Map<string, LicenceEntry>()
    for (const f of p.files) {
      if (!WEB_MODEL_FILES.has(f.name) || !f.licence) continue
      const key = [f.licence, f.copyright ?? '', f.licenceFile ?? ''].join('\n')
      const prev = groups.get(key)
      if (prev) {
        prev.info.note = join(prev.info.note, f.note ?? '', ' ')
        continue
      }
      groups.set(key, {
        id: `${p.id}-${groups.size}`,
        kind: 'model',
        info: { title: f.title ?? f.name, licence: f.licence, copyright: f.copyright ?? '', source: f.source ?? '', licenceFile: f.licenceFile ?? '', note: f.note ?? '', unverified: f.unverified ?? '' },
      })
    }
    out.push(...groups.values())
  }
  return out
}

/** The libraries, fonts and other models used on the web. */
export function componentEntries(json: { components: Component[] }): LicenceEntry[] {
  return json.components
    .filter((c) => !c.used || c.used.includes('web'))
    .map((c) => ({
      id: c.id,
      kind: (c.kind === 'font' || c.kind === 'library' ? c.kind : 'model') as LicenceEntry['kind'],
      info: { title: c.name, licence: c.licence, copyright: c.copyright ?? '', source: c.source ?? '', licenceFile: c.licenceFile ?? '', note: c.note ?? '', unverified: c.unverified ?? '' },
    }))
}

/** What the screen lists: models (the registry's, then other models), libraries, fonts. */
export async function loadEntries(): Promise<LicenceEntry[]> {
  const [reg, comps] = await Promise.all([get<Registry>('licences/model-registry.json'), get<{ components: Component[] }>('licences/components.json')])
  const c = componentEntries(comps)
  return [...modelEntries(reg), ...c.filter((e) => e.kind === 'model'), ...c.filter((e) => e.kind === 'library'), ...c.filter((e) => e.kind === 'font')]
}

async function get<T>(path: string): Promise<T> {
  const r = await fetch(new URL(path, document.baseURI))
  if (!r.ok) throw new Error(`${path}: ${r.status}`)
  return r.json()
}

/** The full licence text, from this site. Rejects when it isn't there. */
export async function loadText(file: string): Promise<string> {
  const r = await fetch(new URL(file, document.baseURI))
  if (!r.ok) throw new Error(`${file}: ${r.status}`)
  return r.text()
}
