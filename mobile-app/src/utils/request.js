import config from './config'

const TOKEN_KEY = 'lion-satoken'

/** 读取登录 token(与 web 端同一存储键, 便于排查时对照) */
export function getToken() {
  return uni.getStorageSync(TOKEN_KEY) || ''
}

/** 清理登录态(401/退出登录共用) */
export function clearLoginState() {
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync('lion-nickname')
  uni.removeStorageSync('lion-role')
}

/**
 * 统一请求封装: 自动携带 satoken 请求头(与后端 sa-token.token-name 一致),
 * 统一解包后端 Result { code, msg, data }; 401 清登录态并回登录页。
 */
export function request({ url, method = 'GET', data, header = {} }) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: config.BASE_URL + url,
      method,
      data,
      header: {
        'content-type': 'application/json',
        // 注意: 后端 sa-token.token-name 是 "sa-token"(带连字符), 不是 web 端误写的 "satoken"
        // (web 端发错名字仍能用是靠浏览器自动携带的同名 Cookie, uni.request 无 Cookie 机制, 必须发对头)
        'sa-token': getToken(),
        ...header
      },
      success: (res) => {
        if (res.statusCode === 401) {
          clearLoginState()
          uni.reLaunch({ url: '/pages/login/login' })
          reject(new Error('登录已过期, 请重新登录'))
          return
        }
        const body = res.data
        if (body && body.code === 200) {
          resolve(body.data)
        } else {
          reject(new Error((body && body.msg) || `请求失败(HTTP ${res.statusCode})`))
        }
      },
      fail: (err) => reject(new Error(err.errMsg || '网络请求失败'))
    })
  })
}
