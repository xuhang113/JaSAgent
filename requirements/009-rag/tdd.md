# 009 检索的测试用例

## 1. chunksByHeadingThenWindow

要求：Markdown 按标题切开，纯文本没有标题时整篇成一节，再按 800 字窗口和 100 字重叠切开，片段正文等于原文在 start 与 end 之间的子串。

看到的结果：两个标题得到两段，标题分别是 Alpha 和 Beta，前一段不含后一个标题。900 个字符的正文加标题产生两段，第一段是原文 `[0,800)`，第二段从 700 开始直到文末，重叠 100 个字符。`plain.txt` 只有一段，标题是 `plain`，正文等于全文。

## 2. indexStoresMdAndTxtPerScope

要求：索引写在该 scope 的 `rag/index`，只收录 `rag/docs` 下的 md 和 txt，另一个 scope 不会因此出现索引文件。

看到的结果：用户目录出现 `rag/index/chunks.v1`，工作区没有该文件。查询能命中 md 和 txt 里的词，命不中 pdf 里的词，pdf 查询的原因是 `quality-empty`。

## 3. threePathsRankDifferently

要求：同一次查询里，词法、向量、标题三路的名次顺序彼此不同。

看到的结果：`notes`、`misc`、`wide`、`alpha-beta` 四篇文档一次检索后，三条名次列表两两不相等。

## 4. fusionUsesRrfK60AndChangesRanks

要求：融合分等于各路 `1/(60+名次)` 的和，融合后的顺序与每一路单独的顺序都不同。

看到的结果：每条命中的 `fusionScore` 与按名次重算的和一致，误差不超过 `1e-12`。假 reranker 把所有候选打成相同分数后，结果顺序与词法顺序、向量顺序、标题顺序都不相同。

## 5. rerankReceivesTopFusion20

要求：交给 reranker 的候选是融合分最高的 20 条。

看到的结果：25 篇都会命中的文档送进假 reranker 的只有 20 条，并且就是融合顺序的前 20 个 `chunkId`。

## 6. rerankRewritesOrder

要求：假 reranker 的分数可以改写最终名次，使结果不再按融合分排序。

看到的结果：融合时靠后的 `alpha-beta` 被打成 0.99 后排到第一，最终 `chunkId` 顺序与融合顺序不同。

## 7. missingProvenanceDropped

要求：缺少溯源字段的命中被丢弃。

看到的结果：`uri` 为空的高分命中不在结果里，另一条溯源完整且达到阈值的命中仍然在。

## 8. incompleteHitDoesNotEraseSameHash

要求：同一 `contentHash` 上，没有完整溯源的高分命中先被丢掉，有溯源的低分命中仍然保留。

看到的结果：结果里只剩有溯源的那条，chunkId 是完整命中的 id。

## 9. sameContentHashKeepsHigherRerank

要求：同一 `contentHash` 的两条完整命中只留下 rerank 分更高的那条。

看到的结果：结果只有一条，rerank 分是 0.8 那条的 chunkId。

## 10. defaultMinRerankKeepsThreshold

要求：默认阈值 0.35 时，低于 0.35 的命中被丢弃，等于 0.35 的命中保留。

看到的结果：`RagConfig.defaults()` 的阈值是 0.35。0.35 的命中留下，0.349 的命中不在。

## 11. configuredMinRerankApplies

要求：系统属性 `jasagent.rag.min-rerank` 设为 0.8 时，低于 0.8 的命中被丢弃，等于 0.8 的命中保留。

看到的结果：默认配置读到 0.8。0.8 的命中留下，0.79 的命中不在。属性在用例结束时恢复。

## 12. allDroppedReturnsQualityEmpty

要求：全部命中被丢掉时，返回空列表和原因 `quality-empty`。

看到的结果：命中列表为空，原因等于 `quality-empty`。

## 13. unspecifiedScopeSearchesBoth

要求：不指定 scope 时，用户目录和工作区里匹配的文档都会返回。

看到的结果：同一次查询的 `sourceScope` 集合同时含有 `user` 和 `workspace`。

## 14. userScopeSkipsWorkspace

要求：指定 `user` 时，结果里没有工作区文档。

看到的结果：每条命中的 `sourceScope` 都是 `user`，正文里没有只写在工作区的标记。

## 15. workspaceScopeSkipsUser

要求：指定 `workspace` 时，结果里没有用户目录文档。

看到的结果：每条命中的 `sourceScope` 都是 `workspace`，正文里没有只写在用户目录的标记。

## 16. hitCarriesProvenance

要求：留下来的命中带有 sourceScope、uri、docId、chunkId、start、end、contentHash、三路名次和分数、fusionScore、rerankScore，且 contentHash 等于正文的 SHA-256，正文能在原文件里按偏移截出。

看到的结果：这些字段都有值，哈希与独立 SHA-256 一致，原文件子串等于命中正文。

## 17. requiredFalseDoesNotFail

要求：`required` 不是 true 时，空结果不抛异常，失败标记为假。

看到的结果：`search` 正常返回，`failed` 为假，原因是 `quality-empty`，命中为空。

## 18. requiredTrueMarksFailedWithoutText

要求：`required` 为 true 且结果为空时，失败标记为真，命中列表仍为空。

看到的结果：不抛异常，`failed` 为真，命中为空，原因是 `quality-empty`。

## 19. threePathsOverlapInTime

要求：一次检索会把三路提交到执行器，三路任务可以同时处于执行中。

看到的结果：检索使用的执行器收到 3 次提交。三路慢任务在三线程池上的最大并发数是 3，耗时小于 500 毫秒。

## 20. searchDoesNotSelectNetworkProxy

要求：假 reranker 参与打分时，检索过程不去选择网络代理。

看到的结果：代理选择器在被调用时会失败，但这次检索仍然返回命中，假 reranker 的调用次数为 1。

## 21. topKKeepsHighestRerank

要求：传入 `topK` 时只返回 rerank 顺序的前 `topK` 条。

看到的结果：`topK` 为 1 时只有 rerank 分最高的那条。

## 22. ragSearchToolSharesPipeline

要求：`rag.search` 的工具名是 `rag.search`，返回与 `RagPipeline.search` 相同的命中。

看到的结果：工具名等于 `rag.search`，工具返回的 `chunkId` 列表与直接检索相同。
