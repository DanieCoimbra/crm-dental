'use client'

import { useState, useEffect, useMemo } from 'react'
import { Calendar, dateFnsLocalizer, Views } from 'react-big-calendar'
import format from 'date-fns/format'
import parse from 'date-fns/parse'
import startOfWeek from 'date-fns/startOfWeek'
import getDay from 'date-fns/getDay'
import ptBR from 'date-fns/locale/pt-BR'
import { useAuth } from '@/hooks/auth'
import axios from '@/lib/axios'
import DashboardLayout from '@/components/DashboardLayout'
import { useAlert } from '@/components/AlertContext'
import SearchableSelect from '@/components/SearchableSelect'
import CustomDatePicker from '@/components/CustomDatePicker'
import SmartBookingModal from '@/components/SmartBookingModal'
import { Calendar as CalendarIcon, Clock, Users, Plus, X, Search, FileText, ChevronLeft, ChevronRight, MapPin, ExternalLink, AlertCircle, Phone } from 'lucide-react'
import Link from 'next/link'

const locales = {
  'pt-BR': ptBR,
}

const localizer = dateFnsLocalizer({
  format,
  parse,
  startOfWeek,
  getDay,
  locales,
})

const CustomToolbar = (toolbar) => {
  const goToBack = () => { toolbar.onNavigate('PREV') }
  const goToNext = () => { toolbar.onNavigate('NEXT') }
  const goToCurrent = () => { toolbar.onNavigate('TODAY') }

  return (
    <div className="rbc-toolbar-custom">
      <div className="rbc-toolbar-group">
        <button className="rbc-btn-custom" onClick={goToCurrent}>Hoje</button>
        <button className="rbc-btn-custom icon-btn" onClick={goToBack}><ChevronLeft size={18} /></button>
        <button className="rbc-btn-custom icon-btn" onClick={goToNext}><ChevronRight size={18} /></button>
      </div>
      <span className="rbc-toolbar-label-custom">{toolbar.label}</span>
      <div className="rbc-toolbar-group">
        <button className={`rbc-btn-custom ${toolbar.view === 'month' ? 'active' : ''}`} onClick={() => toolbar.onView('month')}>Mês</button>
        <button className={`rbc-btn-custom ${toolbar.view === 'week' ? 'active' : ''}`} onClick={() => toolbar.onView('week')}>Semana</button>
        <button className={`rbc-btn-custom ${toolbar.view === 'day' ? 'active' : ''}`} onClick={() => toolbar.onView('day')}>Dia</button>
      </div>
    </div>
  )
}

const CustomEvent = ({ event }) => {
  const parts = event.title.split(' - ')
  const type = parts[0]
  const doctor = parts[1] || ''

  const startTime = new Date(event.raw.start_time).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })
  const endTime = new Date(event.raw.end_time).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden', padding: '2px' }}>
      <div style={{ fontSize: '0.7rem', opacity: 0.9, marginBottom: '2px', fontWeight: 500 }}>
        {startTime} - {endTime}
      </div>
      <div style={{ 
        fontWeight: 600, 
        fontSize: '0.8rem', 
        lineHeight: '1.2',
        display: '-webkit-box',
        WebkitLineClamp: 2,
        WebkitBoxOrient: 'vertical',
        overflow: 'hidden'
      }}>
        {type}
      </div>
      {doctor && (
        <div style={{ 
          fontSize: '0.75rem', 
          opacity: 0.9, 
          lineHeight: '1.2', 
          marginTop: '2px',
          whiteSpace: 'nowrap', 
          overflow: 'hidden', 
          textOverflow: 'ellipsis'
        }}>
          {doctor}
        </div>
      )}
    </div>
  )
}

