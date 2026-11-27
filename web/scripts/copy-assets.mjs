// Copies what the web app shares with the Android app (face and people models from the repo's models/ folder, fonts and sound effects from the app's assets) into public/, so there is one source of truth for each.
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const web = join(dirname(fileURLToPath(import.meta.url)), '..')
const assets = join(web, '..', 'app', 'src', 'main', 'assets')
// The models the Android app downloads at run time (pinned in its model-registry.json), kept outside the APK.
const models = join(web, '..', 'models')
const pub = join(web, 'public')
const copy = (from, to) => {
  mkdirSync(dirname(to), { recursive: true })
  cpSync(from, to, { recursive: true })
}
// The face model in float32 and float16 (see scripts/model_web.py).
const model = join(pub, 'models', 'retinaface_fp16.onnx')
const src = join(models, 'retinaface_mv2.onnx')
if (!existsSync(model) || statSync(model).mtimeMs < statSync(src).mtimeMs || !existsSync(join(pub, 'models', 'retinaface_fp32.onnx'))) {
  execFileSync('python3', [join(web, 'scripts', 'model_web.py'), src, join(pub, 'models')], { stdio: 'inherit' })
}
// YuNet (OpenCV Zoo, 2023mar; float32, 230 KB), the second detector, as the app's own file (one source of truth, nothing fetched from elsewhere at run time).
{
  const yunet = join(models, 'face_detection_yunet_2023mar.onnx')
  const out = join(pub, 'models', 'yunet.onnx')
  if (!existsSync(out) || statSync(out).mtimeMs < statSync(yunet).mtimeMs) copy(yunet, out)
}
// BlazeFace full range (MediaPipe, Apache-2.0), selfie mode's detector, converted from MediaPipe's TFLite model to ONNX (scripts/blazeface_to_onnx.py; float32, 2 MB).
{
  const blaze = join(models, 'blazeface_full_range.onnx')
  const out = join(pub, 'models', 'blazeface_full_range.onnx')
  if (!existsSync(out) || statSync(out).mtimeMs < statSync(blaze).mtimeMs) copy(blaze, out)
}
// Every subtitle font (all under the SIL Open Font License, its text beside them).
for (const f of readdirSync(join(assets, 'fonts'))) if (f.endsWith('.ttf')) copy(join(assets, 'fonts', f), join(pub, 'fonts', f))
copy(join(assets, 'fonts', 'licenses'), join(pub, 'fonts', 'licenses'))
// Allo with its roundness set (the canvas can't set a font's axes; weight stays variable), as the
// app's roundness slider: 0, 25, 75 and 100 (50 is the font's own).
{
  const allo = join(assets, 'fonts', 'allo_hebrew.ttf')
  const outs = [0, 25, 75, 100].map((r) => [r, join(pub, 'fonts', `allo_hebrew_r${r}.ttf`)])
  if (outs.some(([, f]) => !existsSync(f) || statSync(f).mtimeMs < statSync(allo).mtimeMs)) {
    const py = `
import sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
for arg in sys.argv[2:]:
    r, out = arg.split('=', 1)
    instantiateVariableFont(TTFont(sys.argv[1]), {'ROND': int(r)}).save(out)
`
    execFileSync('python3', ['-c', py, allo, ...outs.map(([r, f]) => `${r}=${f}`)], { stdio: 'inherit' })
  }
}
// The speaker model (WeSpeaker ResNet34, CC BY 4.0), as the Android app downloads it, float32.
const speaker = join(pub, 'models', 'speaker.onnx')
if (!existsSync(speaker)) {
  const src16 = join(pub, 'models', 'speaker16.onnx')
  const url = 'https://raw.githubusercontent.com/EladKaufman105/face-blur/bb8e2afa2a37a3db68956504d149ad0c616689cf/speaker-wespeaker-r34lm-fp16.onnx'
  try {
    execFileSync('curl', ['-sSfL', '--retry', '3', '-o', src16, url])
    execFileSync('python3', [join(web, 'scripts', 'model_web.py'), src16, join(pub, 'models'), 'speaker', '--fp32-only'], { stdio: 'inherit' })
    rmSync(src16)
  } catch (e) {
    console.warn('No speaker model (speaker detection will say so):', e.message)
  }
}
// The people model (PP-HumanSeg), for background removal: float32 throughout.
const people = join(pub, 'models', 'people.onnx')
const peopleSrc = join(models, 'human_segmentation_pphumanseg_2023mar.onnx')
if (!existsSync(people) || statSync(people).mtimeMs < statSync(peopleSrc).mtimeMs) {
  execFileSync('python3', [join(web, 'scripts', 'model_web.py'), peopleSrc, join(pub, 'models'), 'people', '--fp32-only'], { stdio: 'inherit' })
}
// Sound effects as AAC (every browser decodes it; Safari not Ogg Vorbis everywhere).
mkdirSync(join(pub, 'sfx'), { recursive: true })
for (const f of readdirSync(join(assets, 'sfx'))) {
  if (!f.endsWith('.ogg')) continue
  const out = join(pub, 'sfx', f.replace(/\.ogg$/, '.m4a'))
  if (existsSync(out) && statSync(out).mtimeMs >= statSync(join(assets, 'sfx', f)).mtimeMs) continue
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', join(assets, 'sfx', f), '-c:a', 'aac', '-b:a', '96k', '-movflags', '+faststart', out])
}
copy(join(assets, 'sfx', 'CREDITS.txt'), join(pub, 'sfx', 'CREDITS.txt'))
