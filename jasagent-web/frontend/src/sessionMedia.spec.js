import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { MEDIA_ACCEPT, attachmentCollection, attachmentUrl, mediaKind } from './sessionMedia'

const source = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), './sessionMedia.js'), 'utf8')

describe('会话附件地址', () => {
  it('只给出网关路径，不带查询串或主机名', () => {
    expect(attachmentCollection('s1')).toBe('/api/sessions/s1/attachments')
    expect(attachmentUrl('s1', 'a1')).toBe('/api/sessions/s1/attachments/a1')
    expect(attachmentUrl('s/1', 'a b')).toBe('/api/sessions/s%2F1/attachments/a%20b')
    expect(attachmentUrl('s1', 'a1')).not.toMatch(/\?|token|http:|https:/)
    expect(source).not.toContain('ts.' + 'net')
    expect(MEDIA_ACCEPT.split(',')).toEqual([
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
    ])
    expect(MEDIA_ACCEPT).not.toContain('video/*')
    expect(MEDIA_ACCEPT).not.toContain('image/svg')
  })

  it('只把名单里的类型画成图片、语音或视频', () => {
    expect(mediaKind('image/png')).toBe('image')
    expect(mediaKind('audio/wav')).toBe('audio')
    expect(mediaKind('video/mp4')).toBe('video')
    expect(mediaKind('video/webm')).toBe('video')
    expect(mediaKind('image/svg+xml')).toBe('')
    expect(mediaKind('video/*')).toBe('')
    expect(mediaKind('text/html')).toBe('')
  })
})
