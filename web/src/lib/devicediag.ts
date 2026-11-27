// What the detection found out about this device, for the stats panel's copied report ("Copy"): the
// owner pastes it back when detection is slow on a device we can't run on. Pure (no browser needed to
// test it, and nothing here reaches the network: it's only text the user copies).

/** What the GPU path found on this device (set while the detector starts). */
export interface GpuDiag {
  /** The WebGPU adapter, as the browser describes it ('' if it says nothing). */
  adapter: string
  f16: boolean
  /** A software (fallback) adapter: never used, the CPU's own path is faster. */
  software: boolean
  /** How many nodes ONNX Runtime put on the GPU and on the CPU (null: not measured yet, see FaceDetector.probePlacement). */
  nodesGpu: number | null
  nodesCpu: number | null
  /** The model's precision on the GPU, and how long its first run took (ms: the shaders compiling; null: not run yet). */
  model: string
  firstRunMs: number | null
}

/** The device diagnostic the analysis worker sends with its stats. */
export interface DeviceDiag {
  gpu: GpuDiag | null
  /** WebAssembly threads per detector (1: each worker runs one; the cores are used by the workers side by side) and SIMD. */
  threads: number
  simd: boolean
  /** The analysis worker's cross-origin isolation (the page's, through its service worker). */
  isolated: boolean
  /** Detectors (CPU workers) running, and how many the cores and memory allowed. */
  pool: number
  poolWanted: number
  cores: number
  memoryGB: number | null
}

/** Whether this browser runs WebAssembly SIMD (the runtime's builds all need it; wasm-feature-detect's test module). */
let simd: boolean | undefined
export function wasmSimd(): boolean {
  return (simd ??= wasmSimdNow())
}
function wasmSimdNow(): boolean {
  try {
    return WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11]))
  } catch {
    return false
  }
}

/** [adapter]'s description in one line. */
export function adapterText(adapter: any): string {
  const i = adapter?.info ?? {}
  return [i.vendor, i.architecture, i.device, i.description].filter(Boolean).join(' ')
}

/** A software adapter (SwiftShader, llvmpipe: WebGPU running on the CPU, several times slower than the CPU's own WebAssembly). */
export function isSoftwareAdapter(adapter: any): boolean {
  if (adapter?.isFallbackAdapter === true || adapter?.info?.isFallbackAdapter === true) return true
  // The browser doesn't always say it (newer ones keep it on adapter.info only): a software renderer names itself.
  // SwiftShader, llvmpipe, Windows' WARP ("Microsoft Basic Render Driver"), and the VM adapters that draw on the host's CPU.
  return /swiftshader|llvmpipe|software|\bwarp\b|basic render|vmware|virtualbox|svga3d/i.test(`${adapter?.info?.architecture ?? ''} ${adapter?.info?.description ?? ''} ${adapter?.info?.vendor ?? ''} ${adapter?.info?.device ?? ''}`)
}

/** The "Node(s) placed on [X]. Number of nodes: N" lines ONNX Runtime logs when a session is made (verbose), as counts per provider. */
export function placementCounts(lines: string[]): { gpu: number | null; cpu: number | null } {
  let gpu: number | null = null, cpu: number | null = null
  for (const l of lines) {
    const m = /placed on \[(\w+)\]\. Number of nodes: (\d+)/.exec(l)
    if (!m) continue
    if (m[1] === 'WebGpuExecutionProvider') gpu = (gpu ?? 0) + Number(m[2])
    else if (m[1] === 'CPUExecutionProvider') cpu = (cpu ?? 0) + Number(m[2])
  }
  return { gpu, cpu }
}

/**
 * Runs [make] with the console's output watched: the runtime's node placement lines go to [lines] (and nowhere else: the
 * verbose log it prints while a session is made is a few thousand lines), anything else passes on as it was.
 */
export async function capturePlacement<T>(lines: string[], make: () => Promise<T>): Promise<T> {
  const c = console as any
  const names = ['log', 'info', 'debug', 'warn', 'error'] as const
  const saved = names.map((n) => c[n])
  names.forEach((n, i) => {
    c[n] = (...a: unknown[]) => {
      const text = a.length === 1 && typeof a[0] === 'string' ? a[0] : ''
      if (text.includes('onnxruntime')) { if (text.includes('Node(s) placed on')) lines.push(text) } else saved[i].apply(console, a)
    }
  })
  try {
    return await make()
  } finally {
    names.forEach((n, i) => { c[n] = saved[i] })
  }
}

