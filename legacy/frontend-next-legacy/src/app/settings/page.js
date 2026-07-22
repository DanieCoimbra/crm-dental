'use client'

import { useState, useEffect } from 'react'
import { useAuth } from '@/hooks/auth'
import axios from '@/lib/axios'
import DashboardLayout from '@/components/DashboardLayout'
import { useAlert } from '@/components/AlertContext'
import { Settings, Save, Clock, Calendar, Shield, Trash2, Plus, Stethoscope, Edit2, MessageCircle, Search, DoorOpen, X, FileSpreadsheet, Upload, Download } from 'lucide-react'
import format from 'date-fns/format'
import parseISO from 'date-fns/parseISO'

export default function SettingsPage() {
  const { user } = useAuth({ middleware: 'auth' })
  const { showAlert, showConfirm } = useAlert()
  
  const [isLoading, setIsLoading] = useState(true)
  const [isSaving, setIsSaving] = useState(false)
  
  const [searchQuery, setSearchQuery] = useState('')
  
  const [rooms, setRooms] = useState([])
  const [newRoom, setNewRoom] = useState({ name: '' })

  const [allowWeekends, setAllowWeekends] = useState(false)
  const [allowOutOfHours, setAllowOutOfHours] = useState(false)
  const [allowHolidays, setAllowHolidays] = useState(false)
  
  const [customHolidays, setCustomHolidays] = useState([])
  const [newHoliday, setNewHoliday] = useState('')

  const [customAllowedDates, setCustomAllowedDates] = useState([])
  const [newAllowedDate, setNewAllowedDate] = useState('')

  const [whatsappMissedReturnDays, setWhatsappMissedReturnDays] = useState(30)
  const [whatsappTemplateBirthday, setWhatsappTemplateBirthday] = useState('')
  const [whatsappTemplateConfirmation, setWhatsappTemplateConfirmation] = useState('')
  const [whatsappTemplateRecovery, setWhatsappTemplateRecovery] = useState('')

  const [appointmentTypes, setAppointmentTypes] = useState([])
  const [newType, setNewType] = useState({ name: '', duration_minutes: 30, color: '#3b82f6' })
  const [showColorPicker, setShowColorPicker] = useState(false)

  const [roles, setRoles] = useState([])
  const [permissions, setPermissions] = useState([])
  const [editingRole, setEditingRole] = useState(null)
  const [showRoleModal, setShowRoleModal] = useState(false)
  const [roleForm, setRoleForm] = useState({ name: '', permissions: [] })

  // Import/Export State
  const [showImportExportModal, setShowImportExportModal] = useState(false)
  const [importFile, setImportFile] = useState(null)
  const [isImporting, setIsImporting] = useState(false)

  const presetColors = [
    '#ef4444', '#f97316', '#f59e0b', '#84cc16', '#22c55e', '#10b981', 
    '#06b6d4', '#0ea5e9', '#3b82f6', '#6366f1', '#8b5cf6', '#a855f7', 
    '#d946ef', '#ec4899', '#f43f5e', '#64748b'
  ]

  useEffect(() => {
    const isManager = user && ['owner', 'manager'].includes(user.role)
    const hasPermission = user && user.permissions_list && user.permissions_list.includes('manage_settings')

    if (user && (isManager || hasPermission)) {
      fetchSettings()
      fetchAppointmentTypes()
      fetchRolesAndPermissions()
      fetchRooms()
    }
  }, [user])

  const fetchSettings = async () => {
    setIsLoading(true)
    try {
      const res = await axios.get('/api/settings')
      setAllowWeekends(res.data.allow_weekends === 'true' || res.data.allow_weekends === true)
      setAllowOutOfHours(res.data.allow_out_of_hours === 'true' || res.data.allow_out_of_hours === true)
      setAllowHolidays(res.data.allow_holidays === 'true' || res.data.allow_holidays === true)
      
      setWhatsappMissedReturnDays(res.data.whatsapp_missed_return_days || 30)
      setWhatsappTemplateBirthday(res.data.whatsapp_template_birthday || 'Olá {nome}, a equipe da clínica deseja um Feliz Aniversário!')
      setWhatsappTemplateConfirmation(res.data.whatsapp_template_confirmation || 'Olá {nome}, passando para lembrar da sua consulta amanhã às {hora}.')
      setWhatsappTemplateRecovery(res.data.whatsapp_template_recovery || 'Olá {nome}, notamos que sua última consulta foi cancelada. Gostaríamos de reagendar?')
      
      let holidays = res.data.custom_holidays
      if (typeof holidays === 'string') {
        try { holidays = JSON.parse(holidays) } catch (e) { holidays = [] }
      }
      setCustomHolidays(Array.isArray(holidays) ? holidays : [])

      let allowedDates = res.data.custom_allowed_dates
      if (typeof allowedDates === 'string') {
        try { allowedDates = JSON.parse(allowedDates) } catch (e) { allowedDates = [] }
      }
      setCustomAllowedDates(Array.isArray(allowedDates) ? allowedDates : [])
    } catch (err) {
      showAlert('Erro ao carregar configurações.', 'error')
    } finally {
      setIsLoading(false)
    }
  }

  const fetchAppointmentTypes = async () => {
    try {
      const res = await axios.get('/api/appointment-types')
      setAppointmentTypes(res.data)
    } catch (err) {
      console.error(err)
    }
  }

  const fetchRolesAndPermissions = async () => {
    try {
      const [rolesRes, permsRes] = await Promise.all([
        axios.get('/api/roles'),
        axios.get('/api/permissions')
      ])
      setRoles(rolesRes.data)
      setPermissions(permsRes.data)
    } catch (err) {
      console.error(err)
    }
  }

  const fetchRooms = async () => {
    try {
      const res = await axios.get('/api/rooms')
      setRooms(res.data)
    } catch (err) {
      console.error(err)
    }
  }

  const handleSave = async () => {
    setIsSaving(true)
    try {
      await axios.put('/api/settings', {
        allow_weekends: allowWeekends,
        allow_out_of_hours: allowOutOfHours,
        allow_holidays: allowHolidays,
        custom_holidays: customHolidays,
        custom_allowed_dates: customAllowedDates,
        whatsapp_missed_return_days: whatsappMissedReturnDays,
        whatsapp_template_birthday: whatsappTemplateBirthday,
        whatsapp_template_confirmation: whatsappTemplateConfirmation,
        whatsapp_template_recovery: whatsappTemplateRecovery
      })
      showAlert('Configurações salvas com sucesso!', 'success')
    } catch (err) {
      showAlert('Erro ao salvar configurações.', 'error')
    } finally {
      setIsSaving(false)
    }
  }

  const handleAddHoliday = () => {
    if (!newHoliday) return
    if (customHolidays.includes(newHoliday)) {
      showAlert('Este feriado já foi adicionado.', 'warning')
      return
    }
    setCustomHolidays([...customHolidays, newHoliday].sort())
    setNewHoliday('')
  }

  const handleRemoveHoliday = (dateToRemove) => {
    setCustomHolidays(customHolidays.filter(d => d !== dateToRemove))
  }

  const handleAddAllowedDate = () => {
    if (!newAllowedDate) return
    if (customAllowedDates.includes(newAllowedDate)) {
      showAlert('Esta data já foi adicionada.', 'warning')
      return
    }
    setCustomAllowedDates([...customAllowedDates, newAllowedDate].sort())
    setNewAllowedDate('')
  }

  const handleRemoveAllowedDate = (dateToRemove) => {
    setCustomAllowedDates(customAllowedDates.filter(d => d !== dateToRemove))
  }

  const handleAddAppointmentType = async () => {
    if (!newType.name || newType.duration_minutes < 1) return
    try {
      const res = await axios.post('/api/appointment-types', newType)
      setAppointmentTypes([...appointmentTypes, res.data])
      setNewType({ name: '', duration_minutes: 30, color: '#3b82f6' })
      showAlert('Tipo de atendimento criado!', 'success')
    } catch (err) {
      showAlert('Erro ao criar tipo de atendimento.', 'error')
    }
  }

  const handleAddRoom = async () => {
    if (!newRoom.name) return
    try {
      const res = await axios.post('/api/rooms', newRoom)
      setRooms([...rooms, res.data])
      setNewRoom({ name: '' })
      showAlert('Sala criada com sucesso!', 'success')
    } catch (err) {
      showAlert('Erro ao criar sala.', 'error')
    }
  }

  const handleDeleteRoom = async (id) => {
    showConfirm(
      'Excluir Sala?',
      'Tem certeza que deseja excluir esta sala? Ação não pode ser desfeita.',
      async () => {
        try {
          await axios.delete(`/api/rooms/${id}`)
          setRooms(rooms.filter(r => r.id !== id))
          showAlert('Sala excluída com sucesso.', 'success')
        } catch (err) {
          if (err.response && err.response.data && err.response.data.message) {
            showAlert(err.response.data.message, 'error')
          } else {
            showAlert('Erro ao excluir. Pode estar em uso.', 'error')
          }
        }
      }
    )
  }

  const handleDeleteAppointmentType = async (id) => {
    showConfirm(
      'Excluir Tipo?',
      'Tem certeza que deseja excluir este tipo de atendimento?',
      async () => {
        try {
          await axios.delete(`/api/appointment-types/${id}`)
          setAppointmentTypes(appointmentTypes.filter(t => t.id !== id))
          showAlert('Tipo de atendimento excluído.', 'success')
        } catch (err) {
          if (err.response && err.response.data && err.response.data.message) {
            showAlert(err.response.data.message, 'error')
          } else {
            showAlert('Erro ao excluir. Pode estar em uso.', 'error')
          }
        }
      }
    )
  }

  const handleOpenRoleModal = (role = null) => {
    if (role) {
      setEditingRole(role)
      setRoleForm({ name: role.name, permissions: role.permissions.map(p => p.name) })
    } else {
      setEditingRole(null)
      setRoleForm({ name: '', permissions: [] })
    }
    setShowRoleModal(true)
  }

  const handleSaveRole = async () => {
    if (!roleForm.name) {
      showAlert('Nome do cargo é obrigatório', 'warning')
      return
    }
    
    try {
      if (editingRole) {
        const res = await axios.put(`/api/roles/${editingRole.id}`, roleForm)
        setRoles(roles.map(r => r.id === editingRole.id ? res.data : r))
        showAlert('Cargo atualizado com sucesso!', 'success')
      } else {
        const res = await axios.post('/api/roles', roleForm)
        setRoles([...roles, res.data])
        showAlert('Cargo criado com sucesso!', 'success')
      }
      setShowRoleModal(false)
    } catch (err) {
      if (err.response && err.response.data && err.response.data.message) {
        showAlert(err.response.data.message, 'error')
      } else {
        showAlert('Erro ao salvar o cargo.', 'error')
      }
    }
  }

  const roleLabels = {
    owner: 'Dono', manager: 'Gerente',
    doctor: 'Médico(a)',
    receptionist: 'Recepcionista'
  }

  const permissionLabels = {
    'view_calendar': 'Ver Calendário',
    'manage_appointments': 'Gerenciar Consultas',
    'view_patients': 'Ver Pacientes',
    'manage_patients': 'Gerenciar Pacientes',
    'manage_team': 'Gerenciar Equipe',
    'manage_settings': 'Gerenciar Configurações',
    'manage_financial': 'Gerenciar Financeiro'
  }

  const getPermissionLabel = (name) => {
    if (!name) return name;
    const normalizedName = name.replace(/-/g, '_');
    return permissionLabels[normalizedName] || name
  }

  const handleDeleteRole = (id, name) => {
    if (['manager', 'doctor', 'receptionist'].includes(name)) {
      showAlert('Cargos padrão não podem ser excluídos.', 'warning')
      return
    }
    showConfirm(
      'Excluir Cargo?',
      'Tem certeza que deseja excluir este cargo? Os usuários vinculados poderão perder o acesso.',
      async () => {
        try {
          await axios.delete(`/api/roles/${id}`)
          setRoles(roles.filter(r => r.id !== id))
          showAlert('Cargo excluído com sucesso.', 'success')
        } catch (err) {
          showAlert('Erro ao excluir o cargo.', 'error')
        }
      }
    )
  }

  const isManager = user && ['owner', 'manager'].includes(user.role)
  const hasPermission = user && user.permissions_list && user.permissions_list.includes('manage_settings')

  if (!user || (!isManager && !hasPermission)) {
    return (
      <DashboardLayout>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '60vh', color: 'var(--text-secondary)' }}>
          <Shield size={64} style={{ marginBottom: '1rem', opacity: 0.5 }} />
          <h2>Acesso Restrito</h2>
          <p>Você não tem permissão para acessar as configurações do sistema.</p>
        </div>
      </DashboardLayout>
    )
  }

  if (isLoading) {
    return <DashboardLayout><div style={{ padding: '2rem' }}>Carregando configurações...</div></DashboardLayout>
  }

  const showSection = (title, keywords = []) => {
    if (!searchQuery) return true;
    const q = searchQuery.toLowerCase();
    return title.toLowerCase().includes(q) || keywords.some(k => k.toLowerCase().includes(q));
  }

  return (
    <DashboardLayout>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2rem' }}>
        <div>
          <h1 style={{ fontSize: '1.75rem', fontWeight: 600, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            <Settings size={28} color="var(--accent)" /> Configurações do Sistema
          </h1>
          <p style={{ color: 'var(--text-secondary)', marginTop: '0.25rem' }}>
            Gerencie as regras de negócio e preferências da clínica.
          </p>
        </div>
        <button 
          onClick={handleSave} 
          disabled={isSaving}
          className="btn-primary" 
          style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', padding: '0.75rem 1.5rem' }}
        >
          <Save size={20} />
          {isSaving ? 'Salvando...' : 'Salvar Alterações'}
        </button>
      </div>

      <div style={{ marginBottom: '2rem', maxWidth: '800px' }}>
        <div style={{ display: 'flex', alignItems: 'center', background: 'var(--bg-secondary)', border: '1px solid var(--border-color)', borderRadius: '12px', padding: '0.75rem 1rem' }}>
          <Search size={20} color="var(--text-secondary)" style={{ marginRight: '0.75rem' }} />
          <input 
            type="text" 
            placeholder="Pesquisar configurações (ex: salas, horários, feriados...)"
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            style={{ border: 'none', background: 'transparent', outline: 'none', width: '100%', color: 'var(--text-primary)', fontSize: '1rem' }}
          />
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem', maxWidth: '800px' }}>
        
        {/* Sessão 1: Regras da Agenda */}
        {showSection('Regras de Agendamento', ['horários', 'finais de semana', 'agenda']) && (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <h2 style={{ fontSize: '1.25rem', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem', borderBottom: '1px solid var(--border-color)', paddingBottom: '1rem' }}>
            <Clock size={20} color="var(--accent)" /> Regras de Agendamento
          </h2>
          
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '1rem', background: 'var(--bg-tertiary)', borderRadius: '12px', border: '1px solid var(--border-color)' }}>
            <div>
              <h3 style={{ fontSize: '1rem', fontWeight: 500 }}>Permitir Finais de Semana</h3>
              <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>
                Libera a agenda para receber consultas aos Sábados e Domingos.
              </p>
            </div>
            <label className="toggle-switch">
              <input type="checkbox" checked={allowWeekends} onChange={e => setAllowWeekends(e.target.checked)} />
              <span className="slider"></span>
            </label>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '1rem', background: 'var(--bg-tertiary)', borderRadius: '12px', border: '1px solid var(--border-color)' }}>
            <div>
              <h3 style={{ fontSize: '1rem', fontWeight: 500 }}>Permitir Fora do Horário Comercial</h3>
              <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>
                Permite agendamentos antes das 08:00 e depois das 18:00.
              </p>
            </div>
            <label className="toggle-switch">
              <input type="checkbox" checked={allowOutOfHours} onChange={e => setAllowOutOfHours(e.target.checked)} />
              <span className="slider"></span>
            </label>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '1rem', background: 'var(--bg-tertiary)', borderRadius: '12px', border: '1px solid var(--border-color)' }}>
            <div>
              <h3 style={{ fontSize: '1rem', fontWeight: 500 }}>Permitir Feriados</h3>
              <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>
                Libera a agenda para receber consultas em Feriados Nacionais e Feriados Customizados.
              </p>
            </div>
            <label className="toggle-switch">
              <input type="checkbox" checked={allowHolidays} onChange={e => setAllowHolidays(e.target.checked)} />
              <span className="slider"></span>
            </label>
          </div>
        </div>
        )}

        {/* Sessão 2: Feriados Customizados */}
        {showSection('Feriados Customizados', ['feriado', 'datas']) && (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <div>
            <h2 style={{ fontSize: '1.25rem', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <Calendar size={20} color="var(--accent)" /> Feriados Customizados / Municipais
            </h2>
            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '0.5rem' }}>
              Os feriados nacionais e móveis (Páscoa, Carnaval) já são bloqueados automaticamente. Adicione aqui feriados específicos da sua cidade ou datas onde a clínica não vai funcionar.
            </p>
          </div>

          <div style={{ display: 'flex', gap: '1rem' }}>
            <input 
              type="date" 
              value={newHoliday} 
              onChange={e => setNewHoliday(e.target.value)} 
              style={{ flex: 1, padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
            />
            <button onClick={handleAddHoliday} className="btn-primary" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <Plus size={18} /> Adicionar
            </button>
          </div>

          {customHolidays.length > 0 ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', marginTop: '1rem' }}>
              {customHolidays.map((date, index) => (
                <div key={index} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '1rem', background: 'var(--bg-secondary)', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
                  <span style={{ fontWeight: 500, color: '#ef4444' }}>{format(parseISO(date), 'dd/MM/yyyy')}</span>
                  <button 
                    onClick={() => handleRemoveHoliday(date)}
                    style={{ background: 'transparent', border: 'none', color: '#ef4444', cursor: 'pointer', padding: '0.5rem', display: 'flex', alignItems: 'center', justifyContent: 'center', borderRadius: '50%', transition: 'background 0.2s' }}
                    onMouseEnter={e => e.currentTarget.style.backgroundColor = 'rgba(239, 68, 68, 0.1)'}
                    onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}
                    title="Remover Data"
                  >
                    <Trash2 size={18} />
                  </button>
                </div>
              ))}
            </div>
          ) : (
            <div style={{ textAlign: 'center', padding: '2rem', background: 'var(--bg-tertiary)', borderRadius: '12px', color: 'var(--text-secondary)' }}>
              Nenhum feriado customizado cadastrado.
            </div>
          )}
        </div>
        )}

        {/* Sessão 3: Datas Específicas Liberadas */}
        {showSection('Datas Específicas Liberadas', ['exceções', 'liberar data']) && (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <div>
            <h2 style={{ fontSize: '1.25rem', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <Calendar size={20} color="#10b981" /> Datas Específicas Liberadas (Exceções)
            </h2>
            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '0.5rem' }}>
              Adicione datas específicas que devem ser <b>liberadas</b> para agendamento, mesmo que caiam em finais de semana ou feriados.
            </p>
          </div>

          <div style={{ display: 'flex', gap: '1rem' }}>
            <input 
              type="date" 
              value={newAllowedDate} 
              onChange={e => setNewAllowedDate(e.target.value)} 
              style={{ flex: 1, padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
            />
            <button onClick={handleAddAllowedDate} className="btn-primary" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', backgroundColor: '#10b981', border: 'none' }}>
              <Plus size={18} /> Liberar Data
            </button>
          </div>

          {customAllowedDates.length > 0 ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', marginTop: '1rem' }}>
              {customAllowedDates.map((date, index) => (
                <div key={index} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '1rem', background: 'var(--bg-secondary)', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
                  <span style={{ fontWeight: 500, color: '#10b981' }}>{format(parseISO(date), 'dd/MM/yyyy')}</span>
                  <button 
                    onClick={() => handleRemoveAllowedDate(date)}
                    style={{ background: 'transparent', border: 'none', color: '#ef4444', cursor: 'pointer', padding: '0.5rem', display: 'flex', alignItems: 'center', justifyContent: 'center', borderRadius: '50%', transition: 'background 0.2s' }}
                    onMouseEnter={e => e.currentTarget.style.backgroundColor = 'rgba(239, 68, 68, 0.1)'}
                    onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}
                    title="Remover Data"
                  >
                    <Trash2 size={18} />
                  </button>
                </div>
              ))}
            </div>
          ) : (
            <div style={{ textAlign: 'center', padding: '2rem', background: 'var(--bg-tertiary)', borderRadius: '12px', color: 'var(--text-secondary)' }}>
              Nenhuma exceção cadastrada.
            </div>
          )}
        </div>
        )}

        {/* Sessão 4: Gestão de Salas */}
        {showSection('Gestão de Salas', ['salas', 'consultório', 'número']) && (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <div>
            <h2 style={{ fontSize: '1.25rem', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <DoorOpen size={20} color="#0ea5e9" /> Gestão de Salas
            </h2>
            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '0.5rem' }}>
              Cadastre as salas de atendimento (consultórios) disponíveis na clínica.
            </p>
          </div>

          <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
            <input 
              type="text" 
              placeholder="Nome/Número da Sala (ex: Sala 01)"
              value={newRoom.name} 
              onChange={e => setNewRoom({...newRoom, name: e.target.value})} 
              style={{ flex: 1, padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)', minWidth: '200px' }}
            />
            <button onClick={handleAddRoom} className="btn-primary" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', backgroundColor: '#0ea5e9', border: 'none' }}>
              <Plus size={18} /> Adicionar
            </button>
          </div>

          <div style={{ overflowX: 'auto', marginTop: '1rem' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border-color)', color: 'var(--text-secondary)', fontSize: '0.875rem' }}>
                  <th style={{ padding: '0.75rem 1rem', fontWeight: 500 }}>Nome da Sala</th>
                  <th style={{ padding: '0.75rem 1rem', fontWeight: 500, textAlign: 'right' }}>Ações</th>
                </tr>
              </thead>
              <tbody>
                {rooms.length === 0 ? (
                  <tr>
                    <td colSpan="2" style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-secondary)' }}>Nenhuma sala cadastrada.</td>
                  </tr>
                ) : rooms.map(room => (
                  <tr key={room.id} style={{ borderBottom: '1px solid var(--border-color)' }}>
                    <td style={{ padding: '1rem', fontWeight: 500, color: 'var(--text-primary)' }}>{room.name}</td>
                    <td style={{ padding: '1rem', textAlign: 'right', display: 'flex', justifyContent: 'flex-end', gap: '0.5rem' }}>
                      <button 
                        onClick={() => handleDeleteRoom(room.id)}
                        style={{ background: 'transparent', border: 'none', color: '#ef4444', cursor: 'pointer', padding: '0.5rem', borderRadius: '50%', transition: 'background 0.2s' }}
                        onMouseEnter={e => e.currentTarget.style.backgroundColor = 'rgba(239, 68, 68, 0.1)'}
                        onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}
                        title="Excluir Sala"
                      >
                        <Trash2 size={18} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        )}

        {/* Sessão 5: Tipos de Atendimento */}
        {showSection('Tipos de Atendimento', ['atendimento', 'duração', 'cor', 'procedimento']) && (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <div>
            <h2 style={{ fontSize: '1.25rem', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <Stethoscope size={20} color="#6366f1" /> Tipos de Atendimento
            </h2>
            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '0.5rem' }}>
              Gerencie os procedimentos disponíveis, suas durações (em minutos) e a cor que aparecerão no calendário.
            </p>
          </div>

          <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
            <input 
              type="text" 
              placeholder="Nome do Tipo (ex: Manutenção)"
              value={newType.name} 
              onChange={e => setNewType({...newType, name: e.target.value})} 
              style={{ flex: 2, padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)', minWidth: '200px' }}
            />
            <input 
              type="number" 
              placeholder="Minutos"
              value={newType.duration_minutes} 
              onChange={e => setNewType({...newType, duration_minutes: e.target.value})} 
              style={{ flex: 1, padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)', minWidth: '100px' }}
            />
            <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
              <button
                onClick={() => setShowColorPicker(!showColorPicker)}
                style={{ 
                  width: '42px', 
                  height: '42px', 
                  borderRadius: '8px', 
                  backgroundColor: newType.color, 
                  border: '2px solid var(--border-color)', 
                  cursor: 'pointer',
                  boxShadow: '0 2px 4px rgba(0,0,0,0.1)'
                }}
                title="Escolher Cor"
              />
              {showColorPicker && (
                <div style={{ 
                  position: 'absolute', 
                  top: '100%', 
                  left: 0, 
                  marginTop: '0.5rem', 
                  backgroundColor: 'var(--bg-secondary)', 
                  padding: '1rem', 
                  borderRadius: '12px', 
                  border: '1px solid var(--border-color)', 
                  boxShadow: '0 10px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1)', 
                  zIndex: 100,
                  display: 'grid',
                  gridTemplateColumns: 'repeat(4, 1fr)',
                  gap: '0.5rem',
                  width: 'max-content'
                }}>
                  {presetColors.map(color => (
                    <button
                      key={color}
                      onClick={() => {
                        setNewType({...newType, color})
                        setShowColorPicker(false)
                      }}
                      style={{
                        width: '32px',
                        height: '32px',
                        borderRadius: '50%',
                        backgroundColor: color,
                        border: newType.color === color ? '3px solid var(--text-primary)' : '2px solid transparent',
                        cursor: 'pointer',
                        transition: 'transform 0.1s',
                        transform: newType.color === color ? 'scale(1.1)' : 'scale(1)'
                      }}
                      onMouseEnter={e => e.currentTarget.style.transform = 'scale(1.1)'}
                      onMouseLeave={e => e.currentTarget.style.transform = newType.color === color ? 'scale(1.1)' : 'scale(1)'}
                    />
                  ))}
                </div>
              )}
            </div>
            <button onClick={handleAddAppointmentType} className="btn-primary" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', backgroundColor: '#6366f1', border: 'none' }}>
              <Plus size={18} /> Adicionar
            </button>
          </div>

          <div style={{ overflowX: 'auto', marginTop: '1rem' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border-color)', color: 'var(--text-secondary)', fontSize: '0.875rem' }}>
                  <th style={{ padding: '0.75rem 1rem', fontWeight: 500 }}>Nome</th>
                  <th style={{ padding: '0.75rem 1rem', fontWeight: 500 }}>Duração (min)</th>
                  <th style={{ padding: '0.75rem 1rem', fontWeight: 500 }}>Cor</th>
                  <th style={{ padding: '0.75rem 1rem', fontWeight: 500, textAlign: 'right' }}>Ações</th>
                </tr>
              </thead>
              <tbody>
                {appointmentTypes.length === 0 ? (
                  <tr>
                    <td colSpan="4" style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-secondary)' }}>Nenhum tipo cadastrado.</td>
                  </tr>
                ) : appointmentTypes.map(type => (
                  <tr key={type.id} style={{ borderBottom: '1px solid var(--border-color)' }}>
                    <td style={{ padding: '1rem', fontWeight: 500, color: 'var(--text-primary)' }}>{type.name}</td>
                    <td style={{ padding: '1rem', color: 'var(--text-secondary)' }}>{type.duration_minutes}</td>
                    <td style={{ padding: '1rem' }}>
                      <div style={{ width: '20px', height: '20px', borderRadius: '50%', backgroundColor: type.color || '#3b82f6', border: '1px solid var(--border-color)' }}></div>
                    </td>
                    <td style={{ padding: '1rem', textAlign: 'right', display: 'flex', justifyContent: 'flex-end', gap: '0.5rem' }}>
                      <button 
                        onClick={() => handleDeleteAppointmentType(type.id)}
                        style={{ background: 'transparent', border: 'none', color: '#ef4444', cursor: 'pointer', padding: '0.5rem', borderRadius: '50%', transition: 'background 0.2s' }}
                        onMouseEnter={e => e.currentTarget.style.backgroundColor = 'rgba(239, 68, 68, 0.1)'}
                        onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}
                        title="Excluir Tipo"
                      >
                        <Trash2 size={18} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        )}

        {/* Sessão 6: Cargos e Permissões */}
        {showSection('Cargos e Permissões', ['cargo', 'permissão', 'acesso', 'gerente', 'equipe']) && (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div>
              <h2 style={{ fontSize: '1.25rem', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <Shield size={20} color="#f59e0b" /> Cargos e Permissões
              </h2>
              <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '0.5rem' }}>
                Crie funções e defina o que cada colaborador pode ver e fazer no sistema.
              </p>
            </div>
            <button onClick={() => handleOpenRoleModal()} className="btn-primary" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', backgroundColor: '#f59e0b', border: 'none' }}>
              <Plus size={18} /> Novo Cargo
            </button>
          </div>

          <div style={{ overflowX: 'auto', marginTop: '1rem' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border-color)', color: 'var(--text-secondary)', fontSize: '0.875rem' }}>
                  <th style={{ padding: '0.75rem 1rem', fontWeight: 500 }}>Nome do Cargo</th>
                  <th style={{ padding: '0.75rem 1rem', fontWeight: 500 }}>Permissões</th>
                  <th style={{ padding: '0.75rem 1rem', fontWeight: 500, textAlign: 'right' }}>Ações</th>
                </tr>
              </thead>
              <tbody>
                {roles.map(role => (
                  <tr key={role.id} style={{ borderBottom: '1px solid var(--border-color)' }}>
                    <td style={{ padding: '1rem', fontWeight: 500, color: 'var(--text-primary)' }}>
                      {roleLabels[role.name] || role.name}
                      {['manager', 'doctor', 'receptionist'].includes(role.name) && (
                        <span style={{ fontSize: '0.7rem', backgroundColor: 'var(--bg-tertiary)', padding: '0.1rem 0.4rem', borderRadius: '4px', marginLeft: '0.5rem', color: 'var(--text-secondary)' }}>Padrão</span>
                      )}
                    </td>
                    <td style={{ padding: '1rem', color: 'var(--text-secondary)' }}>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.25rem' }}>
                        {role.permissions && role.permissions.length > 0 ? 
                          role.permissions.map(p => (
                            <span key={p.id} style={{ fontSize: '0.75rem', backgroundColor: 'rgba(59, 130, 246, 0.1)', color: '#3b82f6', padding: '0.15rem 0.5rem', borderRadius: '12px' }}>
                              {getPermissionLabel(p.name)}
                            </span>
                          )) : 
                          <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>Nenhuma permissão</span>
                        }
                      </div>
                    </td>
                    <td style={{ padding: '1rem', textAlign: 'right', display: 'flex', justifyContent: 'flex-end', gap: '0.5rem' }}>
                      <button 
                        onClick={() => handleOpenRoleModal(role)}
                        style={{ background: 'transparent', border: 'none', color: '#3b82f6', cursor: 'pointer', padding: '0.5rem', borderRadius: '50%', transition: 'background 0.2s' }}
                        onMouseEnter={e => e.currentTarget.style.backgroundColor = 'rgba(59, 130, 246, 0.1)'}
                        onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}
                        title="Editar Cargo"
                      >
                        <Edit2 size={18} />
                      </button>
                      {!['manager', 'doctor', 'receptionist'].includes(role.name) && (
                        <button 
                          onClick={() => handleDeleteRole(role.id, role.name)}
                          style={{ background: 'transparent', border: 'none', color: '#ef4444', cursor: 'pointer', padding: '0.5rem', borderRadius: '50%', transition: 'background 0.2s' }}
                          onMouseEnter={e => e.currentTarget.style.backgroundColor = 'rgba(239, 68, 68, 0.1)'}
                          onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}
                          title="Excluir Cargo"
                        >
                          <Trash2 size={18} />
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        )}

      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem', maxWidth: '800px', marginTop: '2rem' }}>
        {/* Sessão 7: Importação e Exportação (Planilhas) */}
        {showSection('Importação e Exportação (Planilhas)', ['planilhas', 'importar', 'exportar', 'backup', 'excel', 'pacientes']) && (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div>
              <h2 style={{ fontSize: '1.25rem', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <FileSpreadsheet size={20} color="#10b981" /> Pacientes: Base e Planilhas
              </h2>
              <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '0.5rem' }}>
                Faça backup da sua base de pacientes ou importe uma planilha de outro sistema.
              </p>
            </div>
            <button onClick={() => setShowImportExportModal(true)} className="btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', padding: '0.75rem 1.5rem', whiteSpace: 'nowrap' }}>
              <FileSpreadsheet size={18} /> Acessar Planilhas
            </button>
          </div>
        </div>
        )}

        {/* Sessão 8: Automação WhatsApp */}
        {showSection('Automação de WhatsApp', ['whatsapp', 'mensagem', 'lembrete', 'aniversário']) && (
        <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          <div>
            <h2 style={{ fontSize: '1.25rem', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <MessageCircle size={20} color="#22c55e" /> Automação de WhatsApp
            </h2>
            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginTop: '0.5rem' }}>
              Configure os templates das mensagens enviadas automaticamente pelo robô todos os dias às 09:00. <br/>
              <b>Variáveis suportadas:</b> <code>{'{nome}'}</code> e <code>{'{hora}'}</code>.
            </p>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <label style={{ fontSize: '0.95rem', fontWeight: 500 }}>Template: Feliz Aniversário</label>
            <textarea 
              rows={2}
              value={whatsappTemplateBirthday}
              onChange={e => setWhatsappTemplateBirthday(e.target.value)}
              style={{ width: '100%', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)', resize: 'vertical' }}
            />
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <label style={{ fontSize: '0.95rem', fontWeight: 500 }}>Template: Confirmação de Consulta (Dia Seguinte)</label>
            <textarea 
              rows={2}
              value={whatsappTemplateConfirmation}
              onChange={e => setWhatsappTemplateConfirmation(e.target.value)}
              style={{ width: '100%', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)', resize: 'vertical' }}
            />
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', borderTop: '1px solid var(--border-color)', paddingTop: '1.5rem' }}>
            <h3 style={{ fontSize: '1rem', fontWeight: 500 }}>Recuperação de Pacientes</h3>
            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
              Envia uma mensagem para o paciente que cancelou a consulta e não remarcou após os dias configurados abaixo.
            </p>
            <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
              <label style={{ fontSize: '0.95rem', fontWeight: 500 }}>Dias aguardando o retorno:</label>
              <input 
                type="number"
                min="1"
                value={whatsappMissedReturnDays}
                onChange={e => setWhatsappMissedReturnDays(e.target.value)}
                style={{ width: '80px', padding: '0.5rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)', textAlign: 'center' }}
              />
            </div>
            <textarea 
              rows={2}
              value={whatsappTemplateRecovery}
              onChange={e => setWhatsappTemplateRecovery(e.target.value)}
              style={{ width: '100%', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)', resize: 'vertical' }}
            />
          </div>
        </div>
        )}
      </div>

      {showRoleModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
          <div className="card" style={{ width: '100%', maxWidth: '600px', maxHeight: '90vh', overflowY: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
              <h2 style={{ fontSize: '1.25rem', fontWeight: 600, margin: 0 }}>
                {editingRole ? 'Editar Cargo' : 'Novo Cargo'}
              </h2>
              <button onClick={() => setShowRoleModal(false)} style={{ color: 'var(--text-secondary)', padding: '0.5rem', borderRadius: '50%', backgroundColor: 'transparent', transition: 'all 0.2s', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }} onMouseEnter={e => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'} onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}>
                <X size={20} />
              </button>
            </div>
            
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', marginBottom: '1.5rem' }}>
              <div>
                <label style={{ display: 'block', fontSize: '0.875rem', color: 'var(--text-secondary)', marginBottom: '0.25rem' }}>Nome do Cargo</label>
                <input 
                  type="text" 
                  value={roleForm.name} 
                  onChange={e => setRoleForm({...roleForm, name: e.target.value})}
                  disabled={editingRole && ['manager', 'doctor', 'receptionist'].includes(editingRole.name)}
                  style={{ width: '100%', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                />
                {editingRole && ['manager', 'doctor', 'receptionist'].includes(editingRole.name) && (
                  <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: '0.25rem' }}>O nome dos cargos padrão não pode ser alterado.</p>
                )}
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '0.875rem', color: 'var(--text-secondary)', marginBottom: '0.75rem' }}>Permissões de Acesso</label>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(250px, 1fr))', gap: '1rem' }}>
                  {permissions.map(perm => (
                    <div key={perm.id} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', background: 'var(--bg-tertiary)', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
                      <input 
                        type="checkbox"
                        id={`perm-${perm.id}`}
                        checked={roleForm.permissions.includes(perm.name)}
                        onChange={(e) => {
                          if (e.target.checked) {
                            setRoleForm({...roleForm, permissions: [...roleForm.permissions, perm.name]})
                          } else {
                            setRoleForm({...roleForm, permissions: roleForm.permissions.filter(p => p !== perm.name)})
                          }
                        }}
                        disabled={editingRole && ['owner', 'manager'].includes(editingRole.name)}
                        style={{ width: '18px', height: '18px', accentColor: 'var(--accent)', cursor: 'pointer' }}
                      />
                      <label htmlFor={`perm-${perm.id}`} style={{ fontSize: '0.875rem', cursor: 'pointer', userSelect: 'none' }}>
                        {getPermissionLabel(perm.name)}
                      </label>
                    </div>
                  ))}
                </div>
                {editingRole && ['owner', 'manager'].includes(editingRole.name) && (
                  <p style={{ fontSize: '0.75rem', color: '#f59e0b', marginTop: '0.5rem' }}>O Gerente sempre possui todas as permissões.</p>
                )}
              </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '1rem', marginTop: '2rem' }}>
              <button onClick={() => setShowRoleModal(false)} style={{ padding: '0.75rem 1.5rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'transparent', color: 'var(--text-primary)', cursor: 'pointer', fontWeight: 500 }}>
                Cancelar
              </button>
              <button onClick={handleSaveRole} className="btn-primary" style={{ padding: '0.75rem 1.5rem' }}>
                Salvar Cargo
              </button>
            </div>
          </div>
        </div>
      )}

      {showImportExportModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
          <div className="card" style={{ width: '100%', maxWidth: '600px', maxHeight: '90vh', overflowY: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
              <h2 style={{ fontSize: '1.25rem', fontWeight: 600, margin: 0, display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <FileSpreadsheet size={24} color="#10b981" /> Importar / Exportar Base
              </h2>
              <button onClick={() => { setShowImportExportModal(false); setImportFile(null); }} style={{ color: 'var(--text-secondary)', padding: '0.5rem', borderRadius: '50%', backgroundColor: 'transparent', transition: 'all 0.2s', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }} onMouseEnter={e => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'} onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}>
                <X size={20} />
              </button>
            </div>
            
            <div style={{ marginBottom: '2rem' }}>
              <h4 style={{ fontWeight: 600, marginBottom: '0.5rem', color: 'var(--text-primary)' }}>1. Exportar (Backup)</h4>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', marginBottom: '1rem' }}>Baixe todos os pacientes atuais cadastrados na sua clínica.</p>
              <a href={`${process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8000'}/api/patients-export`} target="_blank" rel="noopener noreferrer" className="btn-secondary" style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', textDecoration: 'none', padding: '0.75rem 1.5rem' }}>
                <Download size={18} /> Baixar Base Atual (.xlsx)
              </a>
            </div>

            <hr style={{ borderTop: '1px solid var(--border-color)', margin: '1.5rem 0' }} />

            <div>
              <h4 style={{ fontWeight: 600, marginBottom: '0.5rem', color: 'var(--text-primary)' }}>2. Importar Pacientes</h4>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', marginBottom: '1rem' }}>Faça upload de uma planilha de outro sistema. Nosso sistema vai tentar ler as colunas de Nome, CPF, Telefone e E-mail de forma inteligente.</p>
              
              <div style={{ border: '2px dashed var(--border-color)', padding: '2.5rem', borderRadius: '8px', textAlign: 'center', marginBottom: '1.5rem', backgroundColor: 'var(--bg-tertiary)', transition: 'border-color 0.2s', cursor: 'pointer' }} onMouseEnter={e => e.currentTarget.style.borderColor = 'var(--accent)'} onMouseLeave={e => e.currentTarget.style.borderColor = 'var(--border-color)'}>
                <input 
                  type="file" 
                  accept=".csv, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, application/vnd.ms-excel" 
                  id="file-upload" 
                  style={{ display: 'none' }}
                  onChange={(e) => setImportFile(e.target.files[0])}
                />
                <label htmlFor="file-upload" style={{ cursor: 'pointer', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '1rem' }}>
                  <Upload size={36} color={importFile ? '#10b981' : 'var(--text-secondary)'} />
                  <span style={{ color: 'var(--text-primary)', fontWeight: 500, fontSize: '1.1rem' }}>
                    {importFile ? importFile.name : 'Clique para selecionar a planilha'}
                  </span>
                  <span style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>Suporta .xlsx e .csv (Máx: 10MB)</span>
                </label>
              </div>

              {importFile && (
                <button 
                  className="btn-primary" 
                  style={{ width: '100%', padding: '0.75rem', display: 'flex', justifyContent: 'center', gap: '0.5rem', marginTop: '1rem' }}
                  disabled={isImporting}
                  onClick={async () => {
                    setIsImporting(true);
                    const formData = new FormData();
                    formData.append('file', importFile);
                    try {
                      await axios.post('/api/patients-import', formData, {
                        headers: { 'Content-Type': 'multipart/form-data' }
                      });
                      showAlert('Pacientes importados com sucesso!', 'success');
                      setShowImportExportModal(false);
                      setImportFile(null);
                    } catch (e) {
                      showAlert(e.response?.data?.message || 'Erro ao importar.', 'error');
                    } finally {
                      setIsImporting(false);
                    }
                  }}
                >
                  {isImporting ? 'Processando...' : 'Iniciar Importação'}
                </button>
              )}
              
              <div style={{ textAlign: 'center', marginTop: '1.5rem' }}>
                <a href={`${process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8000'}/api/patients-import-template`} target="_blank" rel="noopener noreferrer" style={{ color: '#0ea5e9', fontSize: '0.85rem', textDecoration: 'underline', transition: 'color 0.2s' }} onMouseEnter={e => e.currentTarget.style.color = '#0284c7'} onMouseLeave={e => e.currentTarget.style.color = '#0ea5e9'}>
                  Planilha muito confusa? Baixe o Modelo Padrão vazio
                </a>
              </div>
            </div>
          </div>
        </div>
      )}

      <style jsx global>{`
        /* Estilos do Toggle Switch (iOS style) */
        .toggle-switch {
          position: relative;
          display: inline-block;
          width: 50px;
          height: 28px;
        }
        .toggle-switch input {
          opacity: 0;
          width: 0;
          height: 0;
        }
        .toggle-switch .slider {
          position: absolute;
          cursor: pointer;
          top: 0;
          left: 0;
          right: 0;
          bottom: 0;
          background-color: var(--border-color);
          transition: .3s;
          border-radius: 34px;
        }
        .toggle-switch .slider:before {
          position: absolute;
          content: "";
          height: 20px;
          width: 20px;
          left: 4px;
          bottom: 4px;
          background-color: white;
          transition: .3s;
          border-radius: 50%;
          box-shadow: 0 2px 4px rgba(0,0,0,0.2);
        }
        .toggle-switch input:checked + .slider {
          background-color: var(--accent);
        }
        .toggle-switch input:checked + .slider:before {
          transform: translateX(22px);
        }
      `}</style>
    </DashboardLayout>
  )
}
