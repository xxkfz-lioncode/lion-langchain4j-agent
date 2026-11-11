import { defineStore } from 'pinia'

const TOKEN_KEY = 'lion-satoken'
const NICKNAME_KEY = 'lion-nickname'
const ROLE_KEY = 'lion-role'

/**
 * 登录态(token/昵称/角色), 持久化到 uni storage。
 * 请求头以 satoken 携带, 与后端 Sa-Token 及 web 端保持一致。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: uni.getStorageSync(TOKEN_KEY) || '',
    nickname: uni.getStorageSync(NICKNAME_KEY) || '',
    role: uni.getStorageSync(ROLE_KEY) || ''
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
      uni.setStorageSync(TOKEN_KEY, token)
      uni.setStorageSync(NICKNAME_KEY, nickname)
      uni.setStorageSync(ROLE_KEY, role)
    },
    clear() {
      this.token = ''
      this.nickname = ''
      this.role = ''
      uni.removeStorageSync(TOKEN_KEY)
      uni.removeStorageSync(NICKNAME_KEY)
      uni.removeStorageSync(ROLE_KEY)
    }
  }
})
