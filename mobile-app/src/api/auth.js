import { request } from '@/utils/request'

/** 登录: 返回 { token, nickname, role } */
export const login = (data) => request({ url: '/api/auth/login', method: 'POST', data })

/** 登出 */
export const logout = () => request({ url: '/api/auth/logout', method: 'POST' })

/** 当前登录用户信息(启动时静默刷新角色用) */
export const getInfo = () => request({ url: '/api/auth/info', method: 'GET' })
