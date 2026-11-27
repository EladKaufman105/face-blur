// Where ONNX Runtime puts the face model's nodes on the GPU (see FaceDetector.probePlacement): a session of its own,
// made with the runtime's verbose log on, only to read "Node(s) placed on [...]" from it. The page ends this worker.
import * as ort from 'onnxruntime-web/webgpu'
import { capturePlacement, placementCounts } from '../lib/devicediag'

self.onmessage = async (e: MessageEvent<{ model: Uint8Array }>) => {
  const seen: string[] = []
  try {
    ort.env.wasm.numThreads = 1
    ort.env.logLevel = 'verbose'
    await capturePlacement(seen, () => ort.InferenceSession.create(e.data.model, { executionProviders: ['webgpu'], graphOptimizationLevel: 'all', logSeverityLevel: 0 }))
  } catch {
    // The counts it got (none, if it failed to start) are all there is.
  }
  const n = placementCounts(seen)
  ;(self as unknown as Worker).postMessage(n)
}
