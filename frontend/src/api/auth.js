import request from './request'

/** 注册 */
export const register = (data) => request.post('/api/auth/register', data)

/** 登录 */
export const login = (data) => request.post('/api/auth/login', data)

/** 登出 */
export const logout = () => request.post('/api/auth/logout')

/** 当前登录用户信息 */
export const getInfo = () => request.get('/api/auth/info')
