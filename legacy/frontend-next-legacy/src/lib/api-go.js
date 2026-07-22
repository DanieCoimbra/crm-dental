import Axios from 'axios'

const apiGo = Axios.create({
    baseURL: 'http://localhost:8080/api/v1',
    headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
    },
})

// Adiciona o Token JWT em todas as requisições
apiGo.interceptors.request.use((config) => {
    if (typeof window !== 'undefined') {
        const token = localStorage.getItem('jwt_token')
        if (token) {
            config.headers.Authorization = `Bearer ${token}`
        }
    }
    return config
})

export default apiGo
