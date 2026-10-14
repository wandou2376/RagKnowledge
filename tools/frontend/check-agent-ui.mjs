// 独立浏览器回归：只服务打包产物和测试夹具，不连接数据库或外部模型。
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { createServer } from 'node:http'
import { existsSync } from 'node:fs'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const dist = path.join(root, 'frontend/dist')
const output = path.join(root, 'target/ui-preview')
const browserPath = process.env.RAG_UI_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
assert(existsSync(browserPath), '请用 RAG_UI_BROWSER 指定已有 Chromium/Edge 可执行文件')
assert(existsSync(path.join(dist, 'index.html')), '请先在 frontend 执行 npm run build')
assert(typeof WebSocket === 'function', '界面回归需要提供全局 WebSocket 的 Node 环境，建议使用 Node 22 或以上版本')
await mkdir(output, { recursive: true })
const profile = path.join(output, `profile-${Date.now()}`)
await mkdir(profile, { recursive: true })
const kbId = '11111111-1111-1111-1111-111111111111'
const kb = { id: kbId, name: '星云数据平台', description: '测试知识库', status: 'ACTIVE' }
const source = {
  index: 1, chunkId: 'chunk-1', originalFileName: '星云平台运维手册.pdf',
  pageNumberStart: 3, pageNumberEnd: 4, score: 0.0325, cited: true,
  content: '处理 E-4012 前先核对连接配置，并检查凭据是否过期。若配置正确仍无法连接，应交由二线排查。'.repeat(12)
}
const requests = []
let answerCount = 0
let hasKnowledgeBases = true
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://localhost')
    if (url.pathname.startsWith('/api/')) {
      let body = ''
      for await (const part of request) body += part
      requests.push({ method: request.method, path: url.pathname, body })
      let data = []
      if (url.pathname === '/api/knowledge-bases') data = hasKnowledgeBases ? [kb] : []
      else if (url.pathname.endsWith('/chat')) {
        const payload = JSON.parse(body)
        answerCount++
        // 延迟用于检查 pending 状态，失败夹具用于覆盖 finally 与错误气泡。
        await new Promise(resolve => setTimeout(resolve, 350))
        if (payload.question === '测试失败') {
          response.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8' })
          response.end(JSON.stringify({ code: 'ANSWER_GENERATION_FAILED', message: '测试模型暂不可用', data: null }))
          return
        }
        data = {
          conversationId: payload.conversationId,
          answer: 'E-4012 表示连接认证失败。[1]\n\n建议先检查连接配置与凭据，再按文档步骤排查。若仍无法解决，交由二线处理。[1]',
          sources: [source, { ...source, index: 2, cited: false }],
          promptTokens: 120, completionTokens: 80, totalTokens: 200
        }
      } else if (url.pathname.includes('/conversations/')) {
        data = [{ role: 'USER', content: '历史问题' }, { role: 'ASSISTANT', content: '历史回答，仅保存正文。' }]
      } else if (url.pathname.endsWith('/search')) {
        data = [{ ...source, chunkIndex: 0, tokenCount: 100, vectorScore: 0.82, keywordScore: 12 }]
      }
      response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
      response.end(JSON.stringify({ code: 'OK', message: 'success', data }))
      return
    }
    const requested = path.resolve(dist, `.${decodeURIComponent(url.pathname)}`)
    if (requested !== dist && !requested.startsWith(dist + path.sep)) {
      response.writeHead(403).end()
      return
    }
    const file = path.extname(requested) ? requested : path.join(dist, 'index.html')
    const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }
    const content = await readFile(file)
    response.writeHead(200, { 'Content-Type': `${mime[path.extname(file)] || 'application/octet-stream'}; charset=utf-8` })
    response.end(content)
  } catch {
    response.writeHead(404).end()
  }
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const base = `http://127.0.0.1:${server.address().port}`
const browser = spawn(browserPath, [
  '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  '--disable-background-networking', '--disable-extensions', '--remote-debugging-port=0',
  `--user-data-dir=${profile}`, 'about:blank'
], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] })

class Cdp {
  constructor(socket) {
    this.socket = socket
    this.sequence = 0
    this.pending = new Map()
    this.errors = []
    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data)
      if (message.method === 'Runtime.exceptionThrown') this.errors.push(message.params.exceptionDetails)
      if (!message.id) return
      const waiting = this.pending.get(message.id)
      if (!waiting) return
      clearTimeout(waiting.timer)
      this.pending.delete(message.id)
      if (message.error) waiting.reject(new Error(message.error.message))
      else waiting.resolve(message.result)
    })
  }
  send(method, params = {}) {
    const id = ++this.sequence
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`CDP 超时：${method}`)) }, 10000)
      this.pending.set(id, { resolve, reject, timer })
      this.socket.send(JSON.stringify({ id, method, params }))
    })
  }
  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text)
    return result.result.value
  }
}

