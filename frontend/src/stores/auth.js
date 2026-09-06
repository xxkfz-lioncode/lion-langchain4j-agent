import { defineStore } from 'pinia'

const TOKEN_KEY = 'lion-satoken'
const NICKNAME_KEY = 'lion-nickname'
const ROLE_KEY = 'lion-role'

/**
 * 登录状态: token / 昵称 / 角色 持久化到 localStorage,
 * 请求拦截器会以 satoken 请求头携带, 与后端 Sa-Token 保持一致。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    nickname: localStorage.getItem(NICKNAME_KEY) || '',
    role: localStorage.getItem(ROLE_KEY) || ''
  }),
  getters: {
    isLogin: (state) => !!state.token,
    isAdmin: (state) => state.role === 'admin'
  },
  actions: {
    setLogin({ token = '', nickname = '', role = '' } = {}) {
      this.token = token
      this.nickname = nickname
      this.role = role
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(NICKNAME_KEY, nickname)
      localStorage.setItem(ROLE_KEY, role)
    },
    setRole(role = '') {
      this.role = role
      localStorage.setItem(ROLE_KEY, role)
    },
    clear() {
      this.token = ''
      this.nickname = ''
      this.role = ''
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(NICKNAME_KEY)
      localStorage.removeItem(ROLE_KEY)
    }
  }
})
