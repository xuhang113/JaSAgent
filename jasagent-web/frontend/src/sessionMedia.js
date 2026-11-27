/** 允许上传的类型。和网关名单一致，不含宽泛的 video/*，也不含 SVG。 */
export const MEDIA_ACCEPT = [
  'image/jpeg',
  'image/png',
  'image/webp',
  'image/gif',
  'audio/mpeg',
  'audio/mp4',
  'audio/aac',
  'audio/wav',
  'audio/ogg',
  'audio/webm',
  'video/mp4',
  'video/webm'
].join(',')

/**
 * 某一场会话的附件集合地址。没有查询串，也没有主机名。
 *
 * @param sessionId 会话编号
 * @return 以 / 开头的路径
 */
export function attachmentCollection(sessionId) {
  return '/api/sessions/' + encodeURIComponent(sessionId) + '/attachments'
}

/**
 * 某一条附件的地址。下载和播放靠登录 Cookie，地址上不放令牌。
 *
 * @param sessionId 会话编号
 * @param attachmentId 附件编号
 * @return 以 / 开头的路径
 */
export function attachmentUrl(sessionId, attachmentId) {
  return attachmentCollection(sessionId) + '/' + encodeURIComponent(attachmentId)
}

/**
 * 按网关返回的类型选择图片、语音或视频元素。不认识的类型不渲染。
 *
 * @param mediaType 网关探测到的类型
 * @return image、audio、video，或空串
 */
export function mediaKind(mediaType) {
  if (mediaType === 'image/jpeg' || mediaType === 'image/png' || mediaType === 'image/webp' || mediaType === 'image/gif') {
    return 'image'
  }
  if (mediaType === 'audio/mpeg' || mediaType === 'audio/mp4' || mediaType === 'audio/aac'
      || mediaType === 'audio/wav' || mediaType === 'audio/ogg' || mediaType === 'audio/webm') {
    return 'audio'
  }
  if (mediaType === 'video/mp4' || mediaType === 'video/webm') {
    return 'video'
  }
  return ''
}
