// Copies what the web app shares with the Android app (face model, font, sound effects) into public/, so there is one source of truth for each.
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync, statSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const web = join(dirname(fileURLToPath(import.meta.url)), '..')
const assets = join(web, '..', 'app', 'src', 'main', 'assets')
const pub = join(web, 'public')
const copy = (from, to) => {
  mkdirSync(dirname(to), { recursive: true })
  cpSync(from, to, { recursive: true })
}
// The face model in float32 and float16 (see scripts/model_web.py).
const model = join(pub, 'models', 'retinaface_fp16.onnx')
const src = join(assets, 'retinaface_mv2.onnx')
if (!existsSync(model) || statSync(model).mtimeMs < statSync(src).mtimeMs || !existsSync(join(pub, 'models', 'retinaface_fp32.onnx'))) {
  execFileSync('python3', [join(web, 'scripts', 'model_web.py'), src, join(pub, 'models')], { stdio: 'inherit' })
}
copy(join(assets, 'fonts', 'allo_hebrew.ttf'), join(pub, 'fonts', 'allo_hebrew.ttf'))
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
const peopleSrc = join(assets, 'human_segmentation_pphumanseg_2023mar.onnx')
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
