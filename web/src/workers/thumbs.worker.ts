// The timeline's filmstrip: small frames at regular times, decoded in one pass.
import { ALL_FORMATS, BlobSource, CanvasSink, Input } from 'mediabunny'

export type ThumbsRequest = { file: File; step: number; height: number }
export type ThumbsMessage = { type: 'thumb'; t: number; blob: Blob } | { type: 'done' }

self.onmessage = async (e: MessageEvent<ThumbsRequest>) => {
  const { file, step, height } = e.data
  try {
    const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
    const video = await input.getPrimaryVideoTrack()
    if (!video) return
    const end = await video.computeDuration()
    const times: number[] = []
    for (let t = 0; t < end; t += step) times.push(t)
    const sink = new CanvasSink(video, { height, poolSize: 2 })
    let k = 0
    for await (const wc of sink.canvasesAtTimestamps(times)) {
      const t = times[k++]
      if (!wc) continue
      const blob = await (wc.canvas as OffscreenCanvas).convertToBlob({ type: 'image/jpeg', quality: 0.7 })
      ;(self as unknown as Worker).postMessage({ type: 'thumb', t, blob } satisfies ThumbsMessage)
    }
  } finally {
    ;(self as unknown as Worker).postMessage({ type: 'done' } satisfies ThumbsMessage)
  }
}
