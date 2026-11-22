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
// The face model, with its float16 weights widened to float32 (ONNX Runtime doesn't mix them).
const model = join(pub, 'models', 'retinaface_mv2.onnx')
const src = join(assets, 'retinaface_mv2.onnx')
if (!existsSync(model) || statSync(model).mtimeMs < statSync(src).mtimeMs) {
  mkdirSync(dirname(model), { recursive: true })
  execFileSync('python3', [join(web, 'scripts', 'model_fp32.py'), src, model], { stdio: 'inherit' })
}
copy(join(assets, 'fonts', 'allo_hebrew.ttf'), join(pub, 'fonts', 'allo_hebrew.ttf'))
copy(join(assets, 'sfx'), join(pub, 'sfx'))
