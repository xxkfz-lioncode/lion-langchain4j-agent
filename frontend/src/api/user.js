import request from './request'

/** 用户分页列表 { pageNum, pageSize, keyword } */
export const pageUsers = (params) => request.get('/api/user/page', { params })

/** 新增用户 { username, password, nickname } */
export const createUser = (data) => request.post('/api/user', data)

/** 编辑用户 { username, nickname, avatar } */
export const updateUser = (id, data) => request.put(`/api/user/${id}`, data)

/** 删除用户 */
export const deleteUser = (id) => request.delete(`/api/user/${id}`)

/** 启用/禁用用户 status: 1 启用 / 0 禁用 */
export const updateUserStatus = (id, status) =>
  request.put(`/api/user/${id}/status`, { status })

/** 重置密码 { password } */
export const resetPassword = (id, password) =>
  request.put(`/api/user/${id}/password`, { password })
