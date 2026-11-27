// Remembered choices ("Remember my choice" in a dialog): key -> value, kept in the browser's settings
// storage (localStorage, per channel like the other settings). Absent = "ask each time". Logic in choicestore.ts.
import { signal } from '@preact/signals'
import { storageKey } from './channel'
import { parseRemembered, withChoice, withoutChoice, type Remembered } from './choicestore'

const KEY = () => storageKey('choices')

function read(): Remembered {
  try { return parseRemembered(localStorage.getItem(KEY())) } catch { return {} }
}

/** What is remembered now (a signal: a list that shows it updates). */
export const remembered = signal<Remembered>(read())

function write(m: Remembered) {
  remembered.value = m
  try {
    if (Object.keys(m).length === 0) localStorage.removeItem(KEY())
    else localStorage.setItem(KEY(), JSON.stringify(m))
  } catch {}
}

/** The remembered value of [id], or null: ask. */
export const getChoice = (id: string): string | null => remembered.value[id] ?? null
export const rememberChoice = (id: string, value: string) => write(withChoice(remembered.value, id, value))
export const clearChoice = (id: string) => write(withoutChoice(remembered.value, id))
export const clearAllChoices = () => write({})
