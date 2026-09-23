/**
 * Electron 主进程：
 * - 创建主窗口（开发加载 Vite Dev Server，生产加载 dist/index.html）
 * - 系统托盘：关闭窗口隐藏到托盘，托盘菜单可显示窗口 / 快速记账 / 退出
 * - 全局快捷键 Cmd/Ctrl+Shift+B：唤起窗口并打开快速记账
 * - 窗口位置记忆：关闭时保存 bounds，下次启动恢复
 * - 生产环境拉起 Java 后端子进程（jpackage 产物），轮询健康检查
 * - 应用退出时回收 Java 子进程
 *
 * Java 后端产物约定：打包时放置于 resources/java/ 下（见 electron-builder.yml
 * 的 extraResources），运行时通过 process.resourcesPath 定位。
 */
const { app, BrowserWindow, Menu, Tray, dialog, globalShortcut, ipcMain, Notification } = require('electron')
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')
const http = require('http')
const net = require('net')

const DEV_SERVER_URL = 'http://127.0.0.1:5173'
const BACKEND_PORT = 8080
/** 实际使用的后端端口（OPT-08）：8080 被占用时降级到空闲端口，启动时探测确定 */
let backendPort = BACKEND_PORT
/** 动态端口探测的最大尝试次数（从 BACKEND_PORT 起递增） */
const PORT_PROBE_LIMIT = 50
const HEALTH_PATH = '/api/health'
const HEALTH_INTERVAL_MS = 500
const HEALTH_TIMEOUT_MS = 30000
const QUICK_RECORD_ACCELERATOR = 'CommandOrControl+Shift+B'
/** 当前生效的全局快捷键（accelerator 字符串）；用户可在设置页自定义，运行期经 IPC 重注册（EL-06） */
let currentQuickRecordAccelerator = QUICK_RECORD_ACCELERATOR

const isDev = !app.isPackaged

/** @type {BrowserWindow | null} */
let mainWindow = null
/** @type {BrowserWindow | null} 启动闪屏窗口（EL-02：冷启动过渡） */
let splashWindow = null
/** 闪屏页面是否已就绪（dom-ready），用于安全下发状态文案 */
let splashReady = false
/** 闪屏就绪前暂存的最新状态文案，dom-ready 后补发 */
let pendingSplashStatus = null
/** @type {Tray | null} */
let tray = null
/** @type {import('child_process').ChildProcess | null} */
let javaProcess = null
let quitting = false
let restartCount = 0
/** 手动重启后端期间为 true，用于抑制 exit 处理器的崩溃自动重启（避免与手动重启叠加） */
let manualRestart = false

/** 窗口位置持久化文件路径 */
function boundsFilePath() {
  return path.join(app.getPath('userData'), 'window-bounds.json')
}

/** 读取上次保存的窗口 bounds，失败或非法时返回 null */
function loadBounds() {
  try {
    const bounds = JSON.parse(fs.readFileSync(boundsFilePath(), 'utf-8'))
    if (
      Number.isFinite(bounds.width) && bounds.width >= 400 &&
      Number.isFinite(bounds.height) && bounds.height >= 300
    ) {
      return bounds
    }
  } catch {
    // 首次启动或文件损坏，使用默认尺寸
  }
  return null
}

/** 保存当前窗口 bounds（最大化状态只存 restore 尺寸） */
function saveBounds() {
  if (!mainWindow || mainWindow.isDestroyed()) return
  try {
    const bounds = mainWindow.isMaximized() ? mainWindow.getNormalBounds() : mainWindow.getBounds()
    fs.writeFileSync(boundsFilePath(), JSON.stringify(bounds))
  } catch (err) {
    console.warn('[main] 保存窗口位置失败:', err)
  }
}

/** 定位 Java 后端可执行文件（jpackage 产物）。
 *  macOS 的 launcher 按「自身位置 ../../Contents/app/xxx.cfg」找配置，必须保留完整 .app 结构，
 *  拆出 MacOS/runtime 会导致 cfg 找不到而启动失败 */
