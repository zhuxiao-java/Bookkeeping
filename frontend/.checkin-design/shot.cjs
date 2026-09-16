/**
 * 临时比选渲染脚本（验证后删除）：离屏渲染 preview.html 并截图。
 */
const { app, BrowserWindow } = require('electron')
const fs = require('fs')
const path = require('path')

const OUT = __dirname
const W = 1160
const H = 1420

app.whenReady().then(async () => {
  const win = new BrowserWindow({ width: W, height: H, show: false, webPreferences: { offscreen: true } })
  await win.loadFile(path.join(OUT, 'preview.html'))
  await new Promise((r) => setTimeout(r, 600))
  const img = await win.webContents.capturePage()
  // Retina 屏捕获为 2x，需 resize 校正回目标尺寸
  fs.writeFileSync(path.join(OUT, 'preview.png'), img.resize({ width: W, height: H, quality: 'best' }).toPNG())
  console.log('SHOT_OK', fs.statSync(path.join(OUT, 'preview.png')).size)
  app.exit(0)
})
