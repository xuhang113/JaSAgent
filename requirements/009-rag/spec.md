# 009 检索

## 这次要改什么

现状是工作流有 retrieve 端口，但还没有三路召回、融合、重排和溯源过滤。这次补上这条管道。不改查询改写，也不引入向量数据库服务。测试使用假的 reranker，不访问外网。

## 模块

这次新增 `jasagent-rag`。它只依赖 `jasagent-api`。

## 改动地方

语料放在用户目录或工作区目录的 `rag/docs`，支持 md 和 txt。先按标题切开，再按约 800 字、重叠 100 字切开。索引按 scope 分开放。

一次检索并行做 lexical、vector 余弦和标题路径 token 匹配。三路用 Reciprocal Rank Fusion 合并，k 为 60，分数是各路 `1/(k+rank)` 的和。排名从 1 开始。融合后只把最高的 20 条交给 rerank。rerank 分会改写最终名次。

质量校验顺序：没有完整溯源的命中丢掉；同一 contentHash 只留一条；rerank 分低于 0.35 的丢掉。一条都不剩就返回空结果，原因是 `quality-empty`。

指定 scope 为 user 时不返回只存在于工作区的文档，反过来也一样。溯源至少含 sourceScope、uri、docId、chunkId、start、end、contentHash、fusionScore、rerankScore。

## 数据库

这次不改表。

## 中间件

这次不访问外网，也不使用向量数据库。

## 测试要求

1. 三路名次不同时，融合后的第一名不是 lexical 的第一名。
2. k 为 60 时，一条同时出现在三路的融合分数等于三路 `1/(60+rank)` 的和。
3. 融合结果超过 20 条时，交给 rerank 的只有 20 条。
4. rerank 分可以改写融合后的名次。
5. 缺少 uri 的命中被丢掉。
6. rerank 分低于 0.35 的命中被丢掉。
7. 同一 contentHash 只留下一条。
8. 全部被丢掉时，结果为空且原因是 `quality-empty`。
9. scope 为 user 时，不返回只放在工作区的文档。
10. scope 为 workspace 时，不返回只放在用户目录的文档。
11. 带标题的正文会按标题切开，超长段落再按 800 字重叠 100 字切开。
12. md 和 txt 都能被索引并检索到。

## 还没定

无。