function resolveBackendEntry() {
  if (process.platform === 'win32') {
    return path.join(process.resourcesPath, 'java', 'bookkeeping-backend.exe')
  }
  if (process.platform === 'darwin') {
    return path.join(process.resourcesPath, 'java', 'bookkeeping-backend.app', 'Contents', 'MacOS', 'bookkeeping-backend')
  }
  return path.join(process.resourcesPath, 'java', 'bin', 'bookkeeping-backend')
}

/** 后端工作目录：bookkeeping.dir=./data 相对 cwd 解析，必须显式指定可写目录（userData），
 *  否则 macOS 从 Dock 启动时 cwd 为 / ，SQLite 无法创建数据文件。
 *  注意：SQLite JDBC 不会自动创建父目录，./data 子目录必须预先建好，
 *  否则首次启动报 SQLITE_CANTOPEN */
function resolveBackendCwd() {
  const cwd = app.getPath('userData')
  fs.mkdirSync(path.join(cwd, 'data'), { recursive: true })
  return cwd
}

/** 探测从 preferred 起的第一个空闲端口（OPT-08）：8080 被占用时自动降级，
 *  避免后端因端口冲突起不来。连续 PORT_PROBE_LIMIT 个都被占用时回退到 preferred，
 *  交由后端自行报错（极端情况，通常不会发生）。 */
function findFreePort(preferred) {
  return new Promise((resolve) => {
    let port = preferred
    const tryPort = () => {
      const srv = net.createServer()
      srv.once('error', () => {
        // 端口被占用（EADDRINUSE）或其他错误：尝试下一个
        srv.close()
        port++
        if (port > preferred + PORT_PROBE_LIMIT) {
          resolve(preferred)
          return
        }
        tryPort()
      })
      srv.once('listening', () => {
        srv.close(() => resolve(port))
      })
      srv.listen(port, '127.0.0.1')
    }
    tryPort()
  })
}

/** 生产环境启动 Java 后端子进程 */
function startBackend() {
  const entry = resolveBackendEntry()
  // 通过启动参数把探测到的端口传给 Spring Boot（--server.port，OPT-08）
  javaProcess = spawn(entry, [`--server.port=${backendPort}`, '--server.address=127.0.0.1'], {
    env: { ...process.env },
    cwd: resolveBackendCwd(),
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true
  })

  javaProcess.stdout.on('data', (data) => {
    console.log(`[backend] ${data}`)
  })
  javaProcess.stderr.on('data', (data) => {
    console.error(`[backend] ${data}`)
  })
  javaProcess.on('exit', (code) => {
    console.warn(`[backend] Java 进程退出，code=${code}`)
    javaProcess = null
    if (!quitting && !manualRestart && mainWindow && !mainWindow.isDestroyed()) {
      // 后端异常退出：提示用户并自动重启（最多 3 次）
      if (restartCount < 3) {
        restartCount++
        startBackend()
      } else {
        dialog.showErrorBox(
          '后端服务异常',
          'Java 后端服务多次重启失败，应用即将退出。请查看日志后重试。'
        )
        app.quit()
      }
    }
  })
}

/** 健康检查：轮询后端接口直至可达或超时 */
function waitBackendReady() {
  return new Promise((resolve, reject) => {
    const startedAt = Date.now()
    const timer = setInterval(() => {
      if (Date.now() - startedAt > HEALTH_TIMEOUT_MS) {
        clearInterval(timer)
        reject(new Error('后端启动超时'))
        return
      }
      const req = http.get(
        { host: '127.0.0.1', port: backendPort, path: HEALTH_PATH, timeout: 2000 },
        (res) => {
          // 仅 HTTP 200 不足以判定就绪：DB 未连通时 body.data.status 为 DOWN，需继续等待（NEW-05）
          let raw = ''
          res.setEncoding('utf8')
          res.on('data', (chunk) => { raw += chunk })
          res.on('end', () => {
            if (res.statusCode === 200 && isHealthUp(raw)) {
              clearInterval(timer)
              resolve()
            }
          })
        }
      )
      req.on('error', () => {
        // 端口未就绪，继续轮询
      })
      req.on('timeout', () => req.destroy())
    }, HEALTH_INTERVAL_MS)
  })
}

/** 解析健康探针响应体：仅当 body.data.status==='UP'（DB 连通）才算就绪；非预期 JSON 保守视为未就绪（NEW-05） */
function isHealthUp(raw) {
  try {
    const body = JSON.parse(raw)
    const data = body && body.data ? body.data : body
    return !!data && data.status === 'UP'
  } catch {
    return false
  }
}

