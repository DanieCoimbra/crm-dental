'use client'

import { useState, useEffect } from 'react'
import { useAuth } from '@/hooks/auth'
import axios from '@/lib/axios'
import DashboardLayout from '@/components/DashboardLayout'
import PasswordInput from '@/components/PasswordInput'
import { useAlert } from '@/components/AlertContext'
import { RotateCcw, Trash2, User as UserIcon, Calendar as CalendarIcon, Users } from 'lucide-react'

export default function TrashPage() {
  const { user } = useAuth({ middleware: 'auth' })
  const { showAlert, showConfirm } = useAlert()
  const [data, setData] = useState({ users: [], patients: [], appointments: [] })
  const [activeTab, setActiveTab] = useState('appointments')
  const [isLoading, setIsLoading] = useState(true)

  // Force Delete State
  const [showForceDeleteModal, setShowForceDeleteModal] = useState(false)
  const [forceDeletePassword, setForceDeletePassword] = useState('')
  const [isDeleting, setIsDeleting] = useState(false)
  const [itemToDelete, setItemToDelete] = useState(null)

  useEffect(() => {
    if (user && (['owner', 'manager', 'receptionist'].includes(user.role))) {
      if (['owner', 'manager'].includes(user?.role)) setActiveTab('patients')
      else setActiveTab('appointments')
      fetchTrash()
    }
  }, [user])

  const fetchTrash = async () => {
    setIsLoading(true)
    try {
      const res = await axios.get('/api/trash')
      setData(res.data)
    } catch (err) {
      console.error(err)
    } finally {
      setIsLoading(false)
    }
  }

  const handleRestore = async (type, id) => {
    showConfirm('Deseja realmente restaurar este registro?', async () => {
      try {
        await axios.post(`/api/trash/${type}/${id}/restore`)
        fetchTrash()
        showAlert('Registro restaurado com sucesso!', 'success')
      } catch (err) {
        console.error(err)
        showAlert('Erro ao restaurar registro.', 'error')
      }
    })
  }

  const handleForceDeleteClick = (type, id) => {
    setItemToDelete({ type, id })
    setShowForceDeleteModal(true)
  }

  const submitForceDelete = async (e) => {
    e.preventDefault()
    setIsDeleting(true)
    try {
      await axios.delete(`/api/trash/${itemToDelete.type}/${itemToDelete.id}/force`, {
        data: { password: forceDeletePassword }
      })
      fetchTrash()
      showAlert('Registro excluído permanentemente.', 'success')
      setShowForceDeleteModal(false)
    } catch (err) {
      console.error(err)
      if (err.response?.status === 422) {
        showAlert('Senha incorreta.', 'error')
      } else {
        showAlert(err.response?.data?.message || 'Erro ao excluir registro.', 'error')
      }
    } finally {
      setIsDeleting(false)
      setForceDeletePassword('')
      setItemToDelete(null)
    }
  }

  if (!user || (!['owner', 'manager', 'receptionist'].includes(user.role))) return null

  const renderTable = (items, type) => {
    if (items.length === 0) {
      return <div style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-secondary)' }}>A lixeira está vazia para esta categoria.</div>
    }

    return (
      <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
          <thead>
            <tr style={{ backgroundColor: 'var(--bg-tertiary)', borderBottom: '1px solid var(--border-color)' }}>
              <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Detalhes do Registro</th>
              <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Excluído Em</th>
              <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Excluído Por</th>
              <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Ações</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.id} style={{ borderBottom: '1px solid var(--border-color)', transition: 'background-color 0.2s' }} onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'} onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}>
                <td style={{ padding: '1.25rem 1.5rem' }}>
                  {type === 'patient' && (
                    <div>
                      <div style={{ fontWeight: 500 }}>{item.name}</div>
                      <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>CPF: {item.cpf}</div>
                    </div>
                  )}
                  {type === 'user' && (
                    <div>
                      <div style={{ fontWeight: 500 }}>{item.name} <span style={{ fontSize: '0.75rem', backgroundColor: 'var(--bg-secondary)', padding: '0.2rem 0.5rem', borderRadius: '4px', marginLeft: '0.5rem' }}>{item.role}</span></div>
                      <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>E-mail: {item.email}</div>
                    </div>
                  )}
                  {type === 'appointment' && (
                    <div>
                      <div style={{ fontWeight: 500 }}>Consulta: {item.patient?.name || 'Desconhecido'}</div>
                      <div style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>Médico: {item.doctor?.name || 'Desconhecido'} | Data: {new Date(item.start_time).toLocaleString('pt-BR')}</div>
                    </div>
                  )}
                </td>
                <td style={{ padding: '1.25rem 1.5rem', color: 'var(--text-secondary)' }}>
                  {new Date(item.deleted_at).toLocaleString('pt-BR')}
                </td>
                <td style={{ padding: '1.25rem 1.5rem', color: 'var(--text-secondary)' }}>
                  {item.deleted_by ? item.deleted_by.name : 'Sistema'}
                </td>
                <td style={{ padding: '1.25rem 1.5rem' }}>
                  <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <button onClick={() => handleRestore(type, item.id)} className="btn-primary" style={{ padding: '0.5rem', display: 'flex', alignItems: 'center', justifyContent: 'center' }} title="Restaurar">
                      <RotateCcw size={16} />
                    </button>
                    {['owner', 'manager'].includes(user?.role) && (
                      <button onClick={() => handleForceDeleteClick(type, item.id)} className="btn-primary" style={{ padding: '0.5rem', backgroundColor: 'transparent', color: '#ef4444', border: '1px solid #ef4444', display: 'flex', alignItems: 'center', justifyContent: 'center' }} title="Excluir Permanentemente">
                        <Trash2 size={16} />
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  }

  return (
    <DashboardLayout>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
        
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <h1 style={{ fontSize: '1.75rem', fontWeight: 600, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <Trash2 size={24} color="#ef4444" /> Lixeira do Sistema
            </h1>
            <p style={{ color: 'var(--text-secondary)', marginTop: '0.25rem' }}>Visualize registros excluídos, audite quem os apagou e restaure se necessário.</p>
          </div>
        </div>

        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ display: 'flex', borderBottom: '1px solid var(--border-color)', backgroundColor: 'var(--bg-tertiary)' }}>
            {['owner', 'manager'].includes(user?.role) && (
              <button 
                onClick={() => setActiveTab('patients')}
                style={tabStyle(activeTab === 'patients')}
              >
                <UserIcon size={18} /> Pacientes ({data.patients.length})
              </button>
            )}
            <button 
              onClick={() => setActiveTab('appointments')}
              style={tabStyle(activeTab === 'appointments')}
            >
              <CalendarIcon size={18} /> Consultas ({data.appointments.length})
            </button>
            {['owner', 'manager'].includes(user?.role) && (
              <button 
                onClick={() => setActiveTab('users')}
                style={tabStyle(activeTab === 'users')}
              >
                <Users size={18} /> Equipe ({data.users.length})
              </button>
            )}
          </div>

          {isLoading ? (
            <div style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Carregando lixeira...</div>
          ) : (
            <div>
              {activeTab === 'patients' && renderTable(data.patients, 'patient')}
              {activeTab === 'appointments' && renderTable(data.appointments, 'appointment')}
              {activeTab === 'users' && renderTable(data.users, 'user')}
            </div>
          )}
        </div>

      </div>

      {/* Modal de Exclusão Permanente */}
      {showForceDeleteModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 2000, padding: '2rem' }}>
          <div className="card" style={{ width: '100%', maxWidth: '400px', animation: 'fadeIn 0.2s ease-out' }}>
            <h3 style={{ fontSize: '1.25rem', fontWeight: 600, color: '#ef4444', marginBottom: '1rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <Trash2 size={20} /> Atenção
            </h3>
            <p style={{ color: 'var(--text-secondary)', fontSize: '0.95rem', marginBottom: '1.5rem', lineHeight: 1.5 }}>
              Você está prestes a excluir este registro de forma <strong>definitiva</strong>. Ele não poderá ser recuperado. Por favor, confirme com sua senha de gerente.
            </p>
            <form onSubmit={submitForceDelete} style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
              <PasswordInput
                id="forceDeletePassword"
                placeholder="Sua senha de gerente" 
                value={forceDeletePassword}
                onChange={e => setForceDeletePassword(e.target.value)}
                required
                style={{ width: '100%' }}
              />
              <div style={{ display: 'flex', gap: '1rem', marginTop: '0.5rem' }}>
                <button type="button" onClick={() => { setShowForceDeleteModal(false); setForceDeletePassword(''); setItemToDelete(null); }} className="btn-primary" style={{ flex: 1, backgroundColor: 'transparent', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}>Cancelar</button>
                <button type="submit" className="btn-primary" disabled={isDeleting} style={{ flex: 1, backgroundColor: '#ef4444' }}>
                  {isDeleting ? 'Processando...' : 'Excluir Definitivamente'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
      
      <style jsx global>{`
        @keyframes fadeIn {
          from { opacity: 0; transform: scale(0.95) translateY(10px); }
          to { opacity: 1; transform: scale(1) translateY(0); }
        }
      `}</style>
    </DashboardLayout>
  )
}

const tabStyle = (isActive) => ({
  flex: 1,
  padding: '1.25rem',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: '0.5rem',
  backgroundColor: isActive ? 'var(--bg-secondary)' : 'transparent',
  color: isActive ? 'var(--accent)' : 'var(--text-secondary)',
  fontWeight: isActive ? 600 : 500,
  border: 'none',
  borderBottom: isActive ? '2px solid var(--accent)' : '2px solid transparent',
  cursor: 'pointer',
  transition: 'all 0.2s',
})
