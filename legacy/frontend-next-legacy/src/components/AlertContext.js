'use client'

import React, { createContext, useContext, useState, useCallback } from 'react'
import { CheckCircle, XCircle, AlertTriangle, Info, X } from 'lucide-react'

const AlertContext = createContext(null)

export const useAlert = () => {
  const context = useContext(AlertContext)
  if (!context) throw new Error('useAlert must be used within an AlertProvider')
  return context
}

export function AlertProvider({ children }) {
  const [toasts, setToasts] = useState([])
  const [confirmDialog, setConfirmDialog] = useState(null)

  const showAlert = useCallback((message, type = 'info') => {
    const id = Math.random().toString(36).substring(2, 9)
    setToasts((prev) => [...prev, { id, message, type }])
    
    // Auto remove after 4 seconds
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id))
    }, 4000)
  }, [])

  const removeToast = (id) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }

  const showConfirm = useCallback((message, onConfirm) => {
    setConfirmDialog({ message, onConfirm })
  }, [])

  const closeConfirm = () => setConfirmDialog(null)

  const handleConfirm = () => {
    if (confirmDialog?.onConfirm) {
      confirmDialog.onConfirm()
    }
    closeConfirm()
  }

  // Icons and colors for Toasts
  const getToastStyle = (type) => {
    switch (type) {
      case 'success':
        return { icon: <CheckCircle size={20} color="#10b981" />, bg: 'var(--bg-secondary)', border: '1px solid #10b981' }
      case 'error':
        return { icon: <XCircle size={20} color="#ef4444" />, bg: 'var(--bg-secondary)', border: '1px solid #ef4444' }
      case 'warning':
        return { icon: <AlertTriangle size={20} color="#f59e0b" />, bg: 'var(--bg-secondary)', border: '1px solid #f59e0b' }
      default:
        return { icon: <Info size={20} color="#3b82f6" />, bg: 'var(--bg-secondary)', border: '1px solid #3b82f6' }
    }
  }

  return (
    <AlertContext.Provider value={{ showAlert, showConfirm }}>
      {children}

      {/* Toasts Container - Top Right */}
      <div style={{
        position: 'fixed',
        top: '20px',
        right: '20px',
        zIndex: 9999,
        display: 'flex',
        flexDirection: 'column',
        gap: '0.75rem',
        pointerEvents: 'none'
      }}>
        {toasts.map((toast) => {
          const style = getToastStyle(toast.type)
          return (
            <div key={toast.id} style={{
              pointerEvents: 'auto',
              display: 'flex',
              alignItems: 'center',
              gap: '0.75rem',
              backgroundColor: style.bg,
              border: style.border,
              padding: '1rem',
              borderRadius: '8px',
              boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
              minWidth: '250px',
              maxWidth: '350px',
              color: 'var(--text-primary)',
              animation: 'slideIn 0.3s ease-out',
            }}>
              {style.icon}
              <div style={{ flex: 1, fontSize: '0.9rem', fontWeight: 500 }}>{toast.message}</div>
              <button 
                onClick={() => removeToast(toast.id)}
                style={{ background: 'transparent', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', padding: 0 }}
              >
                <X size={16} />
              </button>
            </div>
          )
        })}
      </div>

      {/* Confirm Modal */}
      {confirmDialog && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          backgroundColor: 'rgba(0,0,0,0.6)',
          backdropFilter: 'blur(4px)',
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          zIndex: 10000
        }}>
          <div className="card" style={{ maxWidth: '400px', width: '90%', animation: 'fadeIn 0.2s ease-out' }}>
            <h3 style={{ fontSize: '1.25rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '1rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <AlertTriangle size={24} color="#f59e0b" /> Confirmação
            </h3>
            <p style={{ color: 'var(--text-secondary)', marginBottom: '1.5rem', lineHeight: 1.5 }}>
              {confirmDialog.message}
            </p>
            <div style={{ display: 'flex', gap: '1rem', justifyContent: 'flex-end' }}>
              <button 
                onClick={closeConfirm} 
                className="btn-primary" 
                style={{ backgroundColor: 'transparent', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}
              >
                Cancelar
              </button>
              <button 
                onClick={handleConfirm} 
                className="btn-primary" 
                style={{ backgroundColor: '#ef4444' }}
              >
                Confirmar
              </button>
            </div>
          </div>
        </div>
      )}

      <style jsx global>{`
        @keyframes slideIn {
          from { transform: translateX(100%); opacity: 0; }
          to { transform: translateX(0); opacity: 1; }
        }
      `}</style>
    </AlertContext.Provider>
  )
}
