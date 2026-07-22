'use client'

import { useState, useEffect } from 'react'
import { useAuth } from '@/hooks/auth'
import { useAlert } from '@/components/AlertContext'
import axios from '@/lib/axios'
import DashboardLayout from '@/components/DashboardLayout'
import PasswordInput from '@/components/PasswordInput'
import { Search, X, User as UserIcon, Mail, Phone, MapPin, Hash, Lock, Shield, Stethoscope, Briefcase } from 'lucide-react'

export default function TeamPage() {
  const { user } = useAuth({ middleware: 'auth' })
  const { showAlert, showConfirm } = useAlert()
  const [team, setTeam] = useState([])
  const [roles, setRoles] = useState([])
  const [searchTerm, setSearchTerm] = useState('')
  
  // Modal State
  const [isModalOpen, setIsModalOpen] = useState(false)
  const [selectedMember, setSelectedMember] = useState(null)
  const [isSaving, setIsSaving] = useState(false)
  
  // Delete State
  const [showDeleteModal, setShowDeleteModal] = useState(false)
  const [deletePassword, setDeletePassword] = useState('')
  const [isDeleting, setIsDeleting] = useState(false)
  const [trashStatus, setTrashStatus] = useState(null)
  
  // Form State
  const [formData, setFormData] = useState({
    name: '',
    email: '',
    password: '',
    role: 'receptionist',
    cpf: '',
    phone: '',
    address: '',
    medical_registry: '',
    ctps: ''
  })
  const [formErrors, setFormErrors] = useState({})

  useEffect(() => {
    const isManager = user && ['owner', 'manager'].includes(user.role)
    const hasPermission = user && user.permissions_list && user.permissions_list.includes('manage_team')
    
    if (user && (isManager || hasPermission)) {
      fetchData()
    }
  }, [user])

  const fetchData = async () => {
    try {
      const res = await axios.get('/api/users')
      const formattedTeam = res.data.map(u => ({
        ...u,
        role: u.role?.name || u.role
      }))
      setTeam(formattedTeam)
      const rolesRes = await axios.get('/api/roles')
      setRoles(rolesRes.data)
      const trashRes = await axios.get('/api/trash/status')
      setTrashStatus(trashRes.data)
    } catch (err) {
      console.error(err)
    }
  }

  const handleSearch = (e) => setSearchTerm(e.target.value)

  const filteredTeam = team.filter(member => {
    const term = searchTerm.toLowerCase()
    return (
      (member.name && member.name.toLowerCase().includes(term)) || 
      (member.cpf && member.cpf.toLowerCase().includes(term)) ||
      (member.medical_registry && member.medical_registry.toLowerCase().includes(term))
    )
  })

  const openModal = (member = null) => {
    if (member) {
      setSelectedMember(member)
      setFormData({
        name: member.name || '',
        email: member.email || '',
        password: '', // Não preencher senha na edição
        role: member.role || 'receptionist',
        cpf: member.cpf || '',
        phone: member.phone || '',
        address: member.address || '',
        medical_registry: member.medical_registry || '',
        ctps: member.ctps || ''
      })
    } else {
      setSelectedMember(null)
      setFormData({
        name: '',
        email: '',
        password: '',
        role: 'receptionist',
        cpf: '',
        phone: '',
        address: '',
        medical_registry: '',
        ctps: ''
      })
    }
    setFormErrors({})
    setIsModalOpen(true)
  }

  const closeModal = () => {
    setIsModalOpen(false)
    setSelectedMember(null)
  }

  const handleInputChange = (e) => {
    let { name, value } = e.target

    if (name === 'cpf') {
      value = value.replace(/\D/g, '')
      if (value.length > 11) value = value.slice(0, 11)
      value = value.replace(/(\d{3})(\d)/, '$1.$2')
      value = value.replace(/(\d{3})(\d)/, '$1.$2')
      value = value.replace(/(\d{3})(\d{1,2})$/, '$1-$2')
    }

    if (name === 'phone') {
      value = value.replace(/\D/g, '')
      if (value.length > 11) value = value.slice(0, 11)
      value = value.replace(/^(\d{2})(\d)/g, '($1) $2')
      value = value.replace(/(\d)(\d{4})$/, '$1-$2')
    }

    setFormData(prev => ({ ...prev, [name]: value }))
    setFormErrors(prev => ({ ...prev, [name]: null }))
  }

  const handleSave = async (e) => {
    e.preventDefault()

    let errors = {}
    
    if (!formData.name.trim()) errors.name = 'O nome é obrigatório.'
    if (!formData.email.trim()) errors.email = 'O e-mail é obrigatório.'
    if (!selectedMember && !formData.password) errors.password = 'A senha é obrigatória.'
    if (!formData.role) errors.role = 'A função é obrigatória.'
    if (!formData.cpf.trim()) errors.cpf = 'O CPF é obrigatório.'
    if (!formData.ctps.trim()) errors.ctps = 'A CTPS é obrigatória.'
    if (formData.role === 'doctor' && !formData.medical_registry.trim()) {
      errors.medical_registry = 'O CRM é obrigatório para médicos.'
    }

    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    if (formData.email.trim() && !emailRegex.test(formData.email)) {
      errors.email = 'Insira um endereço de e-mail válido.'
    }

    if (formData.cpf && formData.cpf.trim()) {
      const cpfDigits = formData.cpf.replace(/\D/g, '')
      if (cpfDigits.length !== 11) {
        errors.cpf = 'O CPF deve conter exatamente 11 números.'
      }
    }

    if (formData.phone && formData.phone.trim()) {
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
      if (selectedMember) {
        await axios.put(`/api/team/${selectedMember.id}`, formData)
        showAlert('Funcionário atualizado com sucesso!', 'success')
      } else {
        await axios.post('/api/team', formData)
        showAlert('Funcionário criado com sucesso!', 'success')
      }
      fetchData()
      closeModal()
    } catch (err) {
      console.error(err)
      showAlert(err.response?.data?.message || 'Erro ao salvar funcionário.', 'error')
    } finally {
      setIsSaving(false)
    }
  }

  const handleDeleteSubmit = async (e) => {
    e.preventDefault()
    setIsDeleting(true)
    try {
      await axios.delete(`/api/team/${selectedMember.id}`, {
        data: { password: deletePassword }
      })
      showAlert('Funcionário excluído com sucesso!', 'success')
      setShowDeleteModal(false)
      closeModal()
      fetchData()
    } catch (err) {
      if (err.response?.status === 422) {
        showAlert('Senha incorreta.', 'error')
      } else {
        showAlert(err.response?.data?.message || 'Erro ao excluir funcionário.', 'error')
      }
    } finally {
      setIsDeleting(false)
      setDeletePassword('')
    }
  }

  const roleLabels = {
    doctor: 'Médico(a)',
    receptionist: 'Recepcionista',
    owner: 'Dono', manager: 'Gerente'
  }

  const isManager = user && ['owner', 'manager'].includes(user.role)
  const hasPermission = user && user.permissions_list && user.permissions_list.includes('manage_team')

  if (!user || (!isManager && !hasPermission)) {
    return (
      <DashboardLayout>
        <div style={{ padding: '2rem', textAlign: 'center' }}>
          <h2>Acesso Restrito</h2>
          <p>Você não tem permissão para acessar a Gestão de Equipe.</p>
        </div>
      </DashboardLayout>
    )
  }

  return (
    <DashboardLayout>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
        
        {/* Header e Busca */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <h1 style={{ fontSize: '1.75rem', fontWeight: 600, color: 'var(--text-primary)' }}>Gestão de Equipe</h1>
            <p style={{ color: 'var(--text-secondary)', marginTop: '0.25rem' }}>Administre os funcionários e suas permissões de acesso.</p>
          </div>
          
          <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
            <div style={{ position: 'relative', width: '300px' }}>
              <div style={{ position: 'absolute', left: '1rem', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-secondary)' }}>
                <Search size={18} />
              </div>
              <input 
                type="text" 
                placeholder="Buscar (Nome, CPF ou CRM)..." 
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
            <button onClick={() => openModal()} className="btn-primary" style={{ padding: '0.75rem 1.5rem', whiteSpace: 'nowrap' }}>
              + Novo Funcionário
            </button>
          </div>
        </div>

        {/* Tabela de Funcionários */}
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ backgroundColor: 'var(--bg-tertiary)', borderBottom: '1px solid var(--border-color)' }}>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Funcionário</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Função</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Documentos</th>
                  <th style={{ padding: '1.25rem 1.5rem', fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Ficha</th>
                </tr>
              </thead>
              <tbody>
                {filteredTeam.length === 0 ? (
                  <tr>
                    <td colSpan="4" style={{ padding: '3rem', textAlign: 'center', color: 'var(--text-secondary)' }}>Nenhum funcionário encontrado.</td>
                  </tr>
                ) : (
                  filteredTeam.map((member) => (
                    <tr key={member.id} style={{ borderBottom: '1px solid var(--border-color)', transition: 'background-color 0.2s' }} onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'} onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}>
                      <td style={{ padding: '1.25rem 1.5rem' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                          <div style={{ width: 40, height: 40, borderRadius: '50%', backgroundColor: 'rgba(139, 92, 246, 0.1)', color: '#8b5cf6', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 'bold', overflow: 'hidden' }}>
                            {member.avatar ? (
                              <img src={`${process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8000'}${member.avatar}`} alt="Avatar" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                            ) : (
                              member.name ? member.name.charAt(0).toUpperCase() : 'U'
                            )}
                          </div>
                          <div style={{ display: 'flex', flexDirection: 'column' }}>
                            <span style={{ fontWeight: 500 }}>{member.name}</span>
                            <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>{member.email}</span>
                          </div>
                        </div>
                      </td>
                      <td style={{ padding: '1.25rem 1.5rem' }}>
                        <span style={{ 
                          padding: '0.25rem 0.75rem', 
                          borderRadius: '999px', 
                          fontSize: '0.75rem', 
                          fontWeight: 600,
                          backgroundColor: member.role === 'owner' ? 'rgba(139, 92, 246, 0.1)' : member.role === 'manager' ? 'rgba(239, 68, 68, 0.1)' : member.role === 'doctor' ? 'rgba(16, 185, 129, 0.1)' : 'rgba(59, 130, 246, 0.1)',
                          color: member.role === 'owner' ? '#8b5cf6' : member.role === 'manager' ? '#ef4444' : member.role === 'doctor' ? '#10b981' : '#3b82f6'
                        }}>
                          {roleLabels[member.role] || (member.role ? member.role.charAt(0).toUpperCase() + member.role.slice(1) : 'Recepcionista')}
                        </span>
                      </td>
                      <td style={{ padding: '1.25rem 1.5rem', color: 'var(--text-secondary)', fontSize: '0.875rem' }}>
                        {member.cpf ? <div style={{ marginBottom: '0.25rem' }}>CPF: {member.cpf}</div> : <div style={{ marginBottom: '0.25rem' }}>Sem CPF</div>}
                        {member.role === 'doctor' && member.medical_registry && (
                          <div style={{ color: '#10b981', marginBottom: '0.25rem', fontSize: '0.75rem' }}>CRM: {member.medical_registry}</div>
                        )}
                        {member.ctps && (
                          <div style={{ fontSize: '0.75rem' }}>CTPS: {member.ctps}</div>
                        )}
                      </td>
                      <td style={{ padding: '1.25rem 1.5rem' }}>
                        <button onClick={() => openModal(member)} className="btn-primary" style={{ padding: '0.5rem 1rem', fontSize: '0.875rem', backgroundColor: 'transparent', color: 'var(--accent)', border: '1px solid var(--accent)' }}>Abrir</button>
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
      {isModalOpen && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '2rem' }}>
          
          <div className="card" style={{ width: '100%', maxWidth: '800px', maxHeight: '90vh', display: 'flex', flexDirection: 'column', padding: 0, overflow: 'hidden', backgroundColor: 'var(--bg-secondary)', animation: 'fadeIn 0.2s ease-out' }}>
            
            {/* Header do Modal */}
            <div style={{ padding: '1.5rem 2rem', borderBottom: '1px solid var(--border-color)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', backgroundColor: 'var(--bg-tertiary)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                <div style={{ width: 48, height: 48, borderRadius: '12px', background: 'linear-gradient(135deg, #3b82f6, #8b5cf6)', color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '1.25rem', fontWeight: 'bold' }}>
                  <Shield size={24} />
                </div>
                <div>
                  <h2 style={{ fontSize: '1.5rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                    {selectedMember ? 'Editar Funcionário' : 'Novo Funcionário'}
                  </h2>
                  <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem' }}>Preencha os dados e permissões do colaborador.</p>
                </div>
              </div>
              <button type="button" onClick={closeModal} style={{ color: 'var(--text-secondary)', padding: '0.5rem', borderRadius: '50%', backgroundColor: 'transparent', transition: 'all 0.2s', border: 'none', cursor: 'pointer' }} onMouseEnter={e => e.currentTarget.style.backgroundColor = 'var(--border-color)'} onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}>
                <X size={24} />
              </button>
            </div>

            {/* Conteúdo do Modal (Formulário) */}
            <form onSubmit={handleSave} noValidate style={{ flex: 1, overflowY: 'auto', padding: '2rem' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
                
                {/* Nome */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><UserIcon size={14}/> Nome Completo <span style={{ color: '#ef4444' }}>*</span></label>
                  <input type="text" name="name" value={formData.name} onChange={handleInputChange} style={{ ...inputStyle, borderColor: formErrors.name ? '#ef4444' : 'var(--border-color)' }} />
                  {formErrors.name && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.name}</span>}
                </div>

                {/* Função (Role) */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Shield size={14}/> Função (Cargo) <span style={{ color: '#ef4444' }}>*</span></label>
                  <select name="role" value={formData.role} onChange={handleInputChange} style={{ ...inputStyle, appearance: 'none', cursor: 'pointer', borderColor: formErrors.role ? '#ef4444' : 'var(--border-color)' }}>
                    {roles.length === 0 && <option value="receptionist">Recepcionista</option>}
                    {roles.map(r => (
                      <option key={r.id} value={r.name}>{roleLabels[r.name] || r.name.charAt(0).toUpperCase() + r.name.slice(1)}</option>
                    ))}
                  </select>
                  {formErrors.role && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.role}</span>}
                </div>

                {/* Email */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Mail size={14}/> E-mail (Acesso) <span style={{ color: '#ef4444' }}>*</span></label>
                  <input type="email" name="email" value={formData.email} onChange={handleInputChange} style={{ ...inputStyle, borderColor: formErrors.email ? '#ef4444' : 'var(--border-color)' }} />
                  {formErrors.email && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.email}</span>}
                </div>

                {/* Senha */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                    <Lock size={14}/> Senha {!selectedMember ? <span style={{ color: '#ef4444' }}>*</span> : '(Deixe em branco para manter)'}
                  </label>
                  <PasswordInput 
                    name="password" 
                    value={formData.password} 
                    onChange={handleInputChange} 
                    hasError={!!formErrors.password}
                    placeholder="Mínimo 8 caracteres" 
                  />
                  {formErrors.password && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.password}</span>}
                </div>

                <div style={{ gridColumn: '1 / -1', height: '1px', backgroundColor: 'var(--border-color)', margin: '0.5rem 0' }}></div>

                {/* CPF */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Hash size={14}/> CPF <span style={{ color: '#ef4444' }}>*</span></label>
                  <input type="text" name="cpf" value={formData.cpf} onChange={handleInputChange} maxLength="14" placeholder="000.000.000-00" style={{ ...inputStyle, borderColor: formErrors.cpf ? '#ef4444' : 'var(--border-color)' }} />
                  {formErrors.cpf && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.cpf}</span>}
                </div>

                {/* CTPS (Carteira de Trabalho) */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Briefcase size={14}/> Carteira de Trabalho (CTPS) <span style={{ color: '#ef4444' }}>*</span></label>
                  <input type="text" name="ctps" value={formData.ctps} onChange={handleInputChange} style={{ ...inputStyle, borderColor: formErrors.ctps ? '#ef4444' : 'var(--border-color)' }} />
                  {formErrors.ctps && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.ctps}</span>}
                </div>

                {/* Telefone */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Phone size={14}/> Telefone</label>
                  <input type="text" name="phone" value={formData.phone} onChange={handleInputChange} maxLength="15" placeholder="(00) 00000-0000" style={{ ...inputStyle, borderColor: formErrors.phone ? '#ef4444' : 'var(--border-color)' }} />
                  {formErrors.phone && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.phone}</span>}
                </div>

                {/* CRM - Só exibe se for Médico */}
                {formData.role === 'doctor' ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 600, color: '#10b981', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Stethoscope size={14}/> Registro Médico (CRM) <span style={{ color: '#ef4444' }}>*</span></label>
                    <input type="text" name="medical_registry" value={formData.medical_registry} onChange={handleInputChange} style={{ ...inputStyle, borderColor: formErrors.medical_registry ? '#ef4444' : '#10b981' }} placeholder="Ex: CRM-SP 12345" />
                    {formErrors.medical_registry && <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: '-0.25rem' }}>{formErrors.medical_registry}</span>}
                  </div>
                ) : (
                  <div></div> /* Espaço vazio para manter o grid */
                )}

                {/* Endereço */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', gridColumn: '1 / -1' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><MapPin size={14}/> Endereço</label>
                  <input type="text" name="address" value={formData.address} onChange={handleInputChange} style={inputStyle} />
                </div>
              </div>

              {/* Rodapé com botões de ação */}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '2.5rem', paddingTop: '1.5rem', borderTop: '1px solid var(--border-color)' }}>
                <div>
                  {selectedMember && ['owner', 'manager'].includes(user.role) && user.id !== selectedMember.id && (
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
                      {trashStatus?.is_full ? 'Exclusão Bloqueada (Lixeira Cheia)' : 'Excluir Funcionário'}
                    </button>
                  )}
                </div>
                <div style={{ display: 'flex', gap: '1rem' }}>
                  <button type="button" onClick={closeModal} className="btn-primary" style={{ backgroundColor: 'transparent', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}>Cancelar</button>
                  <button type="submit" className="btn-primary" disabled={isSaving}>
                    {isSaving ? 'Salvando...' : selectedMember ? 'Salvar Alterações' : 'Criar Funcionário'}
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal de Exclusão */}
      {showDeleteModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 2000, padding: '2rem' }}>
          <div className="card" style={{ width: '100%', maxWidth: '400px', animation: 'fadeIn 0.2s ease-out' }}>
            <h3 style={{ fontSize: '1.25rem', fontWeight: 600, color: 'var(--text-primary)', marginBottom: '1rem' }}>Confirmar Exclusão</h3>
            <p style={{ color: 'var(--text-secondary)', fontSize: '0.95rem', marginBottom: '1.5rem', lineHeight: 1.5 }}>
              Para excluir o funcionário <strong>{selectedMember?.name}</strong>, por favor, informe a sua senha. Esta ação mandará o funcionário para a lixeira.
            </p>
            <form onSubmit={handleDeleteSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
              <PasswordInput
                id="deletePassword"
                placeholder="Sua senha de usuário" 
                value={deletePassword}
                onChange={e => setDeletePassword(e.target.value)}
                required
                style={{ width: '100%' }}
              />
              <div style={{ display: 'flex', gap: '1rem', marginTop: '0.5rem' }}>
                <button type="button" onClick={() => { setShowDeleteModal(false); setDeletePassword(''); }} className="btn-primary" style={{ flex: 1, backgroundColor: 'transparent', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}>Cancelar</button>
                <button type="submit" className="btn-primary" disabled={isDeleting} style={{ flex: 1, backgroundColor: '#ef4444' }}>
                  {isDeleting ? 'Excluindo...' : 'Excluir'}
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
