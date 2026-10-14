import axios from 'axios'
import { ElMessage } from 'element-plus'

/*
 * 接口约定的四条，都来自后端真实契约：
 *
 * 1. 响应体是 {code, message, data} 信封，不是裸业务数据。
 *    这里统一把 data 剥出来，页面里直接用返回值即可。
 * 2. 成功状态码不止 200 —— 创建知识库和上传文档返回 201，两个删除接口返回 204。
 * 3. 两个删除接口的 204 没有 body，不能当 JSON 解析。
 * 4. 失败时提示文案取响应体的 message（后端已经保证是中文）；503 单独标注，
 *    它表示外部依赖不可用，和 500 服务端自身故障不是一回事。
 */
const request = axios.create({
  baseURL: '/api',
  // 解析和向量化都是同步接口，大文档要等；默认超时给足，避免长任务被前端掐断
  timeout: 180000
})

request.interceptors.response.use(
  (response) => {
    if (response.status === 204 || response.data === '' || response.data == null) {
      return null
    }
    return response.data.data
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    let message = body?.message || error.message || '请求失败'
    if (status === 503) {
      message += '（外部依赖不可用）'
    }
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default request