export default function CalendarPage() {
  const { user } = useAuth({ middleware: 'auth' })
  const { showAlert, showConfirm } = useAlert()
  const [appointments, setAppointments] = useState([])
  const [rooms, setRooms] = useState([])
  const [patientsList, setPatientsList] = useState([])
  const [doctorsList, setDoctorsList] = useState([])
  const [rawDoctors, setRawDoctors] = useState([])
  const [appointmentTypes, setAppointmentTypes] = useState([])
  const [myRoomId, setMyRoomId] = useState('') // For logged in doctor
  
  // Calendar View State
  const [view, setView] = useState(Views.WEEK)
  const [date, setDate] = useState(new Date())

  // Modals state
  const [showModal, setShowModal] = useState(false)
  const [modalType, setModalType] = useState('create') // 'create' or 'view'
  const [selectedEvent, setSelectedEvent] = useState(null)
  const [trashStatus, setTrashStatus] = useState(null)
  
  // Smart Booking States
  const [smartSuggestions, setSmartSuggestions] = useState([])
  const [showSmartModal, setShowSmartModal] = useState(false)
  const [smartGapStartTime, setSmartGapStartTime] = useState(null)
  const [smartGapDoctorId, setSmartGapDoctorId] = useState(null)
  
  // Waitlist Matches State
  const [showMatchModal, setShowMatchModal] = useState(false)
  const [matchResults, setMatchResults] = useState([])
  
  // Form State
  const [appointmentDateTime, setAppointmentDateTime] = useState('')
  const [patientId, setPatientId] = useState('')
  const [doctorId, setDoctorId] = useState('')
  const [appointmentTypeId, setAppointmentTypeId] = useState('')
  const [notes, setNotes] = useState('')

  useEffect(() => {
    if (user) {
      if (user.current_room_id) setMyRoomId(user.current_room_id)
      fetchData()
    }
  }, [user])

  const fetchData = async () => {
    try {
      const [appRes, roomsRes, patRes, docRes, trashRes, typesRes] = await Promise.all([
        axios.get('/api/appointments'),
        axios.get('/api/rooms'),
        axios.get('/api/patients'),
        axios.get('/api/users?role=doctor'),
        axios.get('/api/trash/status'),
        axios.get('/api/appointment-types')
      ])
      
      const fetchedTypes = typesRes.data
      setAppointmentTypes(fetchedTypes)

      const formattedEvents = appRes.data.map(app => {
        const typeObj = fetchedTypes.find(t => t.id === app.appointment_type_id)
        return {
          id: app.id,
          title: `${typeObj ? typeObj.name : 'Agendamento'} - D: ${app.doctor?.name || 'Sem médico'}`,
          start: new Date(app.start_time),
          end: new Date(new Date(app.end_time).getTime() - 60000), // subtrai 1 minuto para garantir separação no calendário
          resourceId: app.doctor_id,
          color: typeObj ? typeObj.color : '#3b82f6',
          raw: app
        }
      })
      
      setAppointments(formattedEvents)
      setRooms(roomsRes.data)
      setRawDoctors(docRes.data)
      setPatientsList(patRes.data.map(p => ({ id: p.id, label: `${p.name} (CPF: ${p.cpf})` })))
      setDoctorsList(docRes.data.map(d => ({ id: d.id, label: d.crm ? `${d.name} (CRM: ${d.crm})` : d.name })))
      setTrashStatus(trashRes.data)
    } catch (err) {
      console.error(err)
    }
  }

  const handleOpenCreateModal = () => {
    if (user.role !== 'owner' && user.role !== 'receptionist' && user.role !== 'manager') {
      showAlert('Apenas recepcionistas, gerentes ou donos podem agendar consultas.', 'warning')
      return
    }

    const now = new Date()
    now.setMinutes(0, 0, 0)
    now.setHours(now.getHours() + 1)
    
    setAppointmentDateTime(format(now, "yyyy-MM-dd'T'HH:mm"))
    setModalType('create')
    setPatientId('')
    setDoctorId('')
    setAppointmentTypeId(appointmentTypes.length > 0 ? appointmentTypes[0].id : '')
    setNotes('')
    setShowModal(true)
  }

  const handleSelectEvent = (event) => {
    setSelectedEvent(event)
    setModalType('view')
    setShowModal(true)
  }

  const handleCreateAppointment = async (e) => {
    e.preventDefault()
    try {
      await axios.post('/api/appointments', {
        patient_id: patientId,
        doctor_id: doctorId,
        appointment_type_id: appointmentTypeId,
        start_time: appointmentDateTime,
        notes: notes
      })
      showAlert('Agendamento criado com sucesso!', 'success')
      setShowModal(false)
      fetchData()
    } catch (err) {
      if (err.response?.status === 409) {
        showAlert('ERRO DE CONFLITO: ' + err.response.data.message, 'error')
      } else {
        showAlert(err.response?.data?.message || 'Erro ao agendar.', 'error')
      }
    }
  }

  const handleUpdateAppointment = async (e) => {
    e.preventDefault()
    try {
      await axios.put(`/api/appointments/${selectedEvent.id}`, {
        patient_id: patientId,
        doctor_id: doctorId,
        appointment_type_id: appointmentTypeId,
        start_time: appointmentDateTime,
        notes: notes
      })
      showAlert('Agendamento atualizado com sucesso!', 'success')
      setShowModal(false)
      fetchData()
    } catch (err) {
      if (err.response?.status === 409) {
        showAlert('ERRO DE CONFLITO: ' + err.response.data.message, 'error')
      } else {
        showAlert(err.response?.data?.message || 'Erro ao atualizar.', 'error')
      }
    }
  }

  const handleEditClick = () => {
    setModalType('edit')
    
    // Converte a data do selectedEvent para o formato do input datetime-local
    const dt = new Date(selectedEvent.raw.start_time)
    const dtString = format(dt, "yyyy-MM-dd'T'HH:mm")
    
    setAppointmentDateTime(dtString)
    setPatientId(selectedEvent.raw.patient_id)
    setDoctorId(selectedEvent.raw.doctor_id)
    setAppointmentTypeId(selectedEvent.raw.appointment_type_id)
    setNotes(selectedEvent.raw.notes || '')
  }

  const handleStartConsultation = async () => {
    try {
      await axios.post(`/api/appointments/${selectedEvent.id}/start`)
      showAlert('Atendimento iniciado!', 'success')
      setShowModal(false)
      fetchData()
    } catch (err) {
      showAlert(err.response?.data?.message || 'Erro ao iniciar.', 'error')
    }
  }

  const handleFinishConsultation = async () => {
    try {
      const res = await axios.post(`/api/appointments/${selectedEvent.id}/finish`)
      showAlert('Atendimento finalizado!', 'success')
      setShowModal(false)
      fetchData()

      if (res.data.smart_suggestions && res.data.smart_suggestions.length > 0) {
        setSmartSuggestions(res.data.smart_suggestions)
        setSmartGapStartTime(res.data.appointment.actual_end_time)
        setSmartGapDoctorId(res.data.appointment.doctor_id)
        setShowSmartModal(true)
      }
    } catch (err) {
      showAlert(err.response?.data?.message || 'Erro ao finalizar.', 'error')
    }
  }

  const handleDelete = async () => {
    showConfirm('Deseja realmente cancelar este agendamento?', async () => {
      try {
        const deletedApp = selectedEvent.raw;
        const res = await axios.delete(`/api/appointments/${selectedEvent.id}`)
        showAlert('Agendamento cancelado.', 'success')
        setShowModal(false)
        fetchData()
        
        if (res.data.smart_suggestions && res.data.smart_suggestions.length > 0) {
          setSmartSuggestions(res.data.smart_suggestions)
          setSmartGapStartTime(deletedApp.start_time)
          setSmartGapDoctorId(deletedApp.doctor_id)
          setShowSmartModal(true)
        }
      } catch(err) {
        showAlert('Erro ao cancelar.', 'error')
      }
    })
  }

  const handleSmartBookNow = (suggestion) => {
    setShowSmartModal(false)
    setModalType('create')
    setPatientId(suggestion.patient_id)
    setDoctorId(smartGapDoctorId || '')
    setAppointmentTypeId(suggestion.appointment_type_id)
    
    const dt = new Date(smartGapStartTime)
    const dtString = format(dt, "yyyy-MM-dd'T'HH:mm")
    setAppointmentDateTime(dtString)
    
    setShowModal(true)
  }

  const handleSetRoom = async (newRoomId) => {
    try {
      await axios.post('/api/profile/room', { room_id: newRoomId || null })
      setMyRoomId(newRoomId)
      showAlert('Sala atualizada!', 'success')
      fetchData() // Refresh to update calendar
    } catch (err) {
      showAlert(err.response?.data?.message || 'Erro ao definir sala.', 'error')
    }
  }

  if (!user) return null

  return (
    <DashboardLayout>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', flex: 1, alignItems: 'center', marginRight: '1rem' }}>
          {user.role === 'doctor' ? (
            <h1 style={{ fontSize: '1.5rem', fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>Minha Agenda</h1>
          ) : (
            <>
              {rooms.length > 0 ? rooms.map(room => {
                const doctorInRoom = rawDoctors.find(d => d.current_room && d.current_room.id === room.id);
                return (
                  <div key={room.id} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', backgroundColor: 'var(--bg-secondary)', padding: '0.5rem 0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', boxShadow: '0 1px 2px rgba(0,0,0,0.05)' }} title={doctorInRoom ? `Ocupada por Dr(a). ${doctorInRoom.name}` : 'Sala Livre'}>
                    <div style={{ width: 8, height: 8, borderRadius: '50%', backgroundColor: doctorInRoom ? '#ef4444' : '#10b981' }}></div>
                    <span style={{ fontWeight: 600, fontSize: '0.85rem', color: 'var(--text-primary)' }}>{room.name}</span>
                    <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                      {doctorInRoom ? `Dr(a). ${doctorInRoom.name.split(' ')[0]}` : 'Livre'}
                    </span>
                  </div>
                );
              }) : (
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>Nenhuma sala cadastrada</span>
              )}
            </>
          )}
        </div>
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
          {user.role === 'doctor' && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', background: 'var(--bg-tertiary)', padding: '0.5rem 1rem', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
              <MapPin size={18} color="var(--text-secondary)" />
              <select 
                value={myRoomId || ''} 
                onChange={e => handleSetRoom(e.target.value)}
                style={{ background: 'transparent', border: 'none', color: 'var(--text-primary)', outline: 'none', fontSize: '0.875rem' }}
              >
                <option value="" style={{ background: 'var(--bg-secondary)', color: 'var(--text-primary)' }}>Selecione sua Sala Hoje...</option>
                {rooms.map(r => <option key={r.id} value={r.id} style={{ background: 'var(--bg-secondary)', color: 'var(--text-primary)' }}>{r.name}</option>)}
              </select>
            </div>
          )}
          {(['owner', 'manager', 'receptionist'].includes(user?.role)) && (
            <button onClick={handleOpenCreateModal} className="btn-primary" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <Plus size={18} /> Novo Agendamento
            </button>
          )}
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', marginBottom: '1rem' }}>
        <div style={{ height: '82vh', backgroundColor: 'var(--bg-secondary)', padding: '1rem', borderRadius: '12px', border: '1px solid var(--border-color)', boxShadow: '0 4px 20px rgba(0,0,0,0.1)' }}>
          <Calendar
            localizer={localizer}
            events={appointments}
            startAccessor="start"
            endAccessor="end"
            view={view}
            date={date}
            onView={(newView) => setView(newView)}
            onNavigate={(newDate) => setDate(newDate)}
            views={[Views.MONTH, Views.WEEK, Views.DAY]}
            onSelectEvent={handleSelectEvent}
            dayLayoutAlgorithm="no-overlap"
            step={30}
            timeslots={1}
            culture="pt-BR"
            components={{
              toolbar: CustomToolbar,
              event: CustomEvent
            }}
            eventPropGetter={(event) => {
              let bgColor = event.color
              if (event.raw.status === 'in_progress') bgColor = '#10b981' // Verde para em andamento
              if (event.raw.status === 'completed') bgColor = '#6b7280' // Cinza para finalizado

              return {
                style: {
                  background: bgColor,
                  color: 'white',
                  border: 'none',
                  borderRadius: '6px',
                  boxShadow: '0 2px 4px rgba(0,0,0,0.1)',
                  padding: '2px 4px',
                  fontSize: '0.85rem',
                  opacity: event.raw.status === 'completed' ? 0.7 : 1
                }
              }
            }}
            messages={{
              next: "Próximo",
              previous: "Anterior",
              today: "Hoje",
              month: "Mês",
              week: "Semana",
              day: "Dia"
            }}
          />
        </div>

        {/* Painel inferior de status das salas foi movido para o cabeçalho */}
      </div>

      {/* Modal */}
      {showModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000 }}>
          <div className="card" style={{ width: '400px', maxWidth: '90%' }}>
            {modalType === 'create' || modalType === 'edit' ? (
              <>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                  <h3 style={{ fontSize: '1.25rem', fontWeight: 600, margin: 0 }}>{modalType === 'edit' ? 'Editar Agendamento' : 'Novo Agendamento'}</h3>
                  <button type="button" onClick={() => setShowModal(false)} style={{ color: 'var(--text-secondary)', padding: '0.5rem', borderRadius: '50%', backgroundColor: 'transparent', transition: 'all 0.2s', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }} onMouseEnter={e => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'} onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}>
                    <X size={20} />
                  </button>
                </div>
                <form onSubmit={modalType === 'edit' ? handleUpdateAppointment : handleCreateAppointment} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Data e Hora</label>
                    <CustomDatePicker 
                      selected={appointmentDateTime ? new Date(appointmentDateTime) : null}
                      onChange={(date) => {
                        // Converter date object local back to datetime-local string format if needed, 
                        // or just keep it as local iso string
                        const tzoffset = (new Date()).getTimezoneOffset() * 60000; // offset in milliseconds
                        const localISOTime = date ? (new Date(date.getTime() - tzoffset)).toISOString().slice(0, 16) : '';
                        setAppointmentDateTime(localISOTime);
                      }}
                      showTimeSelect
                      placeholderText="Selecione data e hora"
                    />
                  </div>
                  
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', zIndex: 60 }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Paciente</label>
                    <SearchableSelect 
                      options={patientsList} 
                      value={patientId} 
                      onChange={setPatientId} 
                      placeholder="Buscar por Nome ou CPF..."
                    />
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', zIndex: 50 }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Médico</label>
                    <SearchableSelect 
                      options={doctorsList} 
                      value={doctorId} 
                      onChange={setDoctorId} 
                      placeholder="Buscar por Nome ou CRM..."
                    />
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Tipo de Atendimento</label>
                    <select value={appointmentTypeId} onChange={e => setAppointmentTypeId(e.target.value)} style={{ padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}>
                      {appointmentTypes.map(t => (
                        <option key={t.id} value={t.id}>{t.name} ({t.duration_minutes} min)</option>
                      ))}
                    </select>
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)' }}>Comentário / Observações</label>
                    <textarea 
                      value={notes} 
                      onChange={e => setNotes(e.target.value)} 
                      placeholder="Alguma observação sobre o agendamento? (Opcional)"
                      rows={3}
                      style={{ padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-tertiary)', color: 'var(--text-primary)', resize: 'vertical' }}
                    ></textarea>
                  </div>
                  <div style={{ display: 'flex', gap: '1rem', marginTop: '1rem' }}>
                    <button type="submit" className="btn-primary" style={{ flex: 1 }}>{modalType === 'edit' ? 'Salvar Alterações' : 'Salvar'}</button>
                    <button type="button" onClick={() => setShowModal(false)} className="btn-primary" style={{ flex: 1, backgroundColor: 'transparent', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}>Cancelar</button>
                  </div>
                  {modalType === 'create' && (
                    <div style={{ marginTop: '0.5rem', textAlign: 'center' }}>
                      <Link href="/waitlist" style={{ color: 'var(--accent)', fontSize: '0.875rem', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.25rem', textDecoration: 'none' }}>
                        <ExternalLink size={14} /> Não achou o horário? Adicionar à Fila de Espera
                      </Link>
                    </div>
                  )}
                </form>
              </>
            ) : (
              <>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                  <h3 style={{ fontSize: '1.25rem', fontWeight: 600, margin: 0 }}>Detalhes do Agendamento</h3>
                  <button type="button" onClick={() => setShowModal(false)} style={{ color: 'var(--text-secondary)', padding: '0.5rem', borderRadius: '50%', backgroundColor: 'transparent', transition: 'all 0.2s', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }} onMouseEnter={e => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'} onMouseLeave={e => e.currentTarget.style.backgroundColor = 'transparent'}>
                    <X size={20} />
                  </button>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', fontSize: '0.95rem' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.5rem' }}>
                    <strong style={{ fontSize: '1.1rem' }}>Status:</strong> 
                    <span style={{ 
                      padding: '4px 8px', borderRadius: '4px', fontSize: '0.85rem', fontWeight: 600,
                      backgroundColor: selectedEvent.raw.status === 'in_progress' ? '#d1fae5' : selectedEvent.raw.status === 'completed' ? '#f3f4f6' : '#dbeafe',
                      color: selectedEvent.raw.status === 'in_progress' ? '#047857' : selectedEvent.raw.status === 'completed' ? '#4b5563' : '#1d4ed8'
                    }}>
                      {selectedEvent.raw.status === 'in_progress' ? 'Em Andamento' : selectedEvent.raw.status === 'completed' ? 'Finalizado' : 'Agendado'}
                    </span>
                  </div>
                  <p><strong>Tipo:</strong> {appointmentTypes.find(t => t.id === selectedEvent.raw.appointment_type_id)?.name || 'Agendamento'}</p>
                  <p><strong>Paciente:</strong> {selectedEvent.raw.patient?.name} (ID: {selectedEvent.raw.patient_id})</p>
                  <p><strong>Médico:</strong> {selectedEvent.raw.doctor?.name} (ID: {selectedEvent.raw.doctor_id})</p>
                  <p><strong>Sala:</strong> {selectedEvent.raw.room?.name || selectedEvent.raw.doctor?.current_room?.name || 'Não definida/Médico não está em uma sala'}</p>
                  <p><strong>Início Previsto:</strong> {format(selectedEvent.start, "dd/MM/yyyy HH:mm")}</p>
                  {selectedEvent.raw.actual_start_time && (
                    <p style={{ color: '#047857', fontWeight: 500 }}><strong>Início Real:</strong> {format(new Date(selectedEvent.raw.actual_start_time), "HH:mm:ss")}</p>
                  )}
                  {selectedEvent.raw.actual_end_time && (
                    <p style={{ color: '#4b5563', fontWeight: 500 }}><strong>Fim Real:</strong> {format(new Date(selectedEvent.raw.actual_end_time), "HH:mm:ss")}</p>
                  )}
                  <p><strong>Comentário:</strong> {selectedEvent.raw.notes || 'Nenhum comentário.'}</p>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', marginTop: '1.5rem' }}>
                  
                  {/* Botões de Ação de Consulta (Para o Médico) */}
                  {user.role === 'doctor' && user.id === selectedEvent.raw.doctor_id && (
                    <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '0.5rem' }}>
                      {(selectedEvent.raw.status === 'scheduled' || !selectedEvent.raw.status) && (
                        <button onClick={handleStartConsultation} className="btn-primary" style={{ flex: 1, backgroundColor: '#10b981', borderColor: '#10b981' }}>
                          Iniciar Atendimento
                        </button>
                      )}
                      {selectedEvent.raw.status === 'in_progress' && (
                        <button onClick={handleFinishConsultation} className="btn-primary" style={{ flex: 1, backgroundColor: '#ef4444', borderColor: '#ef4444' }}>
                          Finalizar Atendimento
                        </button>
                      )}
                    </div>
                  )}

                  <div style={{ display: 'flex', gap: '0.75rem' }}>
                    {user.role !== 'doctor' && (
                      <button onClick={handleEditClick} className="btn-primary" style={{ flex: 1 }}>Editar</button>
                    )}
                    <button onClick={() => setShowModal(false)} className="btn-primary" style={{ flex: 1, backgroundColor: 'transparent', color: 'var(--text-primary)', border: '1px solid var(--border-color)' }}>Fechar</button>
                  </div>
                  {user.role !== 'doctor' && (
                    <button 
                      onClick={handleDelete} 
                      disabled={trashStatus?.is_full}
                      className="btn-primary" 
                      style={{ 
                        width: '100%', 
                        backgroundColor: 'transparent', 
                        color: trashStatus?.is_full ? 'var(--text-secondary)' : '#ef4444', 
                        border: trashStatus?.is_full ? '1px solid var(--border-color)' : '1px solid #ef4444',
                        cursor: trashStatus?.is_full ? 'not-allowed' : 'pointer'
                      }}
                      title={trashStatus?.is_full ? 'A lixeira está cheia. Esvazie para excluir.' : ''}
                    >
                      {trashStatus?.is_full ? 'Exclusão Bloqueada (Lixeira Cheia)' : 'Cancelar Consulta'}
                    </button>
                  )}
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* Match Suggestion Modal */}
      {showMatchModal && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1100, padding: '1rem' }}>
          <div className="card" style={{ width: '100%', maxWidth: '600px', padding: 0, display: 'flex', flexDirection: 'column', maxHeight: '90vh' }}>
            <div style={{ padding: '1.5rem', borderBottom: '1px solid var(--border-color)', backgroundColor: 'rgba(37, 99, 235, 0.05)' }}>
              <h2 style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--accent)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <AlertCircle />
                Sugestão de Encaixe!
              </h2>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.875rem', marginTop: '0.25rem' }}>
                Você acabou de cancelar uma consulta. Os seguintes pacientes da fila de espera têm interesse nesse horário:
              </p>
            </div>
            
            <div style={{ padding: '1.5rem', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              {matchResults.map((match, idx) => (
                <div key={idx} style={{ backgroundColor: 'var(--bg-tertiary)', border: '1px solid var(--border-color)', borderRadius: '12px', padding: '1rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div>
                    <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{match.patient?.name}</div>
                    <div style={{ fontSize: '0.875rem', color: 'var(--text-secondary)', marginTop: '0.25rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <Phone size={14} /> {match.patient?.phone || 'Sem contato'}
                    </div>
                    {match.notes && <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: '0.25rem', fontStyle: 'italic' }}>Obs: {match.notes}</div>}
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    {match.urgency_level === 'high' && <span style={{ padding: '4px 8px', backgroundColor: '#fee2e2', color: '#991b1b', borderRadius: '9999px', fontSize: '0.75rem', fontWeight: 600 }}>Urgência: Alta</span>}
                    {match.urgency_level === 'medium' && <span style={{ padding: '4px 8px', backgroundColor: '#fef3c7', color: '#92400e', borderRadius: '9999px', fontSize: '0.75rem', fontWeight: 600 }}>Urgência: Média</span>}
                    {match.urgency_level === 'low' && <span style={{ padding: '4px 8px', backgroundColor: '#dcfce7', color: '#166534', borderRadius: '9999px', fontSize: '0.75rem', fontWeight: 600 }}>Urgência: Baixa</span>}
                  </div>
                </div>
              ))}
            </div>

            <div style={{ padding: '1.5rem', borderTop: '1px solid var(--border-color)', display: 'flex', justifyContent: 'flex-end', backgroundColor: 'var(--bg-tertiary)' }}>
              <button 
                onClick={() => setShowMatchModal(false)}
                className="btn-primary"
                style={{ width: '100%' }}
              >
                Entendi, vou ligar
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal Smart Booking */}
      <SmartBookingModal 
        isOpen={showSmartModal}
        onClose={() => setShowSmartModal(false)}
        suggestions={smartSuggestions}
        onBookNow={handleSmartBookNow}
      />
    </DashboardLayout>
  )
}
