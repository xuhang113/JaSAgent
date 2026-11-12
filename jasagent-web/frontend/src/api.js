/**
 * 调用网关控制台接口。页面由网关提供时与网关同源，path 以 / 开头。
 * 开发服务器把 /api、/login、/logout、/health 转到 http://127.0.0.1:18721。
 * JSON 体会自动序列化，失败时抛出带状态码和 errorCode 的错误。
 *
 * @param path 以 / 开头的网关路径
 * @param options method、headers、body；body 可以是对象、字符串或表单
 * @return 解析后的 JSON；空响应返回 null
 */
export async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) }
  let body = options.body
  if (body && !(body instanceof URLSearchParams) && typeof body !== 'string') {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(body)
  }
  const response = await fetch(path, {
    method: options.method || 'GET',
    headers,
    body,
    credentials: 'same-origin'
  })
  const text = await response.text()
  let data = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
  }
  if (!response.ok) {
    const error = new Error(typeof data === 'object' && data && data.errorCode ? data.errorCode : '请求失败')
    error.status = response.status
    error.data = data
    throw error
  }
  return data
}
