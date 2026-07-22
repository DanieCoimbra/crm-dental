'use client'

import { useState, useRef, useEffect } from 'react'
import { useAuth } from '@/hooks/auth'
import axios from '@/lib/axios'
import DashboardLayout from '@/components/DashboardLayout'
import { Camera, Save, User as UserIcon, Mail, Phone, MapPin } from 'lucide-react'
import { useAlert } from '@/components/AlertContext'
import Image from 'next/image'

export default function ProfilePage() {
  const { user, mutate } = useAuth({ middleware: 'auth' })
  const { showAlert } = useAlert()
  const [isSaving, setIsSaving] = useState(false)
  const [preview, setPreview] = useState(null)
  const fileInputRef = useRef(null)

  // Profile data state
  const [formData, setFormData] = useState({
    phone: '',
    address: ''
  })

  // Pre-fill form when user data loads
  useEffect(() => {
    if (user) {
      setFormData({
        phone: user.phone || '',
        address: user.address || ''
      })
    }
  }, [user])

  const handleImageChange = (e) => {
    const file = e.target.files[0]
    if (file) {
      setPreview(URL.createObjectURL(file))
    }
  }

  const handleInputChange = (e) => {
    const { name, value } = e.target
    setFormData(prev => ({ ...prev, [name]: value }))
  }

  const handleUploadAndSave = async (e) => {
    e.preventDefault()
    setIsSaving(true)
    
    try {
      // 1. Salvar os dados de perfil (texto)
      await axios.put('/api/profile', formData)

      // 2. Salvar o avatar (se houver um novo arquivo)
      const file = fileInputRef.current.files[0]
      if (file) {
        if (file.size > 15 * 1024 * 1024) {
          showAlert('A imagem não pode ter mais de 15MB.', 'error')
          setIsSaving(false)
          return
        }

        const imageFormData = new FormData()
        imageFormData.append('avatar', file)
        
        await axios.post('/api/profile/avatar', imageFormData)
      }
      
      showAlert('Perfil atualizado com sucesso!', 'success')
      mutate() // Recarrega os dados do usuário autenticado no frontend
      setPreview(null) // Limpa o preview para usar a URL original de novo
      if (fileInputRef.current) fileInputRef.current.value = ''
    } catch (err) {
      console.error(err)
      const errors = err.response?.data?.errors;
      let errorMsg = err.response?.data?.message || 'Erro ao atualizar perfil.';
      
      if (errors) {
        // Formata os erros de validação em uma string mais legível
        errorMsg = Object.values(errors).flat().join('\n');
      }
      
      showAlert(errorMsg, 'error');
    } finally {
      setIsSaving(false)
    }
  }

  if (!user) return null

  // URL da imagem atual ou do preview
  const currentImage = preview || (user.avatar ? `${process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8000'}${user.avatar}` : null)

  return (
    <DashboardLayout>
      <div style={{ maxWidth: '600px', margin: '0 auto', display: 'flex', flexDirection: 'column', gap: '2rem' }}>
        
        <div>
          <h1 style={{ fontSize: '1.75rem', fontWeight: 600, color: 'var(--text-primary)' }}>Meu Perfil</h1>
          <p style={{ color: 'var(--text-secondary)', marginTop: '0.25rem' }}>Gerencie suas informações e foto de perfil.</p>
        </div>

        <div className="card" style={{ padding: '2rem' }}>
          <form onSubmit={handleUploadAndSave} style={{ display: 'flex', flexDirection: 'column', gap: '2rem', alignItems: 'center' }}>
            
            {/* Foto e Input */}
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '1rem' }}>
              <div 
                style={{ 
                  width: 150, 
                  height: 150, 
                  borderRadius: '50%', 
                  backgroundColor: 'rgba(139, 92, 246, 0.1)', 
                  display: 'flex', 
                  alignItems: 'center', 
                  justifyContent: 'center',
                  position: 'relative',
                  overflow: 'hidden',
                  border: '4px solid var(--bg-tertiary)',
                  boxShadow: '0 4px 10px rgba(0,0,0,0.1)'
                }}
              >
                {currentImage ? (
                  <img src={currentImage} alt="Avatar" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                ) : (
                  <span style={{ fontSize: '4rem', fontWeight: 'bold', color: '#8b5cf6' }}>
                    {user.name ? user.name.charAt(0).toUpperCase() : 'U'}
                  </span>
                )}
                
                {/* Botão overlay para trocar foto */}
                <div 
                  onClick={() => fileInputRef.current?.click()}
                  style={{
                    position: 'absolute',
                    bottom: 0,
                    left: 0,
                    right: 0,
                    backgroundColor: 'rgba(0,0,0,0.6)',
                    color: 'white',
                    padding: '0.5rem',
                    textAlign: 'center',
                    cursor: 'pointer',
                    fontSize: '0.875rem',
                    fontWeight: 500,
                    transition: 'background-color 0.2s'
                  }}
                  onMouseEnter={e => e.currentTarget.style.backgroundColor = 'rgba(0,0,0,0.8)'}
                  onMouseLeave={e => e.currentTarget.style.backgroundColor = 'rgba(0,0,0,0.6)'}
                >
                  <Camera size={18} style={{ margin: '0 auto' }} />
                </div>
              </div>
              <input 
                type="file" 
                ref={fileInputRef} 
                onChange={handleImageChange} 
                accept="image/*"
                style={{ display: 'none' }}
              />
              <p style={{ fontSize: '0.875rem', color: 'var(--text-secondary)' }}>Clique na imagem para alterar</p>
            </div>

            {/* Informações básicas */}
            <div style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><UserIcon size={14}/> Nome Completo (Leitura)</label>
                <div style={{ padding: '0.85rem', borderRadius: '8px', border: '1px solid var(--border-color)', backgroundColor: 'var(--bg-tertiary)', color: 'var(--text-secondary)' }}>
                  {user.name}
                </div>
              </div>
              
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Mail size={14}/> E-mail (Leitura)</label>
                <div style={{ padding: '0.85rem', borderRadius: '8px', border: '1px solid var(--border-color)', backgroundColor: 'var(--bg-tertiary)', color: 'var(--text-secondary)' }}>
                  {user.email}
                </div>
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><Phone size={14}/> Telefone</label>
                <input 
                  type="text" 
                  name="phone" 
                  value={formData.phone} 
                  onChange={handleInputChange} 
                  style={{
                    padding: '0.85rem',
                    borderRadius: '8px',
                    border: '1px solid var(--border-color)',
                    backgroundColor: 'var(--bg-secondary)',
                    color: 'var(--text-primary)',
                    fontSize: '0.95rem',
                    outline: 'none',
                    transition: 'border-color 0.2s',
                  }} 
                />
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label style={{ fontSize: '0.875rem', fontWeight: 500, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}><MapPin size={14}/> Endereço</label>
                <input 
                  type="text" 
                  name="address" 
                  value={formData.address} 
                  onChange={handleInputChange} 
                  style={{
                    padding: '0.85rem',
                    borderRadius: '8px',
                    border: '1px solid var(--border-color)',
                    backgroundColor: 'var(--bg-secondary)',
                    color: 'var(--text-primary)',
                    fontSize: '0.95rem',
                    outline: 'none',
                    transition: 'border-color 0.2s',
                  }} 
                />
              </div>
            </div>

            <div style={{ width: '100%', height: '1px', backgroundColor: 'var(--border-color)', margin: '0.5rem 0' }}></div>

            <button 
              type="submit" 
              className="btn-primary" 
              disabled={isSaving}
              style={{ width: '100%', display: 'flex', justifyContent: 'center', gap: '0.5rem' }}
            >
              <Save size={18} />
              {isSaving ? 'Salvando...' : 'Salvar Perfil'}
            </button>
          </form>
        </div>
      </div>
    </DashboardLayout>
  )
}
