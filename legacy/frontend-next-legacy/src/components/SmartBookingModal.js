import React from 'react';
import { X, Clock, AlertCircle, Phone, CalendarCheck } from 'lucide-react';

export default function SmartBookingModal({ isOpen, onClose, suggestions, onBookNow }) {
  if (!isOpen || !suggestions || suggestions.length === 0) return null;

  return (
    <div style={{
      position: 'fixed',
      top: 0, left: 0, right: 0, bottom: 0,
      backgroundColor: 'rgba(0,0,0,0.5)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 9999
    }}>
      <div style={{
        backgroundColor: 'var(--bg-primary)',
        borderRadius: '12px',
        width: '90%',
        maxWidth: '500px',
        boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1)',
        overflow: 'hidden'
      }}>
        {/* Header */}
        <div style={{
          backgroundColor: 'var(--accent)',
          padding: '1.5rem',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          color: '#fff'
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <AlertCircle size={24} />
            <h3 style={{ margin: 0, fontSize: '1.25rem', fontWeight: 600 }}>Oportunidade de Encaixe!</h3>
          </div>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: '#fff', cursor: 'pointer' }}>
            <X size={24} />
          </button>
        </div>

        {/* Body */}
        <div style={{ padding: '1.5rem' }}>
          <p style={{ color: 'var(--text-secondary)', marginBottom: '1.5rem', fontSize: '0.95rem' }}>
            Um horário acabou de ficar vago. Encontramos {suggestions.length} paciente(s) na fila de espera que podem preencher esse buraco:
          </p>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            {suggestions.map((suggestion) => (
              <div key={suggestion.id} style={{
                border: '1px solid var(--border-color)',
                borderRadius: '8px',
                padding: '1rem',
                display: 'flex',
                flexDirection: 'column',
                gap: '0.75rem',
                backgroundColor: 'var(--bg-secondary)'
              }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                  <div>
                    <h4 style={{ margin: 0, fontSize: '1.05rem', color: 'var(--text-primary)' }}>
                      {suggestion.patient?.name || 'Paciente Desconhecido'}
                    </h4>
                    <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                      {suggestion.appointment_type?.name || 'Consulta'} ({suggestion.appointment_type?.duration_minutes} min)
                    </span>
                  </div>
                  {suggestion.urgency_level === 'high' && (
                    <span style={{ backgroundColor: '#fee2e2', color: '#ef4444', padding: '0.2rem 0.5rem', borderRadius: '4px', fontSize: '0.75rem', fontWeight: 600 }}>
                      Alta Urgência
                    </span>
                  )}
                  {suggestion.urgency_level === 'medium' && (
                    <span style={{ backgroundColor: '#fef3c7', color: '#f59e0b', padding: '0.2rem 0.5rem', borderRadius: '4px', fontSize: '0.75rem', fontWeight: 600 }}>
                      Média Urgência
                    </span>
                  )}
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
                  <Phone size={14} />
                  <span>{suggestion.patient?.phone || 'Sem telefone'}</span>
                </div>

                <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem' }}>
                  <button 
                    onClick={() => onBookNow(suggestion)}
                    style={{
                      flex: 1,
                      padding: '0.5rem',
                      backgroundColor: 'var(--accent)',
                      color: '#fff',
                      border: 'none',
                      borderRadius: '6px',
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      gap: '0.5rem',
                      fontWeight: 500
                    }}
                  >
                    <CalendarCheck size={16} />
                    Encaixar Agora
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
