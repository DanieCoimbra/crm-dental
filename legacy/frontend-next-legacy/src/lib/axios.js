import Axios from 'axios'
import { enqueueRequest } from './offlineSync'

const axios = Axios.create({
    baseURL: 'http://localhost:8080/api/v1',
    headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
    },
})

// Inject JWT Token and fix URL path
axios.interceptors.request.use((config) => {
    if (config.url && config.url.startsWith('/api/')) {
        config.url = config.url.replace('/api/', '/')
    }

    if (typeof window !== 'undefined') {
        const token = localStorage.getItem('jwt_token')
        if (token) {
            config.headers.Authorization = `Bearer ${token}`
        }
    }
    return config
})

// Add response interceptor to catch network errors
axios.interceptors.response.use(
    (response) => response,
    async (error) => {
        const isOffline = typeof window !== 'undefined' && !navigator.onLine;
        const isNetworkError = error.code === 'ERR_NETWORK' || error.message === 'Network Error';
        const method = error.config?.method?.toLowerCase();

        if ((isOffline || isNetworkError) && ['post', 'put', 'delete', 'patch'].includes(method)) {
            // Enqueue the request
            await enqueueRequest(error.config);
            
            // Resolve the error so the UI optimistic updates can proceed
            // We return a fake 202 Accepted response so the UI thinks it succeeded
            return Promise.resolve({
                status: 202,
                data: { message: 'Queued offline', queued: true }
            });
        }
        
        return Promise.reject(error);
    }
)

export default axios
