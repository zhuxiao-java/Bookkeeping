const fs = require('node:fs')
const path = require('node:path')
const http = require('node:http')
const { randomUUID } = require('node:crypto')
const { pathToFileURL } = require('node:url')
const client = require('./ai-client.cjs')
const { fail } = client

function trusted(event, window, devUrl, indexFile) {
  if (!window || window.isDestroyed() || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame) return false
  try {
    const url = new URL(event.senderFrame.url)
    url.hash = ''
    return devUrl ? url.origin === new URL(devUrl).origin && url.pathname === '/' && !url.search
      : url.href === pathToFileURL(indexFile).href
  } catch { return false }
}
function validateConfig(input) {
  if (!input || typeof input.baseUrl !== 'string' || typeof input.model !== 'string'
    || typeof input.includeNames !== 'boolean' || !input.model.trim() || input.model.length > 200
    || /[\r\n]/.test(input.model)) throw fail('INVALID_CONFIG')
  client.endpoint(input.baseUrl)
  if (input.apiKey !== undefined && (typeof input.apiKey !== 'string' || !/^[\x21-\x7e]{1,4096}$/.test(input.apiKey))) throw fail('INVALID_KEY')
  return { baseUrl: input.baseUrl.replace(/\/+$/, ''), model: input.model.trim(), includeNames: input.includeNames }
}
class SettingsVault {
  constructor(directory, safeStorage) {
    this.file = path.join(directory, 'monthly-ai-settings.json')
    this.safe = safeStorage
    this.key = ''
    this.config = { baseUrl: '', model: '', includeNames: false, automatic: false, configVersion: randomUUID() }
    try {
      const saved = JSON.parse(fs.readFileSync(this.file, 'utf8'))
      const fields = validateConfig(saved)
      if (!/^[a-zA-Z0-9-]{16,80}$/.test(saved.configVersion)) throw fail('INVALID_CONFIG')
      this.config = { ...fields, configVersion: saved.configVersion, automatic: saved.automatic === true }
      if (saved.encryptedKey && this.canPersist()) this.key = this.safe.decryptString(Buffer.from(saved.encryptedKey, 'base64'))
    } catch { this.config.automatic = false }
    if (!this.key) this.config.automatic = false
  }
  canPersist() {
    return this.safe.isEncryptionAvailable() && this.safe.getSelectedStorageBackend?.() !== 'basic_text'
  }
  view(available) { return { ...this.config, hasKey: !!this.key, persistentKey: this.canPersist(), available } }
  persist() {
    const value = { ...this.config }
    if (this.key && this.canPersist()) value.encryptedKey = this.safe.encryptString(this.key).toString('base64')
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true })
      const tmp = this.file + '.tmp'
      fs.writeFileSync(tmp, JSON.stringify(value), { mode: 0o600 })
      fs.renameSync(tmp, this.file)
    } catch { throw fail('STORAGE_FAILED') }
  }
  save(input) {
    const next = validateConfig(input)
    const changed = ['baseUrl', 'model', 'includeNames'].some(k => next[k] !== this.config[k])
    const oldKey = this.key
    // 更换接收服务时不把旧服务的密钥转交给新服务。
    this.key = input.apiKey ?? (next.baseUrl === this.config.baseUrl ? oldKey : '')
    this.config = { ...this.config, ...next, automatic: false,
      configVersion: changed ? randomUUID() : this.config.configVersion }
    this.persist()
  }
  authorize(version) {
    if (version !== this.config.configVersion || !this.key) throw fail('CONFIG_CHANGED')
    this.config.automatic = true
    try { this.persist() }
    catch { this.config.automatic = false; throw fail('STORAGE_FAILED') }
  }
  revoke(clearKey = false) {
    this.config.automatic = false
    if (clearKey) this.key = ''
    this.persist()
  }
}

