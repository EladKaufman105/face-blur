// Draws a video frame with its faces blurred, pixelated or covered (WebGL2), the same look as
// the Android app: a soft ellipse around each face, blur relative to the face's size.
import type { Look } from './types'

export interface Ellipse {
  /** Pixels, image orientation (origin top-left). */
  cx: number
  cy: number
  rx: number
  ry: number
  alpha: number
}

const MASK_INNER = 0.7
const MASK_OUTER = 1.15
const SLOT = 128

const VS = `#version 300 es
in vec2 aPos;
out vec2 vUV;
void main() { vUV = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }`

// Full frame copy: canvas pixel -> source texel (the texture's row 0 is the image's top).
const COPY_FS = `#version 300 es
precision highp float;
in vec2 vUV;
uniform sampler2D uSrc;
out vec4 o;
void main() { o = texture(uSrc, vec2(vUV.x, 1.0 - vUV.y)); }`

// The region around a face, pre-shrunk through the mipmaps (a box filter) into a SLOT square.
const ROI_FS = `#version 300 es
precision highp float;
in vec2 vUV;
uniform sampler2D uSrc;
uniform vec2 uSrcSize;
uniform vec3 uRoi; // x, y (top-left, px), side
uniform float uLod;
out vec4 o;
void main() {
  vec2 p = uRoi.xy + vec2(vUV.x, 1.0 - vUV.y) * uRoi.z;
  o = textureLod(uSrc, clamp(p, vec2(0.5), uSrcSize - 0.5) / uSrcSize, uLod);
}`

const GAUSS_FS = `#version 300 es
precision highp float;
in vec2 vUV;
uniform sampler2D uSrc;
uniform vec2 uDir; // one texel along the pass
uniform float uSigma;
out vec4 o;
void main() {
  float s = max(uSigma, 0.5);
  vec4 acc = texture(uSrc, vUV);
  float tw = 1.0;
  for (int i = 1; i <= 32; i++) {
    float x = float(i);
    if (x > 3.0 * s) break;
    float w = exp(-0.5 * x * x / (s * s));
    acc += w * (texture(uSrc, vUV + uDir * x) + texture(uSrc, vUV - uDir * x));
    tw += 2.0 * w;
  }
  o = acc / tw;
}`

// The face's effect over the frame, through a soft ellipse.
const COMP_VS = `#version 300 es
in vec2 aPos;
uniform vec3 uRoi;
uniform vec2 uCanvas;
out vec2 vImg;
void main() {
  vImg = uRoi.xy + (aPos * 0.5 + 0.5) * uRoi.z;
  gl_Position = vec4(vImg.x / uCanvas.x * 2.0 - 1.0, 1.0 - vImg.y / uCanvas.y * 2.0, 0.0, 1.0);
}`

const COMP_FS = `#version 300 es
precision highp float;
in vec2 vImg;
uniform sampler2D uSrc;
uniform sampler2D uBlur;
uniform vec2 uSrcSize;
uniform vec3 uRoi;
uniform vec4 uEll; // cx, cy, rx, ry (px)
uniform int uMode; // 0 blur, 1 pixelate, 2 solid
uniform float uBlock;
uniform float uAlpha;
out vec4 o;
void main() {
  vec2 d = (vImg - uEll.xy) / uEll.zw;
  float e = length(d);
  if (uMode == 0) {
    vec2 uv = vec2((vImg.x - uRoi.x) / uRoi.z, 1.0 - (vImg.y - uRoi.y) / uRoi.z);
    float a = 1.0 - smoothstep(${MASK_INNER.toFixed(2)}, ${MASK_OUTER.toFixed(2)}, e);
    o = vec4(texture(uBlur, uv).rgb, a * uAlpha);
  } else if (uMode == 1) {
    vec2 c = (floor(vImg / uBlock) + 0.5) * uBlock;
    float a = 1.0 - smoothstep(0.96, 1.02, e);
    o = vec4(textureLod(uSrc, c / uSrcSize, log2(uBlock)).rgb, a * uAlpha);
  } else {
    float a = 1.0 - smoothstep(0.96, 1.02, e);
    o = vec4(0.07, 0.07, 0.09, a * uAlpha);
  }
}`

// A full-frame picture with transparency (the watermark), over everything.
const OVERLAY_FS = `#version 300 es
precision highp float;
in vec2 vUV;
uniform sampler2D uSrc;
out vec4 o;
void main() { o = texture(uSrc, vec2(vUV.x, 1.0 - vUV.y)); }`

