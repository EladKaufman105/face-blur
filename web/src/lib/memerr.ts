/** Whether an error says the device ran out of memory (WebAssembly or an array buffer that can't be allocated, a C++ allocation, the tab's own limit). */
export function isMemoryError(e: unknown): boolean {
  const s = e instanceof Error ? `${e.name}: ${e.message}` : String(e ?? '')
  return /RangeError|array buffer allocation failed|allocation failed|bad_alloc|\bOOM\b|out of memory|could not allocate|cannot allocate|memory access out of bounds/i.test(s)
}
