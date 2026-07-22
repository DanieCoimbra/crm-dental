'use client'

import { SWRConfig } from 'swr'
import { get, set } from 'idb-keyval'

const CACHE_KEY = 'dental-crm-swr-cache'

function localStorageProvider() {
  if (typeof window === 'undefined') {
    return new Map()
  }

  // When initializing, we create a new Map
  const map = new Map()

  // SWR automatically sets data to this map during its operations.
  // We sync it back to IndexedDB.
  if (typeof window !== 'undefined') {
    // We can't await inside the provider sync signature easily, 
    // but we can initialize it asynchronously.
    get(CACHE_KEY).then((appCache) => {
      if (window.localStorage.getItem('prevent_swr_cache_save') === '1') {
        window.localStorage.removeItem('prevent_swr_cache_save')
        import('idb-keyval').then(({ del }) => del(CACHE_KEY)).catch(console.error)
        return
      }
      if (appCache) {
        const parsed = JSON.parse(appCache)
        parsed.forEach(([k, v]) => {
          map.set(k, v)
        })
      }
    }).catch(console.error)

    // Save to IndexedDB whenever the tab unloads or periodically
    const saveToDB = () => {
      if (window.localStorage.getItem('prevent_swr_cache_save') === '1') return;
      const appCache = JSON.stringify(Array.from(map.entries()))
      set(CACHE_KEY, appCache)
    }

    window.addEventListener('beforeunload', saveToDB)
    // Also save every 10 seconds to ensure freshness in case of sudden crash
    setInterval(saveToDB, 10000)
  }

  return map
}

export default function SWRProvider({ children }) {
  return (
    <SWRConfig value={{ provider: localStorageProvider, revalidateOnReconnect: true }}>
      {children}
    </SWRConfig>
  )
}
