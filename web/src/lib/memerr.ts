/**
 * Whether an error says the device ran out of memory: an allocation that failed (an array buffer, WebAssembly's
 * memory that can't grow, a C++ allocation in the runtime, the graphics processor's own "out of memory").
 * Not every RangeError, and not "memory access out of bounds" (a bug or a bad model file, not a lack of memory):
 * those stay ordinary failures, so a graphics processor that fails still falls back to the CPU.
 */
export function isMemoryError(e: unknown): boolean {
  const s = e instanceof Error ? `${e.name}: ${e.message}` : String(e ?? '')
  return /array buffer allocation failed|failed to allocate|could not allocate|cannot allocate|unable to allocate|allocation failed|bad_alloc|out of memory|\bOOM\b|Memory\.grow\(\)|maximum memory size exceeded/i.test(s)
}
