/**
 * 图标光栅化脚本：以 build/logo.svg 为唯一矢量源，用 Electron 离屏渲染生成各尺寸光栅产物。
 * 产物：
 *   - build/icon.png            1024x1024  打包图标源（electron-builder 据此生成 mac .icns / win .ico）
 *   - electron/assets/icon.png  256x256    运行时窗口图标（随 electron/** 打包，生产可用）
 *   - electron/assets/tray.png  32x32      运行时系统托盘图标（随 electron/** 打包，生产可用）
 *   - public/favicon.svg                   浏览器 / DevTools favicon（矢量直接拷贝）
 *
 * 运行：npx electron build/gen-icons.cjs
 * 说明：离屏窗口设为透明，以保留圆角外的 alpha 通道；「记」字依赖系统字体（macOS PingFang SC），
 *       请在 macOS 上执行以保证字形一致。
 */
const { app, BrowserWindow } = require('electron')
const fs = require('fs')
const path = require('path')

const ROOT = path.join(__dirname, '..')
const LOGO_SVG = path.join(__dirname, 'logo.svg')

const RASTER_TARGETS = [
  { out: path.join(ROOT, 'build', 'icon.png'), size: 1024 },
  { out: path.join(ROOT, 'electron', 'assets', 'icon.png'), size: 256 },
  { out: path.join(ROOT, 'electron', 'assets', 'tray.png'), size: 32 }
]

const PAGE_HTML = (svg) => `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
  html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:transparent}
  svg{display:block;width:100vw;height:100vh}
</style></head><body>${svg}</body></html>`

app.whenReady().then(async () => {
  const svg = fs.readFileSync(LOGO_SVG, 'utf8')

  const win = new BrowserWindow({
    show: false,
    transparent: true,
    width: 1024,
    height: 1024,
    webPreferences: { offscreen: true }
  })

  await win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(PAGE_HTML(svg)))
  // 等待字体与首帧就绪
  await new Promise((r) => setTimeout(r, 500))

  for (const target of RASTER_TARGETS) {
    win.setSize(target.size, target.size)
    await new Promise((r) => setTimeout(r, 200))
    const captured = await win.webContents.capturePage()
    // 离屏捕获带 Retina 倍率，统一缩放回目标物理尺寸（保留 alpha）
    const image = captured.resize({ width: target.size, height: target.size, quality: 'best' })
    fs.mkdirSync(path.dirname(target.out), { recursive: true })
    fs.writeFileSync(target.out, image.toPNG())
    console.log('saved:', target.out, image.getSize())
  }

  const publicDir = path.join(ROOT, 'public')
  fs.mkdirSync(publicDir, { recursive: true })
  fs.writeFileSync(path.join(publicDir, 'favicon.svg'), svg)
  console.log('saved:', path.join(publicDir, 'favicon.svg'))

  app.quit()
})