/** 仅固定回环地址与固定接口；会话凭据不暴露给 preload。 */
function internalRequest(port, secret, resource, body) {
  return new Promise((resolve, reject) => {
    const req = http.request({ hostname: '127.0.0.1', port, path: '/api/internal/monthly-report/' + resource,
      method: body === undefined ? 'GET' : 'POST', headers: { 'X-Monthly-Session': secret, 'Content-Type': 'application/json' } }, res => {
      let raw = '', size = 0
      res.setEncoding('utf8')
      res.on('data', data => {
        size += Buffer.byteLength(data)
        if (size > 4 * 1024 * 1024) req.destroy(fail('BACKEND'))
        else raw += data
      })
      res.on('end', () => {
        clearTimeout(timer)
        try {
          const value = JSON.parse(raw)
          if (res.statusCode !== 200 || value.code !== 'S0806') throw fail('BACKEND')
          resolve(value.data)
        } catch { reject(fail('BACKEND')) }
      })
      res.on('error', () => { clearTimeout(timer); reject(fail('BACKEND')) })
    })
    const timer = setTimeout(() => req.destroy(fail('BACKEND')), 15000)
    req.on('error', () => { clearTimeout(timer); reject(fail('BACKEND')) })
    req.end(body === undefined ? undefined : JSON.stringify(body))
  })
}
class AiReports {
  constructor({ vault, backend, available, notify = () => {}, aiClient = client }) {
    this.vault = vault
    this.backend = backend
    this.available = available
    this.notify = notify
    this.client = aiClient
    this.busy = false
    this.epoch = 0
    this.abort = null
    this.timer = null
  }
  cancel() { this.epoch++; this.abort?.abort() }
  settings() { return this.vault.view(this.available) }
  start() {
    if (!this.available || this.timer) return
    this.timer = setInterval(() => { void this.tick().catch(() => {}) }, 60000)
    this.timer.unref?.()
    void this.tick().catch(() => {})
  }
  stop() { clearInterval(this.timer); this.timer = null; this.cancel() }
  async preview(id) {
    if (!this.available) throw fail('UNAVAILABLE')
    const detail = await this.backend(id == null ? 'latest' : String(id))
    if (!detail) return null
    return { id: detail.id, snapshotVersion: detail.version, stale: detail.stale,
      configVersion: this.vault.config.configVersion, summary: this.client.summaryFor(detail.snapshot, this.vault.config.includeNames) }
  }
  async tick() {
    if (this.busy || !this.available || !this.vault.config.automatic || !this.vault.key) return
    return this.run(null, true)
  }
  async run(input, automatic = false) {
    if (this.busy) throw fail('BUSY')
    if (!this.available || !this.vault.key) throw fail('UNAVAILABLE')
    const epoch = this.epoch
    const config = { ...this.vault.config }, key = this.vault.key
    if (automatic ? !config.automatic : input?.confirmed !== true || input.configVersion !== config.configVersion) throw fail('CONSENT_REQUIRED')
    this.busy = true
    this.abort = new AbortController()
    let claim
    try {
      const detail = await this.backend(automatic ? 'latest' : String(input.reportId))
      if (!detail || detail.snapshot.count === 0) return false
      if (detail.stale || (!automatic && detail.version !== input.snapshotVersion)) throw fail('STALE')
      if (this.epoch !== epoch) throw fail('CANCELLED')
      claim = await this.backend('claim', { reportId: detail.id, snapshotVersion: detail.version,
        configVersion: config.configVersion, automatic, retry: !automatic && input.retry === true })
      if (!claim) return false
      this.notify(detail.id)
      if (this.epoch !== epoch) throw fail('CANCELLED')
      const result = await this.client.interpret(config, key, claim.snapshot, this.abort.signal)
      if (this.epoch !== epoch) throw fail('CANCELLED')
      const stored = await this.backend('complete', { jobId: claim.job.id, snapshotVersion: detail.version, configVersion: config.configVersion, result })
      if (!stored) throw fail('STALE')
      return true
    } catch (err) {
      if (claim) {
        await this.backend('complete', { jobId: claim.job.id, snapshotVersion: claim.job.snapshotVersion,
          configVersion: config.configVersion, result: null, errorCode: safeCode(err) }).catch(() => {})
      }
      throw fail(safeCode(err))
    } finally {
      if (claim) this.notify(claim.job.reportId)
      this.busy = false
      this.abort = null
    }
  }
  async test() {
    if (this.busy) throw fail('BUSY')
    if (!this.vault.key) throw fail('INVALID_KEY')
    this.busy = true
    this.abort = new AbortController()
    try { return await this.client.testConnection(this.vault.config, this.vault.key, this.abort.signal) }
    finally { this.busy = false; this.abort = null }
  }
}
const CODES = new Set(['INVALID_URL', 'INVALID_CONFIG', 'INVALID_KEY', 'CONFIG_CHANGED', 'CONSENT_REQUIRED', 'UNAVAILABLE', 'BUSY', 'STALE', 'CANCELLED', 'TIMEOUT', 'NETWORK', 'BACKEND', 'INVALID_OUTPUT', 'INVALID_FACTS', 'INVALID_JSON', 'OUTPUT_TOO_LARGE', 'SUMMARY_TOO_LARGE', 'REDIRECT_REJECTED', 'STORAGE_FAILED'])
function safeCode(err) { return CODES.has(err?.code) || /^HTTP_\d{3}$/.test(err?.code) ? err.code : 'REQUEST_FAILED' }
function registerAiReports({ app, safeStorage, ipcMain, getWindow, getPort, session, devUrl }) {
  const vault = new SettingsVault(app.getPath('userData'), safeStorage)
  const indexFile = path.join(__dirname, '..', 'dist', 'index.html')
  const service = new AiReports({ vault, available: typeof session === 'string' && session.length >= 32,
    backend: (resource, body) => internalRequest(getPort(), session, resource, body),
    notify: id => { const w = getWindow(); if (w && !w.isDestroyed()) w.webContents.send('monthly-ai-updated', id) }
  })
  const id = value => { if (!Number.isSafeInteger(value) || value < 1) throw fail('INVALID_CONFIG'); return value }
  const handlers = {
    settings: () => service.settings(),
    save: input => { validateConfig(input); service.cancel(); vault.save(input); return service.settings() },
    authorize: input => {
      if (!service.available) throw fail('UNAVAILABLE')
      if (input?.confirmed !== true) throw fail('CONSENT_REQUIRED')
      vault.authorize(input.configVersion)
      void service.tick().catch(() => {})
      return service.settings()
    },
    revoke: () => { service.cancel(); vault.revoke(); return service.settings() },
    clear: () => { service.cancel(); vault.revoke(true); return service.settings() },
    preview: input => service.preview(input?.reportId == null ? null : id(input.reportId)),
    generate: input => { id(input?.reportId); id(input?.snapshotVersion); return service.run(input) },
    test: () => service.test()
  }
  for (const [name, handler] of Object.entries(handlers)) ipcMain.handle('monthly-ai-' + name, async (event, input) => {
    if (!trusted(event, getWindow(), devUrl, indexFile)) return { ok: false, errorCode: 'UNTRUSTED' }
    try { return { ok: true, value: await handler(input) } }
    catch (err) { return { ok: false, errorCode: safeCode(err) } }
  })
  return service
}
module.exports = { registerAiReports, AiReports, SettingsVault, trusted, validateConfig, internalRequest }
