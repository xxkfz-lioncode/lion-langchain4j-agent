// #ifdef H5
// H5: BASE_URL 留空 => 请求走同源相对路径, 开发期由 Vite 代理转发到后端
// (与 web 端同架构, 无跨域/预检问题; 生产部署需在站点同域下挂反向代理 /api)
export default {
  BASE_URL: ''
}
// #endif

// #ifndef H5
// 小程序/App: 无代理机制, 直连后端。
// 真机必须用电脑局域网 IP(ipconfig 查看 WLAN IPv4, 电脑 IP 变了要同步改这里);
// 微信小程序开发工具需勾选"不校验合法域名", 上线前配置 https 合法域名
export default {
  BASE_URL: 'http://192.168.31.18:8087'
}
// #endif
