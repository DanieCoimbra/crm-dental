'use client'

import { useState, useEffect } from 'react'
import axios from '@/lib/axios'
import DashboardLayout from '@/components/DashboardLayout'
import { Plus, Check, Clock, Phone, AlertCircle, RefreshCw, Trash2, X } from 'lucide-react'

export default function WaitlistPage() {
  const [waitlists, setWaitlists] = useState([])
  const [patients, setPatients] = useState([])
  const [appointmentTypes, setAppointmentTypes] = useState([])
  const [doctors, setDoctors] = useState([])
  
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [isLoading, setIsLoading] = useState(true)
  
  // Form State
  const [patientId, setPatientId] = useState('')
  const [doctorId, setDoctorId] = useState('')
  const [appointmentTypeId, setAppointmentTypeId] = useState('')
  const [preferredDays, setPreferredDays] = useState([])
  const [preferredTimeRange, setPreferredTimeRange] = useState('qualquer')
  const [urgencyLevel, setUrgencyLevel] = useState('low')
  const [notes, setNotes] = useState('')

  const daysOfWeek = ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', 'Domingo']

  useEffect(() => {
    fetchData()
  }, [])

  const fetchData = async () => {
    setIsLoading(true)
    try {
      const [waitlistRes, patientsRes, typesRes, usersRes] = await Promise.all([
        axios.get('/api/waitlists'),
        axios.get('/api/patients'),
        axios.get('/api/appointment-types'),
        axios.get('/api/users?role=doctor')
      ])
      setWaitlists(waitlistRes.data)
      setPatients(patientsRes.data)
      setAppointmentTypes(typesRes.data)
      setDoctors(usersRes.data)
    } catch (error) {
      console.error('Failed to load waitlist data', error)
    }
    setIsLoading(false)
  }

  const handleAddWaitlist = async (e) => {
    e.preventDefault()
    try {
      await axios.post('/api/waitlists', {
        patient_id: patientId,
        doctor_id: doctorId || null,
        appointment_type_id: appointmentTypeId || null,
        preferred_days: preferredDays,
        preferred_time_range: preferredTimeRange,
        urgency_level: urgencyLevel,
        notes: notes
      })
      setIsModalOpen(false)
      resetForm()
      fetchData()
    } catch (error) {
      console.error('Failed to add to waitlist', error)
      alert('Erro ao adicionar à fila de espera.')
    }
  }

  const handleResolve = async (id) => {
    if (!confirm('Deseja marcar este paciente como resolvido (agendamento feito)?')) return
    try {
      await axios.put(`/api/waitlists/${id}`, { status: 'resolved' })
      fetchData()
    } catch (error) {
      console.error('Failed to resolve waitlist entry', error)
    }
  }

  const handleDelete = async (id) => {
    if (!confirm('Deseja remover este paciente da fila?')) return
    try {
      await axios.delete(`/api/waitlists/${id}`)
      fetchData()
    } catch (error) {
      console.error('Failed to delete waitlist entry', error)
    }
  }

  const resetForm = () => {
    setPatientId('')
    setDoctorId('')
    setAppointmentTypeId('')
    setPreferredDays([])
    setPreferredTimeRange('qualquer')
    setUrgencyLevel('low')
    setNotes('')
  }

  const toggleDay = (day) => {
    setPreferredDays(prev => 
      prev.includes(day) ? prev.filter(d => d !== day) : [...prev, day]
    )
  }

  const getUrgencyBadge = (level) => {
    switch (level) {
      case 'high': return <span style={{ padding: '4px 8px', backgroundColor: '#fee2e2', color: '#991b1b', borderRadius: '9999px', fontSize: '0.75rem', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: '4px' }}><AlertCircle size={14}/> Alta</span>
      case 'medium': return <span style={{ padding: '4px 8px', backgroundColor: '#fef3c7', color: '#92400e', borderRadius: '9999px', fontSize: '0.75rem', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: '4px' }}><Clock size={14}/> Média</span>
      case 'low': return <span style={{ padding: '4px 8px', backgroundColor: '#dcfce7', color: '#166534', borderRadius: '9999px', fontSize: '0.75rem', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: '4px' }}>Baixa</span>
      default: return null
    }
  }

  return (
    <DashboardLayout>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <h1 style={{ fontSize: '1.75rem', fontWeight: 600, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <Clock color="var(--accent)" />
          Fila de Espera
        </h1>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button 
            onClick={fetchData}
            className="btn-primary"
            style={{ backgroundColor: 'transparent', border: '1px solid var(--border-color)', color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}
          >
            <RefreshCw size={18} /> Atualizar
          </button>
          <button 
            onClick={() => setIsModalOpen(true)}
            className="btn-primary"
            style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}
          >
            <Plus size={18} /> Adicionar Paciente
          </button>
        </div>
      </div>

      <div className="card" style={{ padding: '0', overflow: 'hidden' }}>
        {isLoading ? (
          <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Carregando fila...</div>
        ) : waitlists.length === 0 ? (
          <div style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-secondary)', display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
            <Clock size={48} style={{ opacity: 0.3, marginBottom: '1rem' }} />
            <p style={{ fontSize: '1.125rem' }}>A fila de espera está vazia no momento.</p>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', textAlign: 'left', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ backgroundColor: 'var(--bg-tertiary)', borderBottom: '1px solid var(--border-color)', color: 'var(--text-secondary)' }}>
                  <th style={{ padding: '1rem', fontWeight: 600 }}>Urgência</th>
                  <th style={{ padding: '1rem', fontWeight: 600 }}>Paciente & Contato</th>
                  <th style={{ padding: '1rem', fontWeight: 600 }}>Médico & Especialidade</th>
                  <th style={{ padding: '1rem', fontWeight: 600 }}>Preferência</th>
                  <th style={{ padding: '1rem', fontWeight: 600 }}>Status</th>
                  <th style={{ padding: '1rem', fontWeight: 600, textAlign: 'center' }}>Ações</th>
                </tr>
              </thead>
              <tbody>
                {waitlists.map(item => (
                  <tr key={item.id} style={{ borderBottom: '1px solid var(--border-color)', transition: 'background-color 0.2s' }}>
                    <td style={{ padding: '1rem' }}>
                      {getUrgencyBadge(item.urgency_level)}
                    </td>
                    <td style={{ padding: '1rem' }}>
                      <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{item.patient?.name}</div>
                      <div style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.25rem', marginTop: '0.25rem' }}>
                        <Phone size={12} /> {item.patient?.phone || 'Sem telefone'}
                      </div>
                    </td>
                    <td style={{ padding: '1rem' }}>
                      <div style={{ color: 'var(--text-primary)' }}>{item.doctor?.name || 'Qualquer Médico'}</div>
                      <div style={{ fontSize: '0.875rem', color: 'var(--text-secondary)' }}>{item.appointment_type?.name || 'Qualquer Tipo'}</div>
                    </td>
                    <td style={{ padding: '1rem', fontSize: '0.875rem', color: 'var(--text-secondary)' }}>
                      <div><strong style={{ color: 'var(--text-primary)' }}>Turno:</strong> {item.preferred_time_range === 'qualquer' ? 'Qualquer' : item.preferred_time_range === 'manhã' ? 'Manhã' : 'Tarde'}</div>
                      {item.preferred_days?.length > 0 && (
                        <div><strong style={{ color: 'var(--text-primary)' }}>Dias:</strong> {item.preferred_days.join(', ')}</div>
                      )}
                    </td>
                    <td style={{ padding: '1rem' }}>
                      {item.status === 'waiting' ? (
                        <span style={{ color: '#ea580c', fontWeight: 500, fontSize: '0.875rem', display: 'flex', alignItems: 'center', gap: '4px' }}><Clock size={14}/> Aguardando</span>
                      ) : (
                        <span style={{ color: '#16a34a', fontWeight: 500, fontSize: '0.875rem', display: 'flex', alignItems: 'center', gap: '4px' }}><Check size={14}/> Resolvido</span>
                      )}
                    </td>
                    <td style={{ padding: '1rem' }}>
                      <div style={{ display: 'flex', justifyContent: 'center', gap: '0.5rem' }}>
                        {item.status === 'waiting' && (
                          <button 
                            onClick={() => handleResolve(item.id)}
                            style={{ color: '#16a34a', padding: '0.5rem', borderRadius: '8px', border: '1px solid transparent', cursor: 'pointer', background: 'transparent' }}
                            title="Marcar como resolvido"
                          >
                            <Check size={18} />
                          </button>
                        )}
                        <button 
                          onClick={() => handleDelete(item.id)}
                          style={{ color: '#ef4444', padding: '0.5rem', borderRadius: '8px', border: '1px solid transparent', cursor: 'pointer', background: 'transparent' }}
                          title="Remover"
                        >
                          <Trash2 size={18} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {isModalOpen && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 50, padding: '1rem' }}>
          <div className="card" style={{ width: '100%', maxWidth: '500px', display: 'flex', flexDirection: 'column', maxHeight: '90vh', padding: 0 }}>
            <div style={{ padding: '1.5rem', borderBottom: '1px solid var(--border-color)', display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div>
                <h2 style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--text-primary)' }}>Adicionar à Fila de Espera</h2>
                <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem', marginTop: '0.25rem' }}>Registre as preferências do paciente para futuros encaixes.</p>
              </div>
              <button type="button" onClick={() => setIsModalOpen(false)} style={{ color: 'var(--text-secondary)', padding: '0.5rem', borderRadius: '50%', backgroundColor: 'transparent', transition: 'all 0.2s', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', marginTop: '-0.5rem', marginRight: '-0.5rem' }} onMouseEnter={e => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'} onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}>
                <X size={20} />
              </button>
            </div>
            
            <div style={{ padding: '1.5rem', overflowY: 'auto' }}>
              <form id="waitlist-form" onSubmit={handleAddWaitlist} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Paciente *</label>
                  <select 
                    required
                    value={patientId}
                    onChange={(e) => setPatientId(e.target.value)}
                    style={{ padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                  >
                    <option value="">Selecione o paciente</option>
                    {patients.map(p => (
                      <option key={p.id} value={p.id}>{p.name} - {p.phone}</option>
                    ))}
                  </select>
                </div>

                <div style={{ display: 'flex', gap: '1rem' }}>
                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Médico (Opcional)</label>
                    <select 
                      value={doctorId}
                      onChange={(e) => setDoctorId(e.target.value)}
                      style={{ padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                    >
                      <option value="">Qualquer Médico</option>
                      {doctors.map(d => (
                        <option key={d.id} value={d.id}>{d.name}</option>
                      ))}
                    </select>
                  </div>
                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Tipo de Consulta</label>
                    <select 
                      value={appointmentTypeId}
                      onChange={(e) => setAppointmentTypeId(e.target.value)}
                      style={{ padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                    >
                      <option value="">Qualquer Tipo</option>
                      {appointmentTypes.map(t => (
                        <option key={t.id} value={t.id}>{t.name}</option>
                      ))}
                    </select>
                  </div>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Nível de Emergência</label>
                  <div style={{ display: 'flex', gap: '1rem' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', color: 'var(--text-primary)' }}>
                      <input type="radio" name="urgency" value="low" checked={urgencyLevel === 'low'} onChange={() => setUrgencyLevel('low')} /> Baixa
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', color: 'var(--text-primary)' }}>
                      <input type="radio" name="urgency" value="medium" checked={urgencyLevel === 'medium'} onChange={() => setUrgencyLevel('medium')} /> Média
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', color: 'var(--text-primary)' }}>
                      <input type="radio" name="urgency" value="high" checked={urgencyLevel === 'high'} onChange={() => setUrgencyLevel('high')} /> Alta
                    </label>
                  </div>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Dias Preferenciais</label>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
                    {daysOfWeek.map(day => (
                      <button
                        key={day}
                        type="button"
                        onClick={() => toggleDay(day)}
                        style={{
                          padding: '0.25rem 0.75rem',
                          borderRadius: '9999px',
                          fontSize: '0.875rem',
                          fontWeight: 500,
                          transition: 'all 0.2s',
                          border: preferredDays.includes(day) ? '1px solid var(--accent)' : '1px solid transparent',
                          backgroundColor: preferredDays.includes(day) ? 'var(--accent)' : 'var(--bg-tertiary)',
                          color: preferredDays.includes(day) ? '#fff' : 'var(--text-secondary)'
                        }}
                      >
                        {day}
                      </button>
                    ))}
                  </div>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Turno Preferencial</label>
                  <select 
                    value={preferredTimeRange}
                    onChange={(e) => setPreferredTimeRange(e.target.value)}
                    style={{ padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                  >
                    <option value="qualquer">Qualquer Horário</option>
                    <option value="manhã">Apenas Manhã</option>
                    <option value="tarde">Apenas Tarde</option>
                  </select>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Observações</label>
                  <textarea 
                    value={notes}
                    onChange={(e) => setNotes(e.target.value)}
                    rows={2}
                    placeholder="Ex: Pode chegar em 20 minutos se for avisado"
                    style={{ padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                  ></textarea>
                </div>

              </form>
            </div>
            
            <div style={{ padding: '1.5rem', borderTop: '1px solid var(--border-color)', display: 'flex', justifyContent: 'flex-end', gap: '1rem', backgroundColor: 'var(--bg-tertiary)' }}>
              <button 
                type="button"
                onClick={() => { setIsModalOpen(false); resetForm(); }}
                className="btn-primary"
                style={{ backgroundColor: 'transparent', border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}
              >
                Cancelar
              </button>
              <button 
                type="submit"
                form="waitlist-form"
                className="btn-primary"
              >
                Adicionar à Fila
              </button>
            </div>
          </div>
        </div>
      )}
    </DashboardLayout>
  )
}
