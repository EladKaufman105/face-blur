// The remembered choices' logic, pure (tests/choices.cjs imports it): which values are allowed
// comes from the registry (shared/prefs/choices.json, generated into gen/choices.ts). A value that
// isn't allowed for its key, and a key that isn't registered, are never remembered: it is "ask each time".
import { CHOICES, type ChoiceKey, type ChoiceValue } from '../gen/choices.ts'

export type Remembered = Record<string, string>

export const keyOf = (id: string): ChoiceKey | undefined => CHOICES.find((k) => k.id === id)
export const valueOf = (id: string, value: string): ChoiceValue | undefined => keyOf(id)?.values.find((v) => v.id === value)

/** What is stored (a JSON object), cleaned: only registered keys with an allowed value. */
export function parseRemembered(raw: string | null | undefined): Remembered {
  let o: unknown = null
  try { o = raw ? JSON.parse(raw) : null } catch {}
  const out: Remembered = {}
  if (o && typeof o === 'object' && !Array.isArray(o)) {
    for (const [k, v] of Object.entries(o)) if (typeof v === 'string' && valueOf(k, v)) out[k] = v
  }
  return out
}

/** [m] with [id] remembered as [value] (unchanged when that is not allowed). */
export function withChoice(m: Remembered, id: string, value: string): Remembered {
  return valueOf(id, value) ? { ...m, [id]: value } : m
}

/** [m] without [id]: back to "ask each time". */
export function withoutChoice(m: Remembered, id: string): Remembered {
  const { [id]: _gone, ...rest } = m
  return rest
}

/** The remembered keys, in the registry's order, each with its key and value (for the settings list). */
export function listRemembered(m: Remembered): { key: ChoiceKey; value: ChoiceValue }[] {
  const out: { key: ChoiceKey; value: ChoiceValue }[] = []
  for (const key of CHOICES) {
    const value = m[key.id] ? valueOf(key.id, m[key.id]) : undefined
    if (value) out.push({ key, value })
  }
  return out
}
