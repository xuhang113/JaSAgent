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
