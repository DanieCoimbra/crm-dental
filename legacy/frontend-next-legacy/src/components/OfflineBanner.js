'use client'

import { useState, useEffect } from 'react'
import { WifiOff, RefreshCw } from 'lucide-react'
import { processQueue } from '@/lib/offlineSync'
import axios from '@/lib/axios'

export default function OfflineBanner() {
  const [isOffline, setIsOffline] = useState(false)
  const [isSyncing, setIsSyncing] = useState(false)

  useEffect(() => {
    // Check initial state
    if (typeof window !== 'undefined') {
      setIsOffline(!navigator.onLine)
    }

    const handleOffline = () => setIsOffline(true)
    
    const handleOnline = async () => {
      setIsOffline(false)
      setIsSyncing(true)
      try {
        await processQueue(axios)
      } catch (error) {
        console.error("Erro ao sincronizar fila:", error)
      } finally {
        setIsSyncing(false)
      }
    }

    window.addEventListener('offline', handleOffline)
    window.addEventListener('online', handleOnline)

    return () => {
      window.removeEventListener('offline', handleOffline)
      window.removeEventListener('online', handleOnline)
    }
  }, [])

  if (!isOffline && !isSyncing) return null

  return (
    <div style={{
      position: 'fixed',
      bottom: '1rem',
      right: '1rem',
      backgroundColor: isOffline ? '#f59e0b' : '#10b981',
      color: '#fff',
      padding: '0.75rem 1rem',
      borderRadius: '8px',
      display: 'flex',
      alignItems: 'center',
      gap: '0.75rem',
      boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
      zIndex: 9999,
      animation: 'slideUp 0.3s ease-out'
    }}>
      {isOffline ? (
        <>
          <WifiOff size={20} />
          <span style={{ fontSize: '0.9rem', fontWeight: 500 }}>
            Você está offline. As alterações serão salvas localmente.
          </span>
        </>
      ) : (
        <>
          <RefreshCw size={20} style={{ animation: 'spin 1s linear infinite' }} />
          <span style={{ fontSize: '0.9rem', fontWeight: 500 }}>
            Conexão restaurada. Sincronizando dados...
          </span>
        </>
      )}
      <style>{`
        @keyframes slideUp {
          from { transform: translateY(100%); opacity: 0; }
          to { transform: translateY(0); opacity: 1; }
        }
        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  )
}
