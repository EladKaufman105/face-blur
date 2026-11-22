// Copies what the web app shares with the Android app (face model, font, sound effects) into public/, so there is one source of truth for each.
import { cpSync, existsSync, mkdirSync, statSync } from 'node:fs'
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
copy(join(assets, 'sfx'), join(pub, 'sfx'))
