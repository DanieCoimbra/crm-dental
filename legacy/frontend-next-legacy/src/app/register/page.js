'use client'

import { useAuth } from '@/hooks/auth'
import { useState } from 'react'
import Link from 'next/link'
import PasswordInput from '@/components/PasswordInput'

export default function Register() {
    const { register } = useAuth({
        middleware: 'guest',
        redirectIfAuthenticated: '/',
    })

    const [clinicName, setClinicName] = useState('')
    const [clinicCnpj, setClinicCnpj] = useState('')
    const [name, setName] = useState('')
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [passwordConfirmation, setPasswordConfirmation] = useState('')
    const [errors, setErrors] = useState([])

    const applyCnpjMask = (value) => {
        return value
            .replace(/\D/g, '')
            .replace(/(\d{2})(\d)/, '$1.$2')
            .replace(/(\d{3})(\d)/, '$1.$2')
            .replace(/(\d{3})(\d)/, '$1/$2')
            .replace(/(\d{4})(\d)/, '$1-$2')
            .replace(/(-\d{2})\d+?$/, '$1');
    }

    const handleCnpjChange = (e) => {
        setClinicCnpj(applyCnpjMask(e.target.value));
    }

    const submitForm = async event => {
        event.preventDefault()
        register({
            clinic_name: clinicName,
            clinic_cnpj: clinicCnpj,
            name,
            email,
            password,
            password_confirmation: passwordConfirmation,
            setErrors,
        })
    }

    return (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', backgroundColor: 'var(--bg-primary)', padding: '2rem' }}>
            <div className="card" style={{ width: '100%', maxWidth: '500px' }}>
                <h1 style={{ fontSize: '1.5rem', fontWeight: 600, marginBottom: '0.5rem', textAlign: 'center' }}>Cadastre sua Clínica</h1>
                <p style={{ textAlign: 'center', color: 'var(--text-secondary)', marginBottom: '1.5rem', fontSize: '0.9rem' }}>
                    Teste grátis por 14 dias.
                </p>

                <form onSubmit={submitForm}>
                    {errors.general && (
                        <div style={{ backgroundColor: '#fee2e2', color: '#ef4444', padding: '1rem', borderRadius: '8px', marginBottom: '1.5rem', fontSize: '0.9rem', textAlign: 'center' }}>
                            {errors.general[0]}
                        </div>
                    )}

                    <div style={{ marginBottom: '1rem' }}>
                        <label htmlFor="clinic_name" style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>Nome da Clínica</label>
                        <input
                            id="clinic_name"
                            type="text"
                            value={clinicName}
                            onChange={e => setClinicName(e.target.value)}
                            required
                            autoFocus
                            style={{ width: '100%', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', backgroundColor: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                        />
                        {errors.clinic_name && <span style={{ color: 'red', fontSize: '0.875rem' }}>{errors.clinic_name[0]}</span>}
                    </div>

                    <div style={{ marginBottom: '1rem' }}>
                        <label htmlFor="clinic_cnpj" style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>CNPJ da Clínica</label>
                        <input
                            id="clinic_cnpj"
                            type="text"
                            value={clinicCnpj}
                            onChange={handleCnpjChange}
                            required
                            placeholder="00.000.000/0000-00"
                            maxLength="18"
                            style={{ width: '100%', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', backgroundColor: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                        />
                        {errors.clinic_cnpj && <span style={{ color: 'red', fontSize: '0.875rem' }}>{errors.clinic_cnpj[0]}</span>}
                    </div>

                    <div style={{ marginBottom: '1rem' }}>
                        <label htmlFor="name" style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>Seu Nome (Gerente)</label>
                        <input
                            id="name"
                            type="text"
                            value={name}
                            onChange={e => setName(e.target.value)}
                            required
                            style={{ width: '100%', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', backgroundColor: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                        />
                        {errors.name && <span style={{ color: 'red', fontSize: '0.875rem' }}>{errors.name[0]}</span>}
                    </div>

                    <div style={{ marginBottom: '1rem' }}>
                        <label htmlFor="email" style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>Seu E-mail</label>
                        <input
                            id="email"
                            type="email"
                            value={email}
                            onChange={e => setEmail(e.target.value)}
                            required
                            style={{ width: '100%', padding: '0.75rem', borderRadius: '8px', border: '1px solid var(--border-color)', backgroundColor: 'var(--bg-tertiary)', color: 'var(--text-primary)' }}
                        />
                        {errors.email && <span style={{ color: 'red', fontSize: '0.875rem' }}>{errors.email[0]}</span>}
                    </div>

                    <div style={{ marginBottom: '1rem' }}>
                        <label htmlFor="password" style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>Senha</label>
                        <PasswordInput
                            id="password"
                            value={password}
                            onChange={e => setPassword(e.target.value)}
                            required
                            autoComplete="new-password"
                            hasError={!!errors.password}
                        />
                        {errors.password && <span style={{ color: 'red', fontSize: '0.875rem' }}>{errors.password[0]}</span>}
                    </div>

                    <div style={{ marginBottom: '1.5rem' }}>
                        <label htmlFor="passwordConfirmation" style={{ display: 'block', marginBottom: '0.5rem', color: 'var(--text-secondary)' }}>Confirmar Senha</label>
                        <PasswordInput
                            id="passwordConfirmation"
                            value={passwordConfirmation}
                            onChange={e => setPasswordConfirmation(e.target.value)}
                            required
                        />
                    </div>

                    <button type="submit" className="btn-primary" style={{ width: '100%', marginBottom: '1rem' }}>
                        Criar Conta
                    </button>

                    <div style={{ textAlign: 'center', fontSize: '0.9rem' }}>
                        <Link href="/login" style={{ color: 'var(--primary-color)', textDecoration: 'none' }}>
                            Já tem uma clínica? Faça login
                        </Link>
                    </div>
                </form>
            </div>
        </div>
    )
}
