'use client'

import { useState, useEffect, useRef } from 'react'
import { useAuth } from '@/hooks/auth'
import { useTheme } from '@/app/ThemeProvider'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { Calendar, Users, Settings, LogOut, Sun, Moon, Activity, Trash2, Shield, MessageCircle, ClipboardList, Menu, X } from 'lucide-react'
import OfflineBanner from '@/components/OfflineBanner'

export default function DashboardLayout({ children }) {
  const { user, logout } = useAuth({ middleware: 'auth' })
  const { theme, toggleTheme } = useTheme()
  const pathname = usePathname()
  const [trashStatus, setTrashStatus] = useState(null)
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const menuRef = useRef(null)

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) {
        setIsMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  useEffect(() => {
    if (user && (['owner', 'manager', 'receptionist'].includes(user.role))) {
      import('@/lib/axios').then(axios => {
        axios.default.get('/api/trash/status')
          .then(res => setTrashStatus(res.data))
          .catch(err => console.error(err))
      })
    }
  }, [user, pathname])

  if (!user) {
    return <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', backgroundColor: 'var(--bg-primary)' }}>Carregando...</div>
  }

  const permissions = user.permissions_list || []
  const isManager = ['owner', 'manager'].includes(user?.role)

  const navItems = [
    { name: 'Calendário', path: '/', icon: <Calendar size={20} /> },
  ]

  if (['owner', 'manager', 'receptionist'].includes(user.role)) {
    navItems.push({ name: 'Fila de Espera', path: '/waitlist', icon: <ClipboardList size={20} /> })
  }

  if (isManager || permissions.includes('view_patients') || permissions.includes('manage_patients') || ['doctor', 'receptionist'].includes(user.role)) {
    navItems.push({ name: 'Pacientes', path: '/patients', icon: <Users size={20} /> })
  }

  if (isManager || permissions.includes('manage_team')) {
    navItems.push({ name: 'Gestão de Equipe', path: '/team', icon: <Shield size={20} /> })
  }

  if (isManager) {
    navItems.push({ name: 'Histórico WhatsApp', path: '/whatsapp-logs', icon: <MessageCircle size={20} /> })
  }


  if (isManager || user.role === 'receptionist' || permissions.includes('manage_team')) {
    navItems.push({ name: 'Lixeira', path: '/trash', icon: <Trash2 size={20} /> })
  }

  if (isManager || permissions.includes('manage_settings')) {
    navItems.push({ name: 'Configurações', path: '/settings', icon: <Settings size={20} /> })
  }

  const getPageTitle = () => {
    const item = navItems.find(i => i.path === pathname)
    return item ? item.name : 'Dashboard'
  }

  const trialEndsAt = user?.clinic?.trial_ends_at ? new Date(user.clinic.trial_ends_at) : null;
  const daysRemaining = trialEndsAt ? Math.max(0, Math.ceil((trialEndsAt - new Date()) / (1000 * 60 * 60 * 24))) : 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh', backgroundColor: 'var(--bg-primary)', color: 'var(--text-primary)' }}>
      {/* Trial Banner */}
      {user?.clinic?.status === 'trial' && (
        <div style={{
          backgroundColor: '#3b82f6',
          color: 'white',
          padding: '0.75rem 2rem',
          textAlign: 'center',
          fontWeight: 500,
          fontSize: '0.9rem',
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          gap: '0.5rem',
          zIndex: 50
        }}>
          <Shield size={18} />
          {`Período de Teste: Sua clínica possui mais ${daysRemaining} dias gratuitos. Assine um plano para evitar interrupções.`}
        </div>
      )}

      {/* Lixeira Banner */}
      {trashStatus && (trashStatus.is_almost_full || trashStatus.is_full) && (
        <div style={{
          backgroundColor: trashStatus.is_full ? '#ef4444' : '#f59e0b',
          color: 'white',
          padding: '0.75rem 2rem',
          textAlign: 'center',
          fontWeight: 500,
          fontSize: '0.9rem',
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          gap: '0.5rem',
          zIndex: 50
        }}>
          <Trash2 size={18} />
          {trashStatus.is_full 
            ? `ATENÇÃO: A lixeira está CHEIA (${trashStatus.total}/${trashStatus.limit}). Você não pode apagar mais nada até esvaziar.` 
            : `Aviso: A lixeira está quase cheia (${trashStatus.total}/${trashStatus.limit}). Gerencie os itens deletados para não bloquear novas exclusões.`}
        </div>
      )}

      {/* Top Bar (Full Width) */}
      <header style={{ 
        height: '73px', 
        borderBottom: '1px solid var(--border-color)', 
        backgroundColor: 'var(--bg-secondary)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '0 2rem',
        position: 'sticky',
        top: 0,
        zIndex: 10
      }}>
        {/* Left: Brand + Page Title */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '2rem' }}>
           <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', color: 'var(--accent)' }}>
             <div style={{ width: 36, height: 36, borderRadius: 10, background: 'linear-gradient(135deg, var(--accent), #8b5cf6)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'white' }}>
               <Activity size={20} />
             </div>
             <span style={{ fontSize: '1.25rem', fontWeight: 700, letterSpacing: '-0.5px' }}>CRM Clinical</span>
           </div>
           <div style={{ width: '1px', height: '24px', backgroundColor: 'var(--border-color)' }}></div>
           <h2 style={{ fontSize: '1.1rem', fontWeight: 500, color: 'var(--text-secondary)' }}>{getPageTitle()}</h2>
        </div>
        
        {/* Right: Welcome, Theme Toggle, Avatar and Hamburger Menu */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '1.5rem', position: 'relative' }} ref={menuRef}>
           <span style={{ fontSize: '0.875rem', fontWeight: 500 }}>Bem-vindo, {user.name}</span>
           
           <button onClick={toggleTheme} style={{ 
             display: 'flex', 
             alignItems: 'center', 
             justifyContent: 'center', 
             width: 36, 
             height: 36, 
             borderRadius: '50%', 
             backgroundColor: 'var(--bg-tertiary)',
             color: 'var(--text-secondary)',
             border: 'none',
             cursor: 'pointer',
             transition: 'all 0.2s'
           }} onMouseEnter={e => e.currentTarget.style.backgroundColor = 'var(--border-color)'} onMouseLeave={e => e.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'}>
             {theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
           </button>

           <Link href="/profile" style={{ textDecoration: 'none' }}>
             <div style={{ 
               width: 40, 
               height: 40, 
               borderRadius: '50%', 
               backgroundColor: 'var(--bg-tertiary)', 
               display: 'flex', 
               alignItems: 'center', 
               justifyContent: 'center', 
               fontWeight: 600, 
               color: 'var(--text-primary)',
               border: '1px solid var(--border-color)',
               cursor: 'pointer',
               overflow: 'hidden'
             }}>
               {user.avatar ? (
                 <img src={`${process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8000'}${user.avatar}`} alt="Avatar" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
               ) : (
                 user.name ? user.name.charAt(0).toUpperCase() : 'U'
               )}
             </div>
           </Link>

           <button onClick={() => setIsMenuOpen(!isMenuOpen)} style={{ 
             display: 'flex', 
             alignItems: 'center', 
             justifyContent: 'center', 
             width: 40, 
             height: 40, 
             borderRadius: '10px', 
             backgroundColor: isMenuOpen ? 'var(--accent)' : 'var(--bg-tertiary)',
             color: isMenuOpen ? 'white' : 'var(--text-primary)',
             border: '1px solid var(--border-color)',
             cursor: 'pointer',
             transition: 'all 0.2s'
           }}>
             {isMenuOpen ? <X size={20} /> : <Menu size={20} />}
           </button>

           {/* Dropdown Menu */}
           {isMenuOpen && (
             <div style={{
               position: 'absolute',
               top: 'calc(100% + 10px)',
               right: 0,
               width: '280px',
               backgroundColor: 'var(--bg-secondary)',
               border: '1px solid var(--border-color)',
               borderRadius: '16px',
               boxShadow: '0 10px 30px -10px rgba(0, 0, 0, 0.2)',
               display: 'flex',
               flexDirection: 'column',
               padding: '1.25rem',
               zIndex: 100
             }}>
               <h3 style={{ fontSize: '0.8rem', fontWeight: 600, color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '1px', marginBottom: '1rem', paddingLeft: '0.5rem' }}>
                 Menu Principal
               </h3>
               
               <nav style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem', marginBottom: '1rem' }}>
                 {navItems.map((item) => {
                   const isActive = pathname === item.path
                   return (
                     <Link key={item.path} href={item.path} onClick={() => setIsMenuOpen(false)} style={{
                       display: 'flex',
                       alignItems: 'center',
                       gap: '0.75rem',
                       padding: '0.75rem',
                       borderRadius: '10px',
                       textDecoration: 'none',
                       color: isActive ? 'white' : 'var(--text-primary)',
                       backgroundColor: isActive ? 'var(--accent)' : 'transparent',
                       fontWeight: isActive ? 500 : 400,
                       fontSize: '0.9rem',
                       transition: 'all 0.2s ease',
                     }}>
                       {item.icon}
                       {item.name}
                     </Link>
                   )
                 })}
               </nav>

               <div style={{ paddingTop: '1rem', borderTop: '1px solid var(--border-color)' }}>
                 <button onClick={logout} className="btn-primary" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.5rem', backgroundColor: 'transparent', color: '#ef4444', border: '1px solid #ef4444', width: '100%', transition: 'all 0.2s', borderRadius: '10px', padding: '0.6rem', fontSize: '0.9rem' }}>
                   <LogOut size={18} />
                   Sair
                 </button>
               </div>
             </div>
           )}
        </div>
      </header>

      {/* Main Content */}
      <div style={{ flex: 1, display: 'flex', padding: '2rem', gap: '2rem', maxWidth: '1600px', margin: '0 auto', width: '100%' }}>
        
        {/* Page Content */}
        <main style={{ flex: 1, overflowY: 'auto' }}>
          {children}
        </main>
        
        <OfflineBanner />
      </div>
    </div>
  )
}
