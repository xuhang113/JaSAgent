# 009 检索

## 这次要改什么

现状是登录、目录和后续模块可以各自演进，仓库里还没有检索管道。这次在 `jasagent-rag` 里实现按 scope 分开的本地索引、标题与窗口切分、词法 BM25、向量余弦、标题路径三路召回、RRF 融合、rerank、溯源和质量校验。不改登录口令、模型路由、工作流步骤机、队列、工具执行、通行证、持久化、页面和命令行。不引入 Spring，不引入 Spring AI，不使用向量数据库，也不借用框架自带的检索器。三路召回和融合都在本模块内完成。

## 模块

这次新增 `jasagent-rag`。它的 Maven 依赖只指向 `jasagent-api`。父工程模块列表登记 `jasagent-rag`，否则仓库根的 `mvn test` 不会编译它。不在本模块里调用 DeepSeek 或 Gateway 的 HTTP。rerank 和向量都通过本模块的端口注入；测试传入假 reranker 和本机 hashing embedder。

## 改动地方

语料只读对应 scope 的 `rag/docs`，索引只写对应 scope 的 `rag/index/chunks.v1`。scope 为 `user` 时根目录是调用方给出的用户目录，为 `workspace` 时根目录是调用方给出的工作区目录。只收录扩展名为 `.md` 和 `.txt` 的普通文件，忽略大小写。其它扩展名不进索引。

切分先按 Markdown 的 ATX 标题行（一行开头 1 到 6 个 `#`，后接空白和标题文字）切开。第一个标题之前的文字自成一节，标题用文件名去掉扩展名。没有标题的纯文本也按这一节处理。再把每一节按 800 个 UTF-16 代码单元开窗，重叠 100，步长 700。长度不超过 800 的节只产生一个片段。片段的 `start` 和 `end` 是原文中的偏移，`content` 等于 `原文.substring(start, end)`。切分编号是 `guide.md#0` 这种相对路径加序号。写入索引和返回命中时，`chunkId` 再加 scope 前缀，形如 `user:guide.md#0`，避免两个目录的相同相对路径撞号。`docId` 和 `uri` 都是相对 `rag/docs` 的路径，分隔符为 `/`。`contentHash` 是片段 UTF-8 正文的 SHA-256，小写十六进制。

一次检索同时提交三路，执行器上可以重叠执行。词法是 BM25，`k1=1.2`，`b=0.75`，`IDF = ln(1 + (N - df + 0.5) / (df + 0.5))`，只对片段正文分词。向量是注入的 embedder 的余弦；默认 embedder 名为 hashing，维度 256，按 token 的 Java `hashCode` 做符号特征哈希，L2 归一化，不访问网络。标题路只对标题和 `uri` 分词，分数是查询里不同 token 命中的个数。分词把连续字母数字当作一个 token，每个汉字单独成 token，全部小写。某一路分数不大于 0 的片段不进入该路名次。名次从 1 开始，分数相同再按 `chunkId` 字典序升序。

融合使用 Reciprocal Rank Fusion，`k=60`。命中的融合分是它在各路 `1/(60+名次)` 的和，没进入的路不加分。融合分相同再按 `chunkId` 升序。只把融合分最高的 20 条交给 `Reranker`。假 reranker 或调用方返回与候选等长的 0 到 1 分数。最终顺序按 rerank 分从高到低，再按融合分，再按 `chunkId`。

质量校验按这个顺序：缺少完整溯源的命中丢弃；同一 `contentHash` 只留 rerank 分更高的一条，仍相同则留融合分更高的一条，再相同则留 `chunkId` 更小的一条；rerank 分低于 `jasagent.rag.min-rerank` 的丢弃，等于阈值的保留。默认阈值是 0.35，`RagConfig.defaults()` 读取该系统属性。一条都不剩时返回空列表和原因 `quality-empty`，结果里没有另外的段落文本。完整溯源必须同时具备 `sourceScope`（`user` 或 `workspace`）、`uri`、`docId`、`chunkId`、`start`、`end`、与正文一致的 `contentHash`、非空正文、词法、向量、标题三路的名次和分数、`fusionScore`、`rerankScore`。某一路没有命中时，名次为 0、分数为 0，这仍然算写明了该路。`rerankScore` 必须在 0 到 1 之间。

不传 scope 或传入空集合时，同时读用户目录和工作区已经建好的索引。传入 `user` 时不读工作区索引，传入 `workspace` 时不读用户目录索引。`required` 不是 true 时，空结果正常返回，失败标记为假。`required` 为 true 且结果为空时，失败标记为真，仍然不抛异常，也不编造段落。调用方传入 `topK` 时，质量校验之后只保留 rerank 顺序的前 `topK` 条。`rag.search` 工具名是 `rag.search`，它和检索入口调用同一个 `RagPipeline.search`。阻塞的文件读写放进虚拟线程执行器，对外方法返回 `CompletionStage`。

## 数据库

这次不使用数据库，也不建表。索引是 scope 目录里的文本文件。

## 中间件

这次不启动 HTTP，不连接消息中间件，也不连接模型服务。测试里的 reranker 是进程内假实现。

## 测试要求

1. Markdown 按标题切开，纯文本没有标题时整篇成一节，再按 800 字窗口和 100 字重叠切开，片段正文等于原文在 start 与 end 之间的子串。
2. 索引写在该 scope 的 `rag/index`，只收录 `rag/docs` 下的 md 和 txt，另一个 scope 不会因此出现索引文件。
3. 同一次查询里，词法、向量、标题三路的名次顺序彼此不同。
4. 融合分等于各路 `1/(60+名次)` 的和，融合后的顺序与每一路单独的顺序都不同。
5. 交给 reranker 的候选是融合分最高的 20 条。
6. 假 reranker 的分数可以改写最终名次，使结果不再按融合分排序。
7. 缺少溯源字段的命中被丢弃。
8. 同一 `contentHash` 上，没有完整溯源的高分命中先被丢掉，有溯源的低分命中仍然保留。
9. 同一 `contentHash` 的两条完整命中只留下 rerank 分更高的那条。
10. 默认阈值 0.35 时，低于 0.35 的命中被丢弃，等于 0.35 的命中保留。
11. 系统属性 `jasagent.rag.min-rerank` 设为 0.8 时，低于 0.8 的命中被丢弃，等于 0.8 的命中保留。
12. 全部命中被丢掉时，返回空列表和原因 `quality-empty`。
13. 不指定 scope 时，用户目录和工作区里匹配的文档都会返回。
14. 指定 `user` 时，结果里没有工作区文档。
15. 指定 `workspace` 时，结果里没有用户目录文档。
16. 留下来的命中带有 sourceScope、uri、docId、chunkId、start、end、contentHash、三路名次和分数、fusionScore、rerankScore，且 contentHash 等于正文的 SHA-256，正文能在原文件里按偏移截出。
17. `required` 不是 true 时，空结果不抛异常，失败标记为假。
18. `required` 为 true 且结果为空时，失败标记为真，命中列表仍为空。
19. 一次检索会把三路提交到执行器，三路任务可以同时处于执行中。
20. 假 reranker 参与打分时，检索过程不去选择网络代理。
21. 传入 `topK` 时只返回 rerank 顺序的前 `topK` 条。
22. `rag.search` 的工具名是 `rag.search`，返回与 `RagPipeline.search` 相同的命中。

## 还没定

无。窗口长度、重叠、BM25 参数、RRF 的 k、rerank 候选条数、哈希维度和阈值默认值按上一节实现。
