# 009 检索的测试用例

## 1. fusionChangesLexicalOrder

要求：三路名次不同时，融合后的第一名不是 lexical 的第一名。

看到的结果：融合列表的第一个 chunkId 是 `C`，而 lexical 的第一个是 `A`。

## 2. rrfScoreUsesK60

要求：k 为 60 时，一条同时出现在三路的融合分数等于三路 `1/(60+rank)` 的和。

看到的结果：`A` 的 fusionScore 等于 `1/61 + 1/63 + 1/62`。

## 3. onlyTopTwentyGoToRerank

要求：融合结果超过 20 条时，交给 rerank 的只有 20 条。

看到的结果：rerank 收到的列表长度是 20。

## 4. rerankRewritesOrder

要求：rerank 分可以改写融合后的名次。

看到的结果：最终第一名是融合时排在后面的那条。

## 5. missingProvenanceDropped

要求：缺少 uri 的命中被丢掉。

看到的结果：结果里没有缺 uri 的 chunkId。

## 6. lowRerankDropped

要求：rerank 分低于 0.35 的命中被丢掉。

看到的结果：结果里没有 rerank 分为 0.1 的命中。

## 7. duplicateHashCollapsed

要求：同一 contentHash 只留下一条。

看到的结果：该 contentHash 在结果里只出现一次。

## 8. emptyWhenAllDropped

要求：全部被丢掉时，结果为空且原因是 `quality-empty`。

看到的结果：命中列表为空，原因等于 `quality-empty`。

## 9. userScopeSkipsWorkspaceDoc

要求：scope 为 user 时，不返回只放在工作区的文档。

看到的结果：命中的 sourceScope 都是 `user`，正文不包含工作区独有标记。

## 10. workspaceScopeSkipsUserDoc

要求：scope 为 workspace 时，不返回只放在用户目录的文档。

看到的结果：命中的 sourceScope 都是 `workspace`，正文不包含用户目录独有标记。

## 11. chunkByHeadingThenWindow

要求：带标题的正文会按标题切开，超长段落再按 800 字重叠 100 字切开。

看到的结果：两个标题各自至少一块；超长标题下的相邻块重叠 100 个字符，单块长度不超过 800。

## 12. markdownAndTextAreIndexed

要求：md 和 txt 都能被索引并检索到。

看到的结果：同一次检索能同时找到 md 标记和 txt 标记。
