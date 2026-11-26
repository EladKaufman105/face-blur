// YuNet in a worker of its own (see lib/yunet-worker.ts).
import { Yunet } from '../lib/yunet'
import type { YunetReply, YunetRequest } from '../lib/yunet-worker'

const post = (m: YunetReply) => (self as unknown as Worker).postMessage(m)
let yunet: Yunet | null = null

self.onmessage = async (e: MessageEvent<YunetRequest>) => {
  const m = e.data
  try {
    if (m.type === 'init') {
      yunet = await Yunet.create(m.model)
      post({ type: 'ready' })
    } else if (yunet) {
      try {
        post({ type: 'dets', id: m.id, dets: await yunet.detect(m.frame, m.threshold) })
      } finally {
        m.frame.close()
      }
    } else {
      m.frame.close()
      post({ type: 'error', id: m.id, message: 'not started' })
    }
  } catch (err) {
    post({ type: 'error', id: m.type === 'frame' ? m.id : undefined, message: String((err as Error)?.message ?? err) })
  }
}