/** 手动重启后端子进程（备份恢复覆盖 accounts.db 后，需重新加载新库）。
 *  开发环境后端不由本进程托管，直接返回 ok:false 交由开发者手动重启。 */
async function restartBackend() {
  if (isDev) return { ok: false, reason: 'dev' }
  manualRestart = true
  try {
    if (javaProcess) {
      const proc = javaProcess
      javaProcess = null
      const exited = new Promise((resolve) => proc.once('exit', resolve))
      proc.kill()
      await exited
    }
    // 等待操作系统释放旧进程占用的 SQLite 文件句柄
    await new Promise((resolve) => setTimeout(resolve, 500))
    restartCount = 0
    startBackend()
    await waitBackendReady()
    return { ok: true }
  } catch (err) {
    return { ok: false, reason: err && err.message ? err.message : String(err) }
  } finally {
    manualRestart = false
  }
}

ipcMain.handle('restart-backend', restartBackend)

/** 前端错误日志（userData/frontend.log）滚动上限 2MB，防止无限增长（OPT-12） */
const FRONTEND_LOG_LIMIT = 2 * 1024 * 1024

/** 渲染进程全局错误落盘：追加一行；超限时截断保留后半段，保留最近日志 */
function appendFrontendLog(line) {
  try {
    const file = path.join(app.getPath('userData'), 'frontend.log')
    if (fs.existsSync(file) && fs.statSync(file).size > FRONTEND_LOG_LIMIT) {
      const buf = fs.readFileSync(file, 'utf8')
      fs.writeFileSync(file, buf.slice(Math.floor(buf.length / 2)))
    }
    fs.appendFileSync(file, `${new Date().toISOString()} ${line}\n`)
  } catch {
    // 落盘失败不应影响渲染进程
  }
}

ipcMain.on('log-error', (_event, payload) => {
  appendFrontendLog(typeof payload === 'string' ? payload : JSON.stringify(payload))
})

/** 渲染进程请求发系统通知（EL-04）：窗口失焦/收进托盘时应用内通知看不到，用 OS 通知兜底。
 *  点击通知唤起主窗口；Notification 不支持时静默忽略。 */
ipcMain.on('show-notification', (_event, payload) => {
  try {
    if (!Notification.isSupported()) return
    const title = payload && payload.title ? String(payload.title) : '记账本'
    const body = payload && payload.body ? String(payload.body) : ''
    if (!body) return
    const notification = new Notification({ title, body })
    notification.on('click', () => {
      if (process.platform === 'darwin') app.dock.show()
      showWindow()
    })
    notification.show()
  } catch {
    // 通知失败不影响主流程
  }
})

/** 自定义快速记账全局快捷键（EL-06）：先注册新键，成功后再释放旧键，
 *  避免注册失败（被占用/非法）后无快捷键可用。返回 {ok, reason}。 */
ipcMain.handle('set-quick-record-shortcut', (_event, accelerator) => {
  const accel = typeof accelerator === 'string' ? accelerator.trim() : ''
  if (!accel) return { ok: false, reason: 'empty' }
  if (accel === currentQuickRecordAccelerator) return { ok: true }
  let ok = false
  try {
    ok = globalShortcut.register(accel, openQuickRecord)
  } catch {
    ok = false
  }
  if (!ok) return { ok: false, reason: 'register-failed' }
  if (currentQuickRecordAccelerator) globalShortcut.unregister(currentQuickRecordAccelerator)
  currentQuickRecordAccelerator = accel
  return { ok: true }
})

/** 显示主窗口并聚焦 */
function showWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) {
    createWindow()
    return
  }
  if (!mainWindow.isVisible()) mainWindow.show()
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.focus()
}

/** 唤起窗口并通知渲染进程打开快速记账弹窗 */
function openQuickRecord() {
  showWindow()
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('open-quick-record')
  }
}

/** 创建启动闪屏：无边框透明窗，展示 Logo + 加载进度，覆盖冷启动「白等」空窗期。
 *  主窗口在后端就绪前保持隐藏，闪屏先给出即时视觉反馈（RUN-01）。 */
