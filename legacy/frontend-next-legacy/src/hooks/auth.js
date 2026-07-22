import useSWR from 'swr'
import apiGo from '@/lib/api-go'
import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { clearSyncQueue } from '@/lib/offlineSync'
import { del } from 'idb-keyval'

export const useAuth = ({ middleware, redirectIfAuthenticated } = {}) => {
    const router = useRouter()

    const { data: user, error, mutate } = useSWR('/user', () =>
        apiGo
            .get('/user')
            .then(res => {
                let data = res.data
                if (data && data.role && typeof data.role === 'object') {
                    data.role = data.role.name
                }
                return data
            })
            .catch(error => {
                if (error.response?.status !== 409) throw error
                router.push('/verify-email')
            }),
    )

    const register = async ({ setErrors, ...props }) => {
        setErrors([])
        apiGo
            .post('/auth/register', props)
            .then(res => {
                localStorage.setItem('jwt_token', res.data.token)
                mutate()
            })
            .catch(error => {
                if (error.response?.status === 400 || error.response?.status === 422) {
                    // Adaptando mensagens de erro simples da API Go para o formato de array do frontend
                    setErrors({ general: [error.response.data.message || 'Dados inválidos'] })
                } else {
                    setErrors({ general: ['Ocorreu um erro ao processar seu cadastro. Tente novamente.'] })
                    console.error('Registration error:', error)
                }
            })
    }

    const login = async ({ setErrors, setStatus, ...props }) => {
        setErrors([])
        setStatus(null)
        apiGo
            .post('/auth/login', props)
            .then(res => {
                localStorage.setItem('jwt_token', res.data.token)
                mutate()
            })
            .catch(error => {
                if (error.response?.status === 401) {
                    setErrors({ email: [error.response.data.message || 'Acesso negado.'] })
                } else {
                    setErrors({ email: ['Não foi possível conectar ao servidor. O e-mail ou senha estão incorretos.'] })
                    console.error('Login error:', error)
                }
            })
    }

    const logout = async () => {
        // Remover JWT do localStorage
        localStorage.removeItem('jwt_token')
        
        // Limpar estado do SWR imediatamente
        mutate(null, false)
        
        try {
            await clearSyncQueue()
            window.localStorage.setItem('prevent_swr_cache_save', '1')
            await del('dental-crm-swr-cache')
        } catch (e) {
            console.error('Erro ao limpar cache offline', e)
        }

        window.location.pathname = '/login'
    }

    useEffect(() => {
        if (middleware === 'guest' && redirectIfAuthenticated && user)
            router.push(redirectIfAuthenticated)
        if (middleware === 'auth' && error) logout()
    }, [user, error])

    return {
        user,
        login,
        register,
        logout,
        mutate,
    }
}

