'use client'

import { useAuth } from '@/hooks/auth'
import { useState } from 'react'
import Link from 'next/link'
import PasswordInput from '@/components/PasswordInput'

export default function Login() {
    const { login } = useAuth({
        middleware: 'guest',
        redirectIfAuthenticated: '/',
    })

    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [errors, setErrors] = useState([])
    const [status, setStatus] = useState(null)

    const submitForm = async event => {
        event.preventDefault()
        login({ email, password, setErrors, setStatus })
    }

    return (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', backgroundColor: 'var(--bg-primary)' }}>
            <div className="card" style={{ width: '100%', maxWidth: '400px' }}>
                <h1 style={{ fontSize: '1.5rem', fontWeight: 600, marginBottom: '1.5rem', textAlign: 'center' }}>Dental CRM</h1>
                
                {status && <div style={{ color: 'green', marginBottom: '1rem' }}>{status}</div>}

                <form onSubmit={submitForm}>
                    <div style={{ marginBottom: '1rem' }}>
                        <label htmlFor="email" style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>Email</label>
                        <input
                            id="email"
                            type="email"
                            value={email}
                            onChange={e => setEmail(e.target.value)}
                            required
                            autoFocus
                            style={{ width: '100%', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', backgroundColor: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                        />
                        {errors.email && <span style={{ color: 'red', fontSize: '0.875rem' }}>{errors.email[0]}</span>}
                    </div>

                    <div style={{ marginBottom: '1.5rem' }}>
                        <label htmlFor="password" style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>Senha</label>
                        <PasswordInput
                            id="password"
                            value={password}
                            onChange={e => setPassword(e.target.value)}
                            required
                            autoComplete="current-password"
                            hasError={!!errors.password}
                        />
                        {errors.password && <span style={{ color: 'red', fontSize: '0.875rem' }}>{errors.password[0]}</span>}
                    </div>

                    <button type="submit" className="btn-primary" style={{ width: '100%', marginBottom: '1rem' }}>
                        Entrar
                    </button>

                    <div style={{ textAlign: 'center', fontSize: '0.9rem' }}>
                        <Link href="/register" style={{ color: 'var(--primary-color)', textDecoration: 'none' }}>
                            Sua clínica não tem conta? Cadastre-se
                        </Link>
                    </div>
                </form>
            </div>
        </div>
    )
}
