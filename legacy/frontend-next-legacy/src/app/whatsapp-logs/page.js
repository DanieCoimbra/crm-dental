'use client'

import { useState, useEffect } from 'react'
import { useAuth } from '@/hooks/auth'
import axios from '@/lib/axios'
import DashboardLayout from '@/components/DashboardLayout'
import { MessageCircle, CheckCircle2, XCircle, Clock } from 'lucide-react'
import format from 'date-fns/format'
import parseISO from 'date-fns/parseISO'

export default function WhatsAppLogsPage() {
  const { user } = useAuth({ middleware: 'auth' })
  const [logs, setLogs] = useState([])
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    if (user && ['owner', 'manager'].includes(user.role)) {
      fetchLogs()
    }
  }, [user])

  const fetchLogs = async () => {
    setIsLoading(true)
    try {
      const res = await axios.get('/api/whatsapp-logs')
      setLogs(res.data)
    } catch (err) {
      console.error(err)
    } finally {
      setIsLoading(false)
    }
  }

  const getTypeLabel = (type) => {
    switch(type) {
      case 'birthday': return { label: 'Aniversário', color: '#8b5cf6', bg: 'rgba(139, 92, 246, 0.1)' }
      case 'confirmation': return { label: 'Confirmação', color: '#3b82f6', bg: 'rgba(59, 130, 246, 0.1)' }
      case 'recovery': return { label: 'Recuperação', color: '#f59e0b', bg: 'rgba(245, 158, 11, 0.1)' }
      default: return { label: type, color: '#64748b', bg: 'rgba(100, 116, 139, 0.1)' }
    }
  }

  const getStatusIcon = (status) => {
    switch(status) {
      case 'sent': return <CheckCircle2 size={16} color="#10b981" />
      case 'failed': return <XCircle size={16} color="#ef4444" />
      default: return <Clock size={16} color="#f59e0b" />
    }
  }

  if (!user || !['owner', 'manager'].includes(user.role)) {
    return (
      <DashboardLayout>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '60vh', color: 'var(--text-secondary)' }}>
          <h2>Acesso Restrito</h2>
          <p>Você não tem permissão para acessar o histórico de envios.</p>
        </div>
      </DashboardLayout>
    )
  }

  return (
    <DashboardLayout>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2rem' }}>
        <div>
          <h1 style={{ fontSize: '1.75rem', fontWeight: 600, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            <MessageCircle size={28} color="#22c55e" /> Histórico WhatsApp
          </h1>
          <p style={{ color: 'var(--text-secondary)', marginTop: '0.25rem' }}>
            Acompanhe todas as mensagens enviadas automaticamente pelo robô.
          </p>
        </div>
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {isLoading ? (
          <div style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Carregando histórico...</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ backgroundColor: 'var(--bg-tertiary)', borderBottom: '1px solid var(--border-color)' }}>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Data / Hora</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Paciente</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Telefone</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Motivo</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Mensagem</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Status</th>
                </tr>
              </thead>
              <tbody>
                {logs.length === 0 ? (
                  <tr>
                    <td colSpan="6" style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Nenhuma mensagem registrada no histórico.</td>
                  </tr>
                ) : (
                  logs.map((log) => {
                    const typeStyle = getTypeLabel(log.message_type)
                    return (
                      <tr key={log.id} style={{ borderBottom: '1px solid var(--border-color)', transition: 'background-color 0.2s' }} onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'} onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}>
                        <td style={{ padding: '1.25rem 1.5rem', color: 'var(--text-secondary)', fontSize: '0.9rem', whiteSpace: 'nowrap' }}>
                          {log.sent_at ? format(parseISO(log.sent_at), 'dd/MM/yyyy HH:mm') : '-'}
                        </td>
                        <td style={{ padding: '1.25rem 1.5rem', fontWeight: 500, color: 'var(--text-primary)', whiteSpace: 'nowrap' }}>
                          {log.patient ? log.patient.name : `Deletado (#${log.patient_id})`}
                        </td>
                        <td style={{ padding: '1.25rem 1.5rem', color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>
                          {log.phone_number}
                        </td>
                        <td style={{ padding: '1.25rem 1.5rem', whiteSpace: 'nowrap' }}>
                          <span style={{ backgroundColor: typeStyle.bg, color: typeStyle.color, padding: '0.25rem 0.75rem', borderRadius: '12px', fontSize: '0.8rem', fontWeight: 500 }}>
                            {typeStyle.label}
                          </span>
                        </td>
                        <td style={{ padding: '1.25rem 1.5rem', color: 'var(--text-secondary)', fontSize: '0.85rem', maxWidth: '300px' }}>
                          {log.message_body}
                        </td>
                        <td style={{ padding: '1.25rem 1.5rem' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                            {getStatusIcon(log.status)}
                            <span style={{ fontSize: '0.85rem', textTransform: 'capitalize', color: log.status === 'sent' ? '#10b981' : (log.status === 'failed' ? '#ef4444' : '#f59e0b') }}>
                              {log.status}
                            </span>
                          </div>
                        </td>
                      </tr>
                    )
                  })
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </DashboardLayout>
  )
}
