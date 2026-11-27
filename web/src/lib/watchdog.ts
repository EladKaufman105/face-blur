// The subtitles job's stall judgement, apart from the page so it can be tested: what is quiet, for how long, against which limit.

/** The model side counts as speaking up for this long after its last message. */
export const MODEL_SPOKE_MS = 15_000

/**
 * The quiet (ms) that counts as a stall and its limit (ms), or null when nothing is over its limit.
 * The sound alone is judged (by [audioLimit]) while the model side speaks up; the model's own quiet is always held against its
 * phase's limit [phaseLimit]. When both are quiet the worker may just be busy (creating the model's session blocks it, and the sound's
 * ticks with it), so the phase's limit counts, not the sound's.
 */
export function stalled(phase: string, audioRuns: boolean, quietA: number, quietM: number, audioLimit: number, phaseLimit: number): { quiet: number; limit: number } | null {
  if (phase === 'audio') return quietA > audioLimit ? { quiet: quietA, limit: audioLimit } : null
  if (quietM > phaseLimit) return { quiet: quietM, limit: phaseLimit }
  if (audioRuns && phase !== 'recognize' && quietM < MODEL_SPOKE_MS && quietA > audioLimit) return { quiet: quietA, limit: audioLimit }
  return null
}
