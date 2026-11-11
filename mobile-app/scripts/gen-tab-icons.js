/**
 * tabBar 图标生成脚本: 用 SDF(带符号距离场)绘制几何图形并抗锯齿,
 * 输出 81x81 RGBA PNG 到 src/static/tab/(uni-app tabBar 推荐尺寸)。
 * 运行: node scripts/gen-tab-icons.js
 */
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')

const SIZE = 81
const OUT_DIR = path.join(__dirname, '..', 'src', 'static', 'tab')

// ---------- PNG 编码(RGBA, 无滤镜) ----------
const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

function crc32(buf) {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}

function writePng(file, rgba) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(SIZE, 0)
  ihdr.writeUInt32BE(SIZE, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 6 // RGBA
  const raw = Buffer.alloc(SIZE * (SIZE * 4 + 1))
  for (let y = 0; y < SIZE; y++) {
    raw[y * (SIZE * 4 + 1)] = 0 // filter: none
    rgba.copy(raw, y * (SIZE * 4 + 1) + 1, y * SIZE * 4, (y + 1) * SIZE * 4)
  }
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ])
  fs.writeFileSync(file, png)
}

// ---------- SDF 绘图 ----------
const sdCircle = (px, py, cx, cy, r) => Math.hypot(px - cx, py - cy) - r

function sdRoundRect(px, py, x, y, w, h, r) {
  const qx = Math.abs(px - (x + w / 2)) - (w / 2 - r)
  const qy = Math.abs(py - (y + h / 2)) - (h / 2 - r)
  return Math.min(Math.max(qx, qy), 0) + Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) - r
}

/** 由 SDF 计算抗锯齿覆盖度(1px 半宽平滑) */
function coverage(sdf) {
  const aa = 1.0
  return Math.max(0, Math.min(1, 0.5 - sdf / (2 * aa)))
}

function hexToRgb(hex) {
  return [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)]
}

/** 用 sdf 表达式渲染单色图标 */
function renderIcon(file, hex, sdfFn) {
  const [r, g, b] = hexToRgb(hex)
  const buf = Buffer.alloc(SIZE * SIZE * 4)
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      const a = coverage(sdfFn(x + 0.5, y + 0.5))
      const i = (y * SIZE + x) * 4
      buf[i] = r
      buf[i + 1] = g
      buf[i + 2] = b
      buf[i + 3] = Math.round(a * 255)
    }
  }
  writePng(file, buf)
  console.log('generated', path.basename(file))
}

// ---------- 图标定义 ----------

// 对话图标: 圆角气泡 + 左下尾巴, 内挖三个圆点
function chatSdf(px, py) {
  const bubble = sdRoundRect(px, py, 13, 13, 55, 44, 15)
  const tail = sdCircle(px, py, 25, 58, 8)
  const shape = Math.min(bubble, tail)
  const d1 = sdCircle(px, py, 27, 35, 4.5)
  const d2 = sdCircle(px, py, 40.5, 35, 4.5)
  const d3 = sdCircle(px, py, 54, 35, 4.5)
  const holes = Math.min(d1, d2, d3)
  return Math.max(shape, -holes)
}

// 我的图标: 圆形头像 + 肩部圆角
function mineSdf(px, py) {
  const head = sdCircle(px, py, 40.5, 27, 13.5)
  const body = sdRoundRect(px, py, 20, 47, 41, 24, 12)
  return Math.min(head, body)
}

fs.mkdirSync(OUT_DIR, { recursive: true })
const INACTIVE = '#9ca3af'
const ACTIVE = '#2d4a8f'
renderIcon(path.join(OUT_DIR, 'chat.png'), INACTIVE, chatSdf)
renderIcon(path.join(OUT_DIR, 'chat-active.png'), ACTIVE, chatSdf)
renderIcon(path.join(OUT_DIR, 'mine.png'), INACTIVE, mineSdf)
renderIcon(path.join(OUT_DIR, 'mine-active.png'), ACTIVE, mineSdf)
console.log('done ->', OUT_DIR)