type Prog = { p: WebGLProgram; u: Record<string, WebGLUniformLocation | null> }

export class BlurRenderer {
  readonly gl: WebGL2RenderingContext
  private quad: WebGLBuffer
  private copy: Prog
  private roi: Prog
  private gauss: Prog
  private comp: Prog
  private src: WebGLTexture
  private fbo: WebGLFramebuffer[] = []
  private fboTex: WebGLTexture[] = []
  private overlayProg: Prog
  private overlayTex: WebGLTexture | null = null
  private srcW = 0
  private srcH = 0

  constructor(readonly canvas: HTMLCanvasElement | OffscreenCanvas) {
    const gl = canvas.getContext('webgl2', { premultipliedAlpha: false, preserveDrawingBuffer: true, antialias: false }) as WebGL2RenderingContext | null
    if (!gl) throw new Error('WebGL2 unavailable')
    this.gl = gl
    this.quad = gl.createBuffer()!
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quad)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
    this.copy = this.program(VS, COPY_FS, ['uSrc'])
    this.roi = this.program(VS, ROI_FS, ['uSrc', 'uSrcSize', 'uRoi', 'uLod'])
    this.gauss = this.program(VS, GAUSS_FS, ['uSrc', 'uDir', 'uSigma'])
    this.comp = this.program(COMP_VS, COMP_FS, ['uSrc', 'uBlur', 'uSrcSize', 'uRoi', 'uCanvas', 'uEll', 'uMode', 'uBlock', 'uAlpha'])
    this.overlayProg = this.program(VS, OVERLAY_FS, ['uSrc'])
    this.src = gl.createTexture()!
    for (let i = 0; i < 2; i++) {
      const t = gl.createTexture()!
      gl.bindTexture(gl.TEXTURE_2D, t)
      gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, SLOT, SLOT)
      this.params(gl.LINEAR, gl.LINEAR)
      const f = gl.createFramebuffer()!
      gl.bindFramebuffer(gl.FRAMEBUFFER, f)
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0)
      this.fbo.push(f)
      this.fboTex.push(t)
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, null)
  }

  /** Draws [source] (the whole frame, w x h) with [faces] hidden as [look] says. */
  draw(source: TexImageSource, w: number, h: number, faces: Ellipse[], look: Look) {
    const gl = this.gl
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w
      this.canvas.height = h
    }
    gl.bindTexture(gl.TEXTURE_2D, this.src)
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false)
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source)
    this.srcW = w
    this.srcH = h
    const needMips = faces.length > 0 && look.mode !== 'solid'
    if (needMips) gl.generateMipmap(gl.TEXTURE_2D)
    this.params(needMips ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR, gl.LINEAR)

    gl.disable(gl.BLEND)
    gl.bindFramebuffer(gl.FRAMEBUFFER, null)
    gl.viewport(0, 0, w, h)
    this.use(this.copy)
    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, this.src)
    gl.uniform1i(this.copy.u.uSrc, 0)
    this.drawQuad(this.copy)

    for (const f of faces) this.face(f, look)
    if (this.overlayTex) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, null)
      gl.viewport(0, 0, w, h)
      gl.enable(gl.BLEND)
      gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ZERO, gl.ONE)
      this.use(this.overlayProg)
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, this.overlayTex)
      gl.uniform1i(this.overlayProg.u.uSrc, 0)
      this.drawQuad(this.overlayProg)
      gl.disable(gl.BLEND)
    }
  }

  /** A frame-sized picture drawn over every frame (null: none). */
  setOverlay(img: TexImageSource | null) {
    const gl = this.gl
    if (!img) {
      if (this.overlayTex) gl.deleteTexture(this.overlayTex)
      this.overlayTex = null
      return
    }
    this.overlayTex ??= gl.createTexture()!
    gl.bindTexture(gl.TEXTURE_2D, this.overlayTex)
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false)
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img)
    this.params(gl.LINEAR, gl.LINEAR)
  }

  private face(f: Ellipse, look: Look) {
    const gl = this.gl
    const rx = Math.max(2, f.rx), ry = Math.max(2, f.ry)
    const size = 2 * Math.max(rx, ry)
    let sigma = 0, block = 0
    if (look.mode === 'blur') sigma = size * (0.035 + 0.075 * look.strength)
    else if (look.mode === 'pixelate') block = Math.max(2, size / (16 - 9 * look.strength))
    const r = Math.max(rx, ry) * MASK_OUTER + 3 * sigma + 2
    const roi: [number, number, number] = [f.cx - r, f.cy - r, 2 * r]

    if (look.mode === 'blur') {
      const scale = SLOT / roi[2]
      gl.viewport(0, 0, SLOT, SLOT)
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo[0])
      this.use(this.roi)
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, this.src)
      gl.uniform1i(this.roi.u.uSrc, 0)
      gl.uniform2f(this.roi.u.uSrcSize, this.srcW, this.srcH)
      gl.uniform3f(this.roi.u.uRoi, roi[0], roi[1], roi[2])
      gl.uniform1f(this.roi.u.uLod, Math.max(0, Math.log2(1 / scale) - 0.5))
      this.drawQuad(this.roi)
      const s = sigma * scale
      this.use(this.gauss)
      gl.uniform1f(this.gauss.u.uSigma, s)
      gl.uniform1i(this.gauss.u.uSrc, 0)
      for (const [from, to, dir] of [[0, 1, [1 / SLOT, 0]], [1, 0, [0, 1 / SLOT]]] as const) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo[to])
        gl.bindTexture(gl.TEXTURE_2D, this.fboTex[from])
        gl.uniform2f(this.gauss.u.uDir, dir[0], dir[1])
        this.drawQuad(this.gauss)
      }
    }

    gl.bindFramebuffer(gl.FRAMEBUFFER, null)
    gl.viewport(0, 0, this.srcW, this.srcH)
    gl.enable(gl.BLEND)
    gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ZERO, gl.ONE)
    this.use(this.comp)
    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, this.src)
    gl.activeTexture(gl.TEXTURE1)
    gl.bindTexture(gl.TEXTURE_2D, this.fboTex[0])
    gl.uniform1i(this.comp.u.uSrc, 0)
    gl.uniform1i(this.comp.u.uBlur, 1)
    gl.uniform2f(this.comp.u.uSrcSize, this.srcW, this.srcH)
    gl.uniform2f(this.comp.u.uCanvas, this.srcW, this.srcH)
    gl.uniform3f(this.comp.u.uRoi, roi[0], roi[1], roi[2])
    gl.uniform4f(this.comp.u.uEll, f.cx, f.cy, rx, ry)
    gl.uniform1i(this.comp.u.uMode, look.mode === 'blur' ? 0 : look.mode === 'pixelate' ? 1 : 2)
    gl.uniform1f(this.comp.u.uBlock, Math.max(1, block))
    gl.uniform1f(this.comp.u.uAlpha, f.alpha)
    this.drawQuad(this.comp)
    gl.activeTexture(gl.TEXTURE0)
    gl.disable(gl.BLEND)
  }

  private params(min: number, mag: number) {
    const gl = this.gl
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, min)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, mag)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
  }

  private use(p: Prog) {
    this.gl.useProgram(p.p)
  }

  private drawQuad(p: Prog) {
    const gl = this.gl
    const loc = gl.getAttribLocation(p.p, 'aPos')
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quad)
    gl.enableVertexAttribArray(loc)
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0)
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
  }

  private program(vs: string, fs: string, uniforms: string[]): Prog {
    const gl = this.gl
    const sh = (type: number, src: string) => {
      const s = gl.createShader(type)!
      gl.shaderSource(s, src)
      gl.compileShader(s)
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? 'shader')
      return s
    }
    const p = gl.createProgram()!
    gl.attachShader(p, sh(gl.VERTEX_SHADER, vs))
    gl.attachShader(p, sh(gl.FRAGMENT_SHADER, fs))
    gl.linkProgram(p)
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p) ?? 'link')
    const u: Prog['u'] = {}
    for (const n of uniforms) u[n] = gl.getUniformLocation(p, n)
    return { p, u }
  }
}

/** The ellipse that hides a face box (fractions of the frame), as the Android app draws it. */
export function ellipseFor(box: [number, number, number, number], w: number, h: number, padding: number, alpha = 1): Ellipse {
  const [cx, cy, bw, bh] = box
  return { cx: cx * w, cy: (cy - 0.06 * bh) * h, rx: (bw * w) / 2 * padding, ry: (bh * h) / 2 * padding * 1.18, alpha }
}
