import { describe, expect, it } from 'vitest'
import { CLUSTER_PATH, clusterRows, consoleModels, readRoutedAgent, withConsoleModels } from './gateway'

describe('网关控制台目录', () => {
  it('保留接口里的 DeepSeek，并在末尾加上 grok-4.7', () => {
    const models = withConsoleModels([
      { id: 'deepseek-flash', provider: 'deepseek', label: 'deepseek-flash' },
      { id: 'deepseek-v4-pro', provider: 'deepseek', label: 'deepseek-v4-pro' }
    ])
    expect(models.map((item) => item.id)).toEqual(['deepseek-flash', 'deepseek-v4-pro', 'grok-4.7'])
    expect(models[2].provider).toBe('grok')
  })

  it('接口已经给了 grok-4.7 时不重复追加', () => {
    const models = withConsoleModels([
      { id: 'deepseek-flash', provider: 'deepseek', label: 'deepseek-flash' },
      { id: 'grok-4.7', provider: 'grok', label: 'grok-4.7' }
    ])
    expect(models).toEqual([
      { id: 'deepseek-flash', provider: 'deepseek', label: 'deepseek-flash' },
      { id: 'grok-4.7', provider: 'grok', label: 'grok-4.7' }
    ])
  })

  it('空目录仍保留 deepseek-flash，并加上 grok-4.7', () => {
    expect(consoleModels(null).map((item) => item.provider + '|' + item.id)).toEqual([
      'deepseek|deepseek-flash',
      'grok|grok-4.7'
    ])
    expect(consoleModels({ models: [] }).map((item) => item.id)).toEqual(['deepseek-flash', 'grok-4.7'])
  })
})

describe('当前请求落到的 agent', () => {
  it('优先读 agentInstanceId 和 agentAddress', () => {
    expect(readRoutedAgent({
      agentInstanceId: 'agent-7',
      instanceId: 'other',
      agentAddress: '10.0.0.7:19001',
      address: '127.0.0.1:1'
    })).toEqual({
      instanceId: 'agent-7',
      address: '10.0.0.7:19001'
    })
  })

  it('没有专用字段时用 instanceId 和 address', () => {
    expect(readRoutedAgent({ instanceId: 'agent-2', address: '10.0.0.2:19001' })).toEqual({
      instanceId: 'agent-2',
      address: '10.0.0.2:19001'
    })
  })

  it('缺响应时两个字段都是空串', () => {
    expect(readRoutedAgent(null)).toEqual({ instanceId: '', address: '' })
  })
})

describe('集群行', () => {
  it('按约定字段整理，缺省不算在线', () => {
    expect(CLUSTER_PATH).toBe('/api/cluster')
    expect(clusterRows({
      agents: [
        {
          instanceId: 'agent-1',
          address: '10.0.0.1:19001',
          online: true,
          connectionAlive: false,
          stickyUsers: ['ada', ''],
          lastHeartbeat: '2026-11-12T13:00:00+08:00'
        },
        { instanceId: 'agent-2' }
      ]
    })).toEqual([
      {
        instanceId: 'agent-1',
        address: '10.0.0.1:19001',
        online: true,
        connectionAlive: false,
        stickyUsers: ['ada'],
        lastHeartbeat: '2026-11-12T13:00:00+08:00'
      },
      {
        instanceId: 'agent-2',
        address: '',
        online: false,
        connectionAlive: false,
        stickyUsers: [],
        lastHeartbeat: ''
      }
    ])
  })

  it('没有 agents 数组时是空表', () => {
    expect(clusterRows(null)).toEqual([])
    expect(clusterRows({ agents: 'nope' })).toEqual([])
  })
})
