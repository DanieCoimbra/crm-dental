'use client'

import { useState, useEffect } from 'react'
import { useAuth } from '@/hooks/auth'
import axios from '@/lib/axios'
import DashboardLayout from '@/components/DashboardLayout'
import PatientEMR from '@/components/PatientEMR'
import CustomDatePicker from '@/components/CustomDatePicker'
import PasswordInput from '@/components/PasswordInput'
import { useAlert } from '@/components/AlertContext'
import { Search, X, User as UserIcon, FileText, Phone, Mail, MapPin, Hash, FileHeart, Shield, Calendar, Upload, Download, FileSpreadsheet } from 'lucide-react'

export default function PatientsPage() {
  const { user } = useAuth({ middleware: 'auth' })
  const { showAlert, showConfirm } = useAlert()
  const [patients, setPatients] = useState([])
  const [searchTerm, setSearchTerm] = useState('')

  const calculateAge = (birthDate) => {
    if (!birthDate) return '';
    const today = new Date();
    const birth = new Date(birthDate);
    let age = today.getFullYear() - birth.getFullYear();
    const m = today.getMonth() - birth.getMonth();
    if (m < 0 || (m === 0 && today.getDate() < birth.getDate())) {
      age--;
    }
    return `${age} anos`;
  }
  
  // Modal State
  const [selectedPatient, setSelectedPatient] = useState(null)
  const [activeTab, setActiveTab] = useState('gerais') // 'gerais' ou 'prontuario'
  const [isSaving, setIsSaving] = useState(false)
  const [hasHealthInsurance, setHasHealthInsurance] = useState(false)
  
  // Delete State
  const [showDeleteModal, setShowDeleteModal] = useState(false)
  const [deletePassword, setDeletePassword] = useState('')
  const [isDeleting, setIsDeleting] = useState(false)
  const [trashStatus, setTrashStatus] = useState(null)
  
  // Form State
  const [formData, setFormData] = useState({
    name: '',
    cpf: '',
    email: '',
    phone: '',
    address: '',
    birth_date: '',
    health_insurance: '',
    medical_history: ''
  })
  const [formErrors, setFormErrors] = useState({})

  useEffect(() => {
    if (user) fetchData()
  }, [user])

  const fetchData = async () => {
    try {
      const res = await axios.get('/api/patients')
      setPatients(res.data)
      const trashRes = await axios.get('/api/trash/status')
      setTrashStatus(trashRes.data)
    } catch (err) {
      console.error(err)
    }
  }

  const handleSearch = (e) => setSearchTerm(e.target.value)

  const filteredPatients = patients.filter(p => {
    const term = searchTerm.toLowerCase()
    return (
      (p.name && p.name.toLowerCase().includes(term)) || 
      (p.cpf && p.cpf.toLowerCase().includes(term))
    )
  })

  const openPatientModal = (patient = null) => {
    if (patient) {
      setSelectedPatient(patient)
      setHasHealthInsurance(!!patient.health_insurance)
      setFormData({
        name: patient.name || '',
        cpf: patient.cpf || '',
        email: patient.email || '',
        phone: patient.phone || '',
        address: patient.address || '',
        birth_date: patient.birth_date ? patient.birth_date.split('T')[0] : '',
        health_insurance: patient.health_insurance || '',
        medical_history: patient.medical_history || ''
      })
    } else {
      setSelectedPatient({ isNew: true })
      setHasHealthInsurance(false)
      setFormData({
        name: '',
        cpf: '',
        email: '',
        phone: '',
        address: '',
        birth_date: '',
        health_insurance: '',
        medical_history: ''
      })
    }
      setFormErrors({})
      setActiveTab(user.role === 'doctor' && patient ? 'prontuario' : 'gerais')
    }

  const closePatientModal = () => {
    setSelectedPatient(null)
  }

  const handleInputChange = (e) => {
    let { name, value } = e.target

    if (name === 'cpf') {
      value = value.replace(/\D/g, '') // remove não-dígitos
      if (value.length > 11) value = value.slice(0, 11)
      
      // Aplica a máscara 000.000.000-00
      value = value.replace(/(\d{3})(\d)/, '$1.$2')
      value = value.replace(/(\d{3})(\d)/, '$1.$2')
      value = value.replace(/(\d{3})(\d{1,2})$/, '$1-$2')
    }

    if (name === 'phone') {
      value = value.replace(/\D/g, '') // remove não-dígitos
      if (value.length > 11) value = value.slice(0, 11)
      
      // Aplica a máscara (00) 00000-0000 ou (00) 0000-0000
      value = value.replace(/^(\d{2})(\d)/g, '($1) $2')
      value = value.replace(/(\d)(\d{4})$/, '$1-$2')
    }

    setFormData(prev => ({ ...prev, [name]: value }))
    // Limpa o erro do campo ao digitar
    setFormErrors(prev => ({ ...prev, [name]: null }))
  }

  const handleSave = async (e) => {
    e.preventDefault()
    
    let errors = {}
    
    // Validações de campos vazios
    if (!formData.name.trim()) errors.name = 'O nome é obrigatório.'
    if (!formData.cpf.trim()) errors.cpf = 'O CPF é obrigatório.'
    if (!formData.phone.trim()) errors.phone = 'O telefone é obrigatório.'
    if (!formData.email.trim()) errors.email = 'O e-mail é obrigatório.'
    if (hasHealthInsurance && !formData.health_insurance.trim()) errors.health_insurance = 'O nome do plano é obrigatório.'

    // Validação de E-mail
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    if (formData.email.trim() && !emailRegex.test(formData.email)) {
      errors.email = 'Insira um endereço de e-mail válido.'
    }

    // Validações de formato CPF e Telefone
    if (formData.cpf.trim()) {
      const cpfDigits = formData.cpf.replace(/\D/g, '')
      if (cpfDigits.length !== 11) {
        errors.cpf = 'O CPF deve conter exatamente 11 números.'
      }
    }

    if (formData.phone.trim()) {
      const phoneDigits = formData.phone.replace(/\D/g, '')
      if (phoneDigits.length < 10 || phoneDigits.length > 11) {
        errors.phone = 'O telefone deve conter o DDD e o número (10 ou 11 números).'
      }
    }

    if (Object.keys(errors).length > 0) {
      setFormErrors(errors)
      return
    }

    setIsSaving(true)
    try {
      if (selectedPatient.isNew) {
        await axios.post('/api/patients', formData)
        showAlert('Paciente cadastrado com sucesso!', 'success')
      } else {
        await axios.put(`/api/patients/${selectedPatient.id}`, formData)
        showAlert('Paciente atualizado com sucesso!', 'success')
      }
      fetchData() // Refresh list
      closePatientModal()
    } catch (err) {
      console.error(err)
      if (err.response?.status === 422) {
        const errors = err.response.data.errors
        const errorMessages = Object.values(errors).flat().join('\n')
        showAlert('Erro de Validação:\n' + errorMessages, 'error')
      } else {
        showAlert(selectedPatient.isNew ? 'Erro ao cadastrar paciente.' : 'Erro ao atualizar paciente.', 'error')
      }
    } finally {
      setIsSaving(false)
    }
  }

  const handleDeleteSubmit = async (e) => {
    e.preventDefault()
    setIsDeleting(true)
    try {
      await axios.delete(`/api/patients/${selectedPatient.id}`, {
        data: { password: deletePassword }
      })
      fetchData()
      setShowDeleteModal(false)
      setSelectedPatient(null)
      showAlert('Paciente excluído com sucesso!', 'success')
    } catch (err) {
      if (err.response?.status === 422) {
        showAlert('Senha incorreta.', 'error')
      } else {
        showAlert(err.response?.data?.message || 'Erro ao excluir paciente.', 'error')
      }
    } finally {
      setIsDeleting(false)
      setDeletePassword('')
    }
  }

  if (!user) return null

  return (
    <DashboardLayout>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
        
        {/* Header e Busca */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <h1 style={{ fontSize: '1.75rem', fontWeight: 600, color: 'var(--text-primary)' }}>Gerenciamento de Pacientes</h1>
            <p style={{ color: 'var(--text-secondary)', marginTop: '0.25rem' }}>Pesquise e gerencie as fichas dos seus pacientes.</p>
          </div>
          
          <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
            <div style={{ position: 'relative', width: '350px' }}>
              <div style={{ position: 'absolute', left: '1rem', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-secondary)' }}>
                <Search size={18} />
              </div>
              <input 
                type="text" 
                placeholder="Buscar por Nome ou CPF..." 
                value={searchTerm}
                onChange={handleSearch}
                style={{
                  width: '100%',
                  padding: '0.75rem 1rem 0.75rem 2.75rem',
                  borderRadius: '12px',
                  border: '1px solid var(--border-color)',
                  backgroundColor: 'var(--bg-secondary)',
                  color: 'var(--text-primary)',
                  fontSize: '0.95rem',
                  outline: 'none',
                  boxShadow: '0 2px 4px rgba(0,0,0,0.02)',
                  transition: 'all 0.2s'
                }}
                onFocus={(e) => e.target.style.borderColor = 'var(--accent)'}
                onBlur={(e) => e.target.style.borderColor = 'var(--border-color)'}
              />
            </div>
            {user.role !== 'doctor' && (
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button onClick={() => openPatientModal()} className="btn-primary" style={{ padding: '0.75rem 1.5rem', display: 'flex', alignItems: 'center', gap: '0.5rem', whiteSpace: 'nowrap' }}>
                  <UserIcon size={18} /> Novo Paciente
                </button>
              </div>
            )}
          </div>
        </div>

        {/* Tabela de Pacientes */}
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ backgroundColor: 'var(--bg-tertiary)', borderBottom: '1px solid var(--border-color)' }}>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Paciente</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>CPF</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Contato</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Ficha</th>
                </tr>
              </thead>
              <tbody>
                {filteredPatients.length === 0 ? (
                  <tr>
                    <td colSpan="4" style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Nenhum paciente encontrado.</td>
                  </tr>
                ) : (
                  filteredPatients.map((patient) => (
                    <tr key={patient.id} style={{ borderBottom: '1px solid var(--border-color)', transition: 'background-color 0.2s' }} onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'} onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}>
                      <td style={{ padding: '1.25rem 1.5rem' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                          <div style={{ width: 40, height: 40, borderRadius: '50%', backgroundColor: 'rgba(37, 99, 235, 0.1)', color: 'var(--accent)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 'bold' }}>
                            {patient.name ? patient.name.charAt(0).toUpperCase() : 'P'}
                          </div>
                          <div style={{ display: 'flex', flexDirection: 'column' }}>
                            <span style={{ fontWeight: 500 }}>{patient.name}</span>
                            {patient.birth_date && (
                              <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>{calculateAge(patient.birth_date)}</span>
                            )}
                          </div>
                        </div>
                      </td>
                      <td style={{ padding: '1.25rem 1.5rem', color: 'var(--text-secondary)' }}>{patient.cpf || 'Não informado'}</td>
                      <td style={{ padding: '1.25rem 1.5rem', color: 'var(--text-secondary)' }}>{patient.phone || patient.email || 'Não informado'}</td>
                      <td style={{ padding: '1.25rem 1.5rem' }}>
                        <button onClick={() => openPatientModal(patient)} className="btn-primary" style={{ padding: '0.5rem 1rem', fontSize: '0.875rem' }}>Abrir</button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {/* Modal Central */}
      {selectedPatient && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '2rem' }}>
          
          <div className="card" style={{ width: '100%', maxWidth: '800px', maxHeight: '90vh', display: 'flex', flexDirection: 'column', padding: 0, overflow: 'hidden', backgroundColor: 'var(--bg-secondary)', animation: 'fadeIn 0.2s ease-out' }}>
            
            {/* Header do Modal */}
            <div style={{ padding: '1.5rem 2rem', borderBottom: '1px solid var(--border-color)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', backgroundColor: 'var(--bg-tertiary)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                <div style={{ width: 48, height: 48, borderRadius: '12px', background: 'linear-gradient(135deg, var(--accent), #8b5cf6)', color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '1.25rem', fontWeight: 'bold' }}>
                  {selectedPatient.isNew ? '+' : (selectedPatient.name ? selectedPatient.name.charAt(0).toUpperCase() : 'P')}
                </div>
                <div>
                  <h2 style={{ fontSize: '1.5rem', fontWeight: 600, color: 'var(--text-primary)' }}>{selectedPatient.isNew ? 'Novo Paciente' : selectedPatient.name}</h2>
                  {!selectedPatient.isNew && <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem' }}>Paciente ID: #{selectedPatient.id} {selectedPatient.birth_date ? `| ${calculateAge(selectedPatient.birth_date)}` : ''}</p>}
                </div>
              </div>
              <button type="button" onClick={closePatientModal} style={{ color: 'var(--text-secondary)', padding: '0.5rem', borderRadius: '50%', backgroundColor: 'transparent', transition: 'all 0.2s', border: 'none', cursor: 'pointer' }} onMouseEnter={e => e.currentTarget.style.backgroundColor = 'var(--border-color)'} onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}>
                <X size={24} />
              </button>
            </div>

            {/* Abas */}
            <div style={{ display: 'flex', borderBottom: '1px solid var(--border-color)', padding: '0 2rem' }}>
              <button 
                type="button"
                onClick={() => setActiveTab('gerais')}
                style={{
                  padding: '1rem 1.5rem',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '0.5rem',
                  color: activeTab === 'gerais' ? 'var(--accent)' : 'var(--text-secondary)',
                  fontWeight: activeTab === 'gerais' ? 600 : 500,
                  borderBottom: activeTab === 'gerais' ? '2px solid var(--accent)' : '2px solid transparent',
                  transition: 'all 0.2s',
                  background: 'transparent',
                  borderTop: 'none', borderLeft: 'none', borderRight: 'none',
                  cursor: 'pointer'
                }}
              >
                <UserIcon size={18} /> Dados Gerais
              </button>

              {/* RBAC: Aba de Prontuário apenas para médicos e gerentes, e apenas para pacientes já salvos */}
              {['doctor', 'manager'].includes(user.role) && !selectedPatient.isNew && (
                <button 
                  type="button"
                  onClick={() => setActiveTab('prontuario')}
                  style={{
                    padding: '1rem 1.5rem',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '0.5rem',
                    color: activeTab === 'prontuario' ? 'var(--accent)' : 'var(--text-secondary)',
                    fontWeight: activeTab === 'prontuario' ? 600 : 500,
                    borderBottom: activeTab === 'prontuario' ? '2px solid var(--accent)' : '2px solid transparent',
                    transition: 'all 0.2s',
                    background: 'transparent',
                    borderTop: 'none', borderLeft: 'none', borderRight: 'none',
                    cursor: 'pointer'
                  }}
                >
                  <FileHeart size={18} /> Prontuário Médico
                </button>
              )}
            </div>

            {/* Conteúdo do Modal (Formulário) */}
            <form onSubmit={handleSave} noValidate style={{ flex: 1, overflowY: 'auto', padding: '2rem' }}>
              
              {activeTab === 'gerais' ? (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><UserIcon size={14}/> Nome Completo <span style={{ color: '#ef4444' }}>*</span></label>
                    <input type="text" name="name" value={formData.name} onChange={handleInputChange} disabled={user.role === 'doctor'} style={{ ...inputStyle, borderColor: formErrors.name ? '#ef4444' : 'var(--border-color)', opacity: user.role === 'doctor' ? 0.7 : 1 }} />
                    {formErrors.name && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.name}</span>}
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Hash size={14}/> CPF <span style={{ color: '#ef4444' }}>*</span></label>
                    <input type="text" name="cpf" value={formData.cpf} onChange={handleInputChange} disabled={user.role === 'doctor'} maxLength="14" placeholder="000.000.000-00" style={{ ...inputStyle, borderColor: formErrors.cpf ? '#ef4444' : 'var(--border-color)', opacity: user.role === 'doctor' ? 0.7 : 1 }} />
                    {formErrors.cpf && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.cpf}</span>}
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Phone size={14}/> Telefone <span style={{ color: '#ef4444' }}>*</span></label>
                    <input type="text" name="phone" value={formData.phone} onChange={handleInputChange} disabled={user.role === 'doctor'} maxLength="15" placeholder="(00) 00000-0000" style={{ ...inputStyle, borderColor: formErrors.phone ? '#ef4444' : 'var(--border-color)', opacity: user.role === 'doctor' ? 0.7 : 1 }} />
                    {formErrors.phone && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.phone}</span>}
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Mail size={14}/> E-mail <span style={{ color: '#ef4444' }}>*</span></label>
                    <input type="email" name="email" value={formData.email} onChange={handleInputChange} disabled={user.role === 'doctor'} style={{ ...inputStyle, borderColor: formErrors.email ? '#ef4444' : 'var(--border-color)', opacity: user.role === 'doctor' ? 0.7 : 1 }} />
                    {formErrors.email && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.email}</span>}
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Calendar size={14}/> Data de Nascimento</label>
                    <CustomDatePicker 
                      selected={formData.birth_date ? new Date(formData.birth_date + 'T00:00:00') : null} 
                      onChange={(date) => {
                        const formattedDate = date ? date.toISOString().split('T')[0] : '';
                        setFormData(prev => ({ ...prev, birth_date: formattedDate }));
                      }}
                      disabled={user.role === 'doctor'} 
                    />
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', gridColumn: '1 / -1' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><MapPin size={14}/> Endereço</label>
                    <input type="text" name="address" value={formData.address} onChange={handleInputChange} disabled={user.role === 'doctor'} style={{...inputStyle, opacity: user.role === 'doctor' ? 0.7 : 1}} />
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', gridColumn: '1 / -1' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Shield size={14}/> Possui Plano de Saúde?</label>
                    <div style={{ display: 'flex', gap: '1.5rem', marginTop: '0.25rem' }}>
                      <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: user.role === 'doctor' ? 'default' : 'pointer', fontSize: '0.9rem', color: 'var(--text-primary)' }}>
                        <input type="radio" name="has_insurance" checked={hasHealthInsurance} onChange={() => setHasHealthInsurance(true)} disabled={user.role === 'doctor'} style={{ cursor: user.role === 'doctor' ? 'default' : 'pointer' }} /> Sim
                      </label>
                      <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: user.role === 'doctor' ? 'default' : 'pointer', fontSize: '0.9rem', color: 'var(--text-primary)' }}>
                        <input type="radio" name="has_insurance" checked={!hasHealthInsurance} onChange={() => { setHasHealthInsurance(false); setFormData(prev => ({...prev, health_insurance: ''})) }} disabled={user.role === 'doctor'} style={{ cursor: user.role === 'doctor' ? 'default' : 'pointer' }} /> Não (Particular)
                      </label>
                    </div>
                  </div>
                  
                  {hasHealthInsurance && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', gridColumn: '1 / -1', animation: 'fadeIn 0.2s ease-out' }}>
                      <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>Nome do Plano <span style={{ color: '#ef4444' }}>*</span></label>
                      <input type="text" name="health_insurance" value={formData.health_insurance} onChange={handleInputChange} disabled={user.role === 'doctor'} placeholder="Ex: Unimed, Bradesco Saúde, Amil..." style={{ ...inputStyle, borderColor: formErrors.health_insurance ? '#ef4444' : 'var(--border-color)', opacity: user.role === 'doctor' ? 0.7 : 1 }} />
                      {formErrors.health_insurance && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.health_insurance}</span>}
                    </div>
                  )}
                </div>
              ) : (
                <div style={{ height: '600px' }}>
                  <PatientEMR patientId={selectedPatient.id} user={user} showAlert={showAlert} />
                </div>
              )}

              {/* Rodapé com botões de ação */}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '2.5rem', paddingTop: '1.5rem', borderTop: '1px solid var(--border-color)' }}>
                <div>
                  {selectedPatient && (['owner', 'manager', 'receptionist'].includes(user?.role)) && (
                    <button 
                      type="button" 
                      onClick={() => setShowDeleteModal(true)} 
                      disabled={trashStatus?.is_full}
                      className="btn-primary" 
                      style={{ 
                        backgroundColor: 'transparent', 
                        color: trashStatus?.is_full ? 'var(--text-secondary)' : '#ef4444', 
                        border: trashStatus?.is_full ? '1px solid var(--border-color)' : '1px solid #ef4444', 
                        fontSize: '0.875rem', 
                        padding: '0.6rem 1rem',
                        cursor: trashStatus?.is_full ? 'not-allowed' : 'pointer'
                      }}
                      title={trashStatus?.is_full ? 'A lixeira está cheia. Esvazie para excluir.' : ''}
                    >
                      {trashStatus?.is_full ? 'Exclusão Bloqueada (Lixeira Cheia)' : 'Excluir Paciente'}
                    </button>
                  )}
                </div>
                <div style={{ display: 'flex', gap: '1rem' }}>
                  <button type="button" onClick={closePatientModal} className="btn-primary" style={{ backgroundColor: 'transparent', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}>
                    {user.role === 'doctor' ? 'Fechar' : 'Cancelar'}
                  </button>
                  {user.role !== 'doctor' && (
                    <button type="submit" className="btn-primary" disabled={isSaving}>
                      {isSaving ? 'Salvando...' : 'Salvar Alterações'}
                    </button>
                  )}
                </div>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal de Exclusão */}
      {showDeleteModal && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '1rem' }}>
          <div style={{ backgroundColor: 'var(--bg-card)', padding: '2rem', borderRadius: '12px', width: '100%', maxWidth: '400px' }}>
            <h3 style={{ fontSize: '1.25rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '1rem' }}>Confirmar Exclusão</h3>
            <p style={{ color: 'var(--text-secondary)', marginBottom: '1.5rem', lineHeight: 1.5 }}>
              Para excluir o paciente <strong>{selectedPatient?.name}</strong>, por favor, insira sua senha de usuário.
            </p>
            <PasswordInput
              value={deletePassword}
              onChange={(e) => setDeletePassword(e.target.value)}
              placeholder="Sua senha"
            />
            <div style={{ display: 'flex', gap: '1rem', marginTop: '1.5rem' }}>
              <button onClick={() => { setShowDeleteModal(false); setDeletePassword(''); }} className="btn-secondary" style={{ flex: 1 }}>Cancelar</button>
              <button onClick={handleDeleteSubmit} disabled={isDeleting || !deletePassword} className="btn-primary" style={{ flex: 1, backgroundColor: '#ef4444' }}>
                {isDeleting ? 'Excluindo...' : 'Excluir'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Estilo para animação do modal */}
      <style jsx global>{`
        @keyframes fadeIn {
          from { opacity: 0; transform: scale(0.95) translateY(10px); }
          to { opacity: 1; transform: scale(1) translateY(0); }
        }
      `}</style>
    </DashboardLayout>
  )
}

const inputStyle = {
  padding: '0.85rem',
  borderRadius: '8px',
  border: '1px solid var(--border-color)',
  backgroundColor: 'var(--bg-tertiary)',
  color: 'var(--text-primary)',
  fontSize: '0.95rem',
  outline: 'none',
  transition: 'border-color 0.2s',
}
