import { ref, watch } from 'vue'

// Only display choices are retained; credentials and business drafts are not.
export function useDisplayPreference(key: string, defaultValue = false) {
  const storageKey = `edsp.ui.${key}`
  let initial = defaultValue
  try {
    const saved = localStorage.getItem(storageKey)
    if (saved !== null) initial = saved === 'true'
  } catch { /* Storage can be disabled by browser policy. */ }
  const value = ref(initial)
  watch(value, next => {
    try { localStorage.setItem(storageKey, String(next)) } catch { /* In-memory preference still works. */ }
  })
  return value
}