function createSplashWindow() {
  if (splashWindow && !splashWindow.isDestroyed()) return
  splashReady = false
  pendingSplashStatus = null
  splashWindow = new BrowserWindow({
    width: 380,
    height: 300,
    frame: false,
    transparent: true,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    hasShadow: false,
    center: true,
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false
      // 闪屏为纯静态页面，无需 preload
    }
  })
  splashWindow.loadFile(path.join(__dirname, 'splash.html'))
  splashWindow.once('ready-to-show', () => {
    if (splashWindow && !splashWindow.isDestroyed()) splashWindow.show()
  })
  splashWindow.webContents.once('dom-ready', () => {
    splashReady = true
    if (pendingSplashStatus) applySplashStatus(pendingSplashStatus)
  })
  splashWindow.on('closed', () => {
    splashWindow = null
    splashReady = false
  })
}

/** 向闪屏下发启动状态文案；页面未就绪时先暂存，dom-ready 后补发最新一条 */
function updateSplashStatus(text) {
  pendingSplashStatus = text
  if (splashReady) applySplashStatus(text)
}

/** 实际注入状态文案：executeJavaScript 由主进程调用，不受页面 CSP 限制 */
function applySplashStatus(text) {
  if (!splashWindow || splashWindow.isDestroyed()) return
  splashWindow.webContents
    .executeJavaScript(`window.setSplashStatus && window.setSplashStatus(${JSON.stringify(text)})`)
    .catch(() => {})
}

/** 关闭并回收闪屏窗口（后端就绪切主窗口、或启动失败弹框前调用） */
function closeSplashWindow() {
  pendingSplashStatus = null
  splashReady = false
  if (splashWindow && !splashWindow.isDestroyed()) {
    splashWindow.close()
  }
  splashWindow = null
}

/** 渲染进程崩溃弹框防重入标志（NEW-05） */
let rendererCrashPrompting = false

/** 渲染进程异常（崩溃/无响应/加载失败）兜底：提示重载或退出，避免困在白屏（NEW-05） */
function handleRendererCrash(reason) {
  console.error('[main] 渲染进程异常:', reason)
  if (rendererCrashPrompting) return
  if (!mainWindow || mainWindow.isDestroyed()) return
  rendererCrashPrompting = true
  let choice = 0
  try {
    choice = dialog.showMessageBoxSync(mainWindow, {
      type: 'error',
      title: '界面异常',
      message: '应用界面出现异常，是否重新加载？',
      detail: `原因：${reason}`,
      buttons: ['重新加载', '退出应用']
    })
  } finally {
    rendererCrashPrompting = false
  }
  if (choice === 0) {
    if (isDev) mainWindow.loadURL(DEV_SERVER_URL)
    else mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  } else {
    quitting = true
    app.quit()
  }
}