/** Cores kept for the decoder, the page and the GPU's driver; a detector for each of the others. */
const KEEP_CORES = 2
/** The most CPU detectors a device runs at once, by what it says its memory is (each holds the model and a WebAssembly heap: 140 MB at 640 px, 200-350 MB at 960 px, measured). */
const MOST = { 8: 6, 4: 3 } as const

/**
 * How many CPU detectors (workers, one WebAssembly thread each) run side by side: a detection is a long single-thread run, so
 * the frames go to the cores in turn. Two even on two cores (measured: about 30% less time); up to cores - 2 beyond, no more than
 * the memory allows (6 with 8 GB: about 2 GB of heaps) (browsers say at most 8 GB; Firefox and Safari don't say: two) and, on a phone or tablet (big.LITTLE cores
 * that throttle, memory shared with the system), four.
 */
export function poolSize(cores: number, memoryGB?: number, mobile = false): number {
  if (!(cores > 1)) return 1
  const most = memoryGB === undefined ? 2 : memoryGB >= 8 ? MOST[8] : memoryGB >= 4 ? MOST[4] : 2
  const phone = mobile ? 4 : Infinity
  return Math.max(2, Math.min(most, phone, Math.floor(cores) - KEEP_CORES))
}

/**
 * How many detectors take over when detection leaves the GPU (a software adapter, the CPU found faster, a failed GPU): [wanted] with
 * 4 or more cores, else one (a detector with YuNet in a worker of its own overlaps the two on two or three cores; two workers, each
 * running YuNet after RetinaFace, didn't beat that in the runs made).
 */
export const fallbackPoolSize = (cores: number, wanted: number) => (cores >= 4 ? wanted : 1)

/** How much of N workers' speed N detectors really get on N cores (measured: 2 workers on 2 cores 7.28 a second against 4.74 for one = 0.77). */
export const POOL_EFFICIENCY = 0.75

export interface GpuVsCpu {
  /** The check's means per frame (ms): the GPU's RetinaFace, the CPU's one core's RetinaFace, YuNet (it runs after RetinaFace in each CPU worker, beside it on the GPU path). */
  gpuMs: number
  cpuMs: number
  yunetMs: number
  /** CPU detectors that would run side by side. */
  lanes: number
  /** The GPU's RetinaFace at the size the fine pass runs at (the check's time scaled by the area), and the most the pace allows it there (GPU_SLOW_MS). */
  gpuFineMs: number
  slowMs: number
}

/**
 * Whether a GPU that works (not a software adapter, not a failure) is left for the CPU's detectors: only when the GPU can't hold the
 * fine pass at the size it starts at (the pace would shrink the frame there anyway) and the CPU, YuNet included, is the faster.
 * A GPU that holds that size is never left for speed: the frame would drop from 1280 to 960 px for nothing.
 */
export function leaveGpuForSpeed(i: GpuVsCpu): boolean {
  if (i.gpuFineMs <= i.slowMs) return false
  // Side by side, each worker runs YuNet after RetinaFace; one detector has YuNet in a worker of its own, overlapping.
  if (i.lanes <= 1) return Math.max(i.cpuMs, i.yunetMs) < i.gpuMs * 0.9
  return (i.cpuMs + i.yunetMs) / (i.lanes * POOL_EFFICIENCY) < i.gpuMs * 0.9
}

/** The report's lines for [d] (keys in English in every language, as the rest of the report). */
export function diagLines(d: DeviceDiag): string[] {
  const g = d.gpu
  const out: string[] = []
  if (g) {
    out.push(`webgpu: ${g.adapter || 'adapter (the browser gives no name)'}${g.software ? ' (software adapter, not used)' : ''}`)
    out.push(`shader_f16: ${g.f16 ? 'yes' : 'no'} (model ${g.model})`)
    out.push(`ep_nodes: ${g.software ? 'no WebGPU session (software adapter)' : g.nodesGpu === null && g.nodesCpu === null ? 'not measured yet' : `${g.nodesGpu ?? 0} on the GPU, ${g.nodesCpu ?? 0} on the CPU`}`)
    out.push(`gpu_first_run_ms: ${g.firstRunMs === null ? '-' : Math.round(g.firstRunMs)}`)
  } else out.push('webgpu: not available in this browser (or forced off)')
  out.push(`wasm: ${d.threads} thread${d.threads === 1 ? '' : 's'} per detector, simd ${d.simd ? 'yes' : 'no'}`)
  out.push(`cross_origin_isolated: ${d.isolated ? 'yes' : 'no'}`)
  out.push(`detector_pool: ${d.pool} running, ${d.poolWanted} wanted (${d.cores} cores, memory ${d.memoryGB === null ? 'not given' : `>= ${d.memoryGB} GB`})`)
  return out
}
