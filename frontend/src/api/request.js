import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

const request = axios.create({
  // 默认空走 Vite 代理; 直连模式可在 .env 中配置 VITE_API_BASE_URL
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 120000 // 千问生成耗时可能较长
})

// 请求拦截: 携带 Sa-Token(请求头名与后端 sa-token.token-name 保持一致)
request.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers['satoken'] = auth.token
  }
  return config
})

// 响应拦截: 统一处理后端 Result { code, msg, data }
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    if (res.code === 401) {
      const auth = useAuthStore()
      auth.clear()
      ElMessage.warning(res.msg || '登录已过期, 请重新登录')
      router.push({ path: '/auth/login' })
      return Promise.reject(new Error(res.msg || '未登录'))
    }
    ElMessage.error(res.msg || '请求失败')
    return Promise.reject(new Error(res.msg || '请求失败'))
  },
  (error) => {
    const msg =
      error.response?.data?.msg ||
      (error.code === 'ECONNABORTED' ? '请求超时, 请稍后重试' : '网络异常, 请检查后端服务')
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