function createWindow() {
  // 启动失败重试会再次进入 bootstrap，主窗口若仍在则复用，避免重复创建
  if (mainWindow && !mainWindow.isDestroyed()) return
  const savedBounds = loadBounds()
  mainWindow = new BrowserWindow({
    ...(savedBounds || { width: 1280, height: 820 }),
    minWidth: 1024,
    minHeight: 768,
    show: false,
    autoHideMenuBar: true,
    title: '记账本',
    // 运行时图标置于 electron/assets（随包发布）；打包图标源见 build/icon.png
    icon: path.join(__dirname, 'assets', 'icon.png'),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.cjs'),
      // 生产环境把实际后端端口注入渲染进程 argv，供 preload 解析 apiBase（OPT-08）
      ...(isDev ? {} : { additionalArguments: [`--backend-port=${backendPort}`] })
    }
  })

  if (isDev) {
    mainWindow.loadURL(DEV_SERVER_URL)
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  } else {
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  }

  // 渲染进程崩溃/白屏兜底（NEW-05）：给出重载入口，避免用户困在空白窗口
  mainWindow.webContents.on('render-process-gone', (_event, details) => {
    handleRendererCrash(details && details.reason ? details.reason : 'gone')
  })
  mainWindow.webContents.on('did-fail-load', (_event, errorCode) => {
    // -3 = ERR_ABORTED（主动取消/重定向），非真实失败，忽略
    if (errorCode === -3) return
    handleRendererCrash(`load-failed(${errorCode})`)
  })
  mainWindow.on('unresponsive', () => handleRendererCrash('unresponsive'))

  // 关闭窗口：隐藏到托盘而非退出（真正退出走托盘菜单 / before-quit）
  mainWindow.on('close', (e) => {
    if (!quitting) {
      e.preventDefault()
      saveBounds()
      mainWindow.hide()
      if (process.platform === 'darwin') app.dock.hide()
    }
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

/** 创建系统托盘 */
function createTray() {
  const iconPath = path.join(__dirname, 'assets', 'tray.png')
  tray = new Tray(iconPath)
  tray.setToolTip('记账本')

  const menu = Menu.buildFromTemplate([
    { label: '显示主窗口', click: () => { if (process.platform === 'darwin') app.dock.show(); showWindow() } },
    { label: '快速记账', click: () => { if (process.platform === 'darwin') app.dock.show(); openQuickRecord() } },
    { type: 'separator' },
    { label: '退出', click: () => { quitting = true; app.quit() } }
  ])
  tray.setContextMenu(menu)
  // 单击托盘图标显示窗口（macOS 保留左键弹菜单的惯例，用双击）
  tray.on(process.platform === 'darwin' ? 'double-click' : 'click', () => {
    if (process.platform === 'darwin') app.dock.show()
    showWindow()
  })
}

/** 注册全局快捷键 */
function registerShortcuts() {
  const ok = globalShortcut.register(currentQuickRecordAccelerator, openQuickRecord)
  if (!ok) {
    console.warn(`[main] 全局快捷键 ${currentQuickRecordAccelerator} 注册失败（可能被占用）`)
  }
}

async function bootstrap() {
  // 先弹闪屏给出即时反馈
  createSplashWindow()
  // 生产环境在创建窗口前探测空闲端口（OPT-08）：端口经 additionalArguments 注入渲染进程，
  // 故必须先于 createWindow 确定；随后以 --server.port 启动后端
  if (!isDev) {
    backendPort = await findFreePort(BACKEND_PORT)
  }
  // 隐藏式创建主窗口并预加载页面
  createWindow()
  createTray()
  registerShortcuts()

  try {
    updateSplashStatus(isDev ? '正在连接本地后端服务' : '正在启动后端服务')
    if (!isDev) {
      startBackend()
    }
    updateSplashStatus('正在检查服务状态')
    await waitBackendReady()
    updateSplashStatus('服务已就绪，正在打开主界面')
    if (mainWindow) mainWindow.show()
    closeSplashWindow()
  } catch (err) {
    console.error('[main] 启动失败:', err)
    // 先收起闪屏，避免遮挡错误弹框
    closeSplashWindow()
    const choice = dialog.showMessageBoxSync({
      type: 'error',
      title: '启动失败',
      message: '后端服务启动失败，是否重试？',
      buttons: ['重试', '退出']
    })
    if (choice === 0) {
      // 重试：重启应用流程
      if (javaProcess) {
        javaProcess.kill()
        javaProcess = null
      }
      restartCount = 0
      bootstrap()
    } else {
      app.quit()
    }
  }
}

// 单实例锁（NEW-05）：防止重复启动导致多份 Java 后端争抢同一 SQLite 文件
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    // 二次启动：唤起已运行实例的主窗口（macOS 先恢复 dock）
    if (process.platform === 'darwin') app.dock.show()
    showWindow()
  })
  app.whenReady().then(bootstrap)
}

app.on('window-all-closed', () => {
  // 有托盘常驻，窗口全部关闭时不退出（退出统一走托盘菜单 / before-quit）
})

app.on('activate', () => {
  if (process.platform === 'darwin') app.dock.show()
  if (BrowserWindow.getAllWindows().length === 0) createWindow()
  else showWindow()
})

app.on('before-quit', () => {
  quitting = true
  saveBounds()
})

/** 退出时释放全局快捷键并终止 Java 子进程，避免残留进程占用 SQLite 文件 */
app.on('will-quit', () => {
  globalShortcut.unregisterAll()
  closeSplashWindow()
  if (tray) {
    tray.destroy()
    tray = null
  }
  if (javaProcess) {
    javaProcess.kill()
    javaProcess = null
  }
})