let rootCdp
let page
let checks = 0
const check = (label, condition) => { assert(condition, label); checks++; console.log(`PASS ${label}`) }
const waitFor = async (expression) => {
  const deadline = Date.now() + 10000
  while (Date.now() < deadline) {
    if (await page.evaluate(expression)) return
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`页面状态未就绪：${expression}`)
}
const click = selector => page.evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`)
const fillQuestion = text => page.evaluate(`(() => {
  const input = document.querySelector('.composer textarea');
  input.value = ${JSON.stringify(text)};
  input.dispatchEvent(new Event('input', { bubbles: true }));
})()`)
const screenshot = async name => {
  await waitFor("!document.querySelector('[class*=\"-enter-active\"], [class*=\"-leave-active\"]') && document.getAnimations().filter(animation => animation.effect?.getTiming().iterations !== Infinity).every(animation => animation.playState === 'finished')")
  const result = await page.send('Page.captureScreenshot', { format: 'png' })
  await writeFile(path.join(output, `${name}.png`), Buffer.from(result.data, 'base64'))
}
const noOverflow = () => page.evaluate('document.documentElement.scrollWidth <= window.innerWidth && document.body.scrollWidth <= window.innerWidth')

try {
  const browserUrl = await new Promise((resolve, reject) => {
    let stderr = ''
    const timer = setTimeout(() => reject(new Error('无法启动隔离的无头浏览器')), 15000)
    browser.stderr.on('data', part => {
      stderr += part.toString()
      const match = stderr.match(/DevTools listening on (ws:\/\/[^\s]+)/)
      if (match) { clearTimeout(timer); resolve(match[1]) }
    })
    browser.once('error', error => { clearTimeout(timer); reject(error) })
    browser.once('exit', code => { clearTimeout(timer); reject(new Error(`浏览器提前退出：${code}`)) })
  })
  const rootSocket = new WebSocket(browserUrl)
  await once(rootSocket, 'open')
  rootCdp = new Cdp(rootSocket)
  const target = await rootCdp.send('Target.createTarget', { url: 'about:blank' })
  const debugBase = `http://${new URL(browserUrl).host}`
  const targets = await (await fetch(`${debugBase}/json/list`)).json()
  const targetInfo = targets.find(item => item.id === target.targetId)
  const socket = new WebSocket(targetInfo.webSocketDebuggerUrl)
  await once(socket, 'open')
  page = new Cdp(socket)
  await page.send('Page.enable')
  await page.send('Runtime.enable')
  await page.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  await page.send('Page.navigate', { url: base })
  await waitFor("document.querySelector('.welcome h1') && !document.querySelector('.composer textarea').disabled")
  check('桌面欢迎界面无水平溢出', await noOverflow())
  await screenshot('desktop-welcome')
  await click('.workspace-location .icon-button')
  await waitFor("document.querySelector('.sidebar').classList.contains('collapsed')")
  check('桌面侧栏可收起', true)
  await click('.workspace-location .icon-button')
  await fillQuestion('E-4012 怎么处理？')
  await page.evaluate("document.querySelector('.composer textarea').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, isComposing: true }))")
  check('输入法确认不会发出聊天请求', answerCount === 0)
  await click('.send-button')
  await waitFor("document.querySelector('.pending')")
  check('请求中发送和切换知识库被禁用', await page.evaluate("document.querySelector('.send-button').disabled && document.querySelector('.new-chat').disabled"))
  await waitFor("document.querySelector('.content')?.textContent.includes('连接认证失败') && !document.querySelector('.pending')")
  check('回答和来源响应式更新', await page.evaluate("document.querySelectorAll('.turn').length === 2 && document.querySelectorAll('.content .cite').length === 2 && !!document.querySelector('.sources')"))
  check('来源默认折叠', await page.evaluate("!document.querySelector('.sources').open"))
  check('会话标识透传且本地索引保存', !!JSON.parse(requests.find(item => item.path.endsWith('/chat')).body).conversationId && await page.evaluate("JSON.parse(localStorage.getItem('rag.conversations'))[" + JSON.stringify(kbId) + "].length === 1"))
  await screenshot('desktop-conversation')
  await page.evaluate("Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { window.copiedAnswer = text } } })")
  await click('.copy-button')
  await waitFor("document.querySelector('.copy-button').textContent.includes('已复制')")
  check('回答复制与成功反馈', await page.evaluate("window.copiedAnswer === document.querySelector('.content').textContent"))
  await click('.sources-summary')
  await waitFor("document.querySelector('.sources').open")
  check('默认只展示被引用来源', await page.evaluate("document.querySelectorAll('.source').length === 1"))
  await click('.toggle')
  await waitFor("document.querySelectorAll('.source').length === 2")
  await click('.source-head')
  check('可展开来源原文', await page.evaluate("document.querySelector('.snippet').classList.contains('expanded')"))
  await screenshot('desktop-sources')
  await click('.sources-summary')
  await fillQuestion('第二个问题')
  await page.evaluate("document.querySelector('.composer textarea').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true }))")
  check('Shift+Enter 不提交', answerCount === 1)
  await page.evaluate("document.querySelector('.composer textarea').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))")
  await waitFor("document.querySelectorAll('.turn').length === 4 && !document.querySelector('.pending')")
  check('Enter 可连续进行第二轮问答', answerCount === 2)
  await fillQuestion('测试失败')
  await click('.send-button')
  await waitFor("document.querySelector('.content.failed') && !document.querySelector('.pending')")
  check('503 后退出等待并显示失败气泡', await page.evaluate("!document.querySelector('.new-chat').disabled && document.querySelector('.content.failed').textContent.includes('请求失败')"))
  await waitFor("!document.querySelector('.el-message')")
  await click('.new-chat')
  await waitFor("!!document.querySelector('.welcome')")
  await click('.conversation-open')
  await waitFor("document.querySelector('.content')?.textContent.includes('历史回答')")
  check('历史会话恢复且不虚构来源', await page.evaluate("!document.querySelector('.sources')"))
  await click('.workspace-location .icon-button')
  await waitFor("document.querySelector('.sidebar').classList.contains('collapsed')")
  await click('.attach-button')
  await waitFor("[...document.querySelectorAll('.el-drawer')].some(el => { const rect = el.getBoundingClientRect(); return rect.width > 0 && rect.left >= -1 && rect.right <= innerWidth + 1 })")
  check('桌面收起侧栏后仍能打开文档抽屉', true)
  await screenshot('desktop-documents')
  await click('.el-drawer__close-btn')
  await waitFor("![...document.querySelectorAll('.el-drawer')].some(el => el.getBoundingClientRect().width > 0)")
  await click('.workspace-location .icon-button')
  await click('.section-action')
  await waitFor("[...document.querySelectorAll('.el-dialog')].some(el => el.getBoundingClientRect().width > 0)")
  check('桌面知识库管理弹窗可用', true)
  await screenshot('desktop-knowledge-bases')
  await click('.el-dialog__headerbtn')
  await waitFor("![...document.querySelectorAll('.el-dialog')].some(el => el.getBoundingClientRect().width > 0)")
  await click('.nav-link[href="/retrieval"]')
  await waitFor("!!document.querySelector('.query-input')")
  check('桌面检索页无水平溢出', await noOverflow())
  await page.evaluate("(() => { const input = document.querySelector('.query-input input'); input.value = '连接认证'; input.dispatchEvent(new Event('input', { bubbles: true })); })()")
  await click('.panel .el-button--primary')
  await waitFor("!!document.querySelector('.hit')")
  check('检索模式和分渠道结果正常展示', await page.evaluate("document.querySelector('.score.vector').textContent.includes('0.8200') && document.querySelector('.score.keyword').textContent.includes('12.0000')"))
  await screenshot('desktop-retrieval')
  await page.send('Emulation.setDeviceMetricsOverride', { width: 1024, height: 768, deviceScaleFactor: 1, mobile: false })
  await page.send('Page.navigate', { url: base })
  await waitFor("!!document.querySelector('.welcome')")
  check('较小桌面窗口无溢出且输入框完整显示', await noOverflow() && await page.evaluate("document.querySelector('.composer').getBoundingClientRect().bottom <= innerHeight"))
  await screenshot('desktop-compact')
  hasKnowledgeBases = false
  await page.send('Page.navigate', { url: base })
  await waitFor("document.querySelector('.setup-link') && document.querySelector('.composer textarea').disabled")
  check('无知识库时引导创建且禁止发送', await page.evaluate("document.querySelector('.send-button').disabled"))
  await screenshot('desktop-no-knowledge-base')
  await click('.setup-link')
  await waitFor("[...document.querySelectorAll('.el-dialog')].some(el => el.getBoundingClientRect().width > 0)")
  check('空状态创建入口能打开管理弹窗', true)
  check('没有未处理的浏览器运行异常', page.errors.length === 0)
  await writeFile(path.join(output, 'report.json'), JSON.stringify({ checks, answerCount, runtimeErrors: page.errors, screenshots: output, externalServicesUsed: false }, null, 2))
  console.log(`完成 ${checks} 项检查，截图与报告：${output}`)
} finally {
  if (rootCdp) await rootCdp.send('Browser.close').catch(() => {})
  page?.socket.close()
  rootCdp?.socket.close()
  browser.kill()
  server.close()
}
