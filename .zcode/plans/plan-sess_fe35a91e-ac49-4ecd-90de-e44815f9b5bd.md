三件事按顺序做：生成能体现 RAG 能力的语料 → 评测集扩容 → 前端。

## 阶段 A：生成语料库

**内容设定**：虚构企业数据平台「星云 Nebula」，6 份文档放进 `src/test/resources/corpus/`

| 文件 | 内容 | 为什么需要它 |
| --- | --- | --- |
| `nebula-handbook.pdf` | 主手册 ~30 页：概述、架构、部署配置、数据接入、任务调度、监控告警、故障排查 | 长文档分块效果 |
| `nebula-error-codes.pdf` | 错误码速查表 | 表格密集 + 编号密集 |
| `nebula-ops-faq.pdf` | 运维 FAQ | 问答式短段落 |
| `nebula-oncall-guide.pdf` | 值班手册、升级路径、联系人 | 与错误码表构成跨文档问题 |
| `nebula-glossary.pdf` | 术语表中英对照 | 体现 bge-m3 多语言 |
| `nebula-changelog.pdf` | 变更记录 | 版本号密集 |

这份设定能支撑六类评测问题：表格查值、跨文档综合（「E-4012 该升级给谁」需要错误码表 + 值班手册）、专有编号、模糊问法、以及合理的「文档里没有」问题。

**顺带能验证一个从未被验证过的功能**：现有语料没有页眉页脚，解析日志一直是「识别为重复页眉页脚 0 种」——`PageTextCleaner` 的剔除逻辑从没被真实数据检验过。新语料每页带文档名 + 页码，能真正触发它。

**生成方式**：`tools/corpus/generate_corpus.py`（Python 3.11 + fpdf2 2.8.7 + `C:\Windows\Fonts\simhei.ttf`，全部已在机，零安装零网络）。内容以数据结构定义、参数化。

**PDF 提交进仓库**，不每次重新生成——评测集的期望答案绑定在具体内容上，重新生成会让内容漂移、评测失效。现有 `test.pdf` 保留不动（解析冒烟测试还在用）。

## 阶段 B：评测集扩容

**结构要改**：语料变成多文档后，`expectedChunkIndexes` 不够用，要带文档维度：
```json
{"question":"...", "type":"MULTI_HOP",
 "expected":[{"document":"nebula-error-codes.pdf","chunkIndex":3}, ...]}
```
`type` 用于分组统计，能看出「关键词在 TERM 类上强、向量在 PARAPHRASE 类上强」——这才叫评测集设计，而不只是一个数字。

**六类问题，目标约 60 题**（每类约 10 题）：

| 类型 | 说明 | 设计预期 |
| --- | --- | --- |
| `FACT` | 单点事实 | 两者都该答对 |
| `TABLE` | 表格查值 | 向量偏弱（表格文本语义差） |
| `MULTI_HOP` | 跨文档 / 跨章节综合 | 考验召回覆盖 |
| `TERM` | 错误码 / 版本号 / 接口名 | **关键词强项** |
| `PARAPHRASE` | 不含原文关键词的模糊问法 | **向量强项** |
| `NEGATIVE` | 文档里没有，应拒答 | 单独统计拒答准确率 |

**期望答案继续用 SQL 从真实分块反查**，不凭印象写（沿用上一轮建立的方法）。

**测试改造含一个必要的性能设计**：**每个 (问题, 模式) 只检索一次最大 K（10），在内存里截断计算各档 K 的指标**。若每个 (问题, K, 模式) 各查一次，60 题 × 3 模式 × 4 档 K = 720 次检索，每次都要调一次 embedding API；截断法只需 60 × 3 = 180 次。排序在 K 增大时是包含关系，所以截断等价。

K 取 1/2/3/5/10，全部远小于语料分块总数（~70），避免上一轮那个「K ≥ 分块总数导致 Recall 恒为 1」的假结果。报告按 **模式 × K × 问题类型** 三个维度出。断言阈值**先跑出数据再定**，不预先写死。

成本：只用 embedding、不调聊天模型，额度消耗很低。

## 阶段 C：前端（Vue 3 + Vite + Element Plus）

环境已就绪，不用装任何东西。

**目录**（`frontend/` 子目录，AGENTS.md 已定）
```
frontend/
├── package.json / vite.config.js（proxy /api → localhost:8080）/ index.html
└── src/
    ├── main.js / App.vue / router/index.js
    ├── api/request.js              axios 实例 + 拦截器
    ├── api/{knowledgeBase,document,retrieval,chat}.js
    └── views/
        ├── KnowledgeBaseList.vue
        ├── DocumentList.vue
        ├── RetrievalPlayground.vue
        └── ChatView.vue
```
不引状态管理库：页面间共享的只有知识库 id，走路由参数即可，少一个依赖也少一个要解释的东西。

**拦截器的四个约定**（核对自真实接口契约）
1. 剥信封：后端返回 `{code,message,data}`，拦截器把 `data` 抽出返回，页面直接用
2. 成功状态码集合是 `{200, 201, 204}`，不是只有 200
3. 204 无 body，跳过 JSON 解析
4. 错误提示取 `error.response.data.message`，并区分 409（重复/冲突）与 503（依赖不可用）

**四个页面**
1. **知识库列表**：表格 + 新建/编辑对话框 + 删除（204），状态标签
2. **文档列表**：`el-upload`（字段名 `file`、只收 .pdf、50MB）、parseStatus 标签、操作列「解析 / 向量化 / 查看分块 / 删除」，分块用抽屉展示（序号、页码、token 数、内容）。解析与向量化是同步接口，要有 loading 和「大文件会慢」的提示
3. **检索调试页**：问题 + 模式单选（HYBRID/VECTOR/KEYWORD）+ topK，结果列出 `score` / `vectorScore` / `keywordScore` / 页码 / 原文。**这是「体现能力」的核心页面**，一般 RAG 项目没有
4. **问答页**：前端生成 `conversationId`，多轮气泡，回答下方来源卡片（文件名 + 第几页 + 片段 + `cited` 标记），可展开看全部召回来源；「新建会话」换 id

**必须先补的一个后端缺口**：`DocumentResponse` 没有 `errorMessage` 和 `parsedAt`，所以文档列表里 `FAILED` 的行只能显示「失败」，看不到原因（比如「未从 PDF 中提取到任何文本」）。做前端一定会撞上，要补这两个字段并同步更新契约测试。

**其他要改的**：`.gitignore` 补 `node_modules/`、`dist/`、`.vite/`、`*.local`（AGENTS.md 要求了但文件里漏了）。

**验证方式**：`npm run build` 通过 + **我自己起环境用浏览器实测走一遍并截图**（知识库 → 上传 → 解析 → 向量化 → 检索三模式对比 → 多轮问答看引用），不是只保证能编译。

## 阶段 D：收尾

- 修两处我自己写的文档 bug：`CitationResponse.score` 的 javadoc 写着「余弦相似度」，但问答链路固定走 HYBRID，实际是 RRF 分数；`KnowledgeBaseController` 里「11 个接口里唯一例外」已过时（实际 15 个接口、2 个 204）
- **AGENTS.md 修一条错的约定**：它写着「响应体本身就是业务数据，直接读 `res.data`，不要写成 `res.data.data`」——这是 `Result` 落地之前的旧约定，按现状是反的。已核实后端确实返回 `{code,message,data}` 信封。改成「拦截器剥信封」的写法并与实现对齐
- AGENTS.md 补：前端技术栈与目录、语料与评测集的位置和跑法、`DocumentResponse` 新增字段
- plan：前端记入「阶段之外的横向改动」（计划里原本没有前端阶段），并更新第 10 节
- 环境按你的要求收尾：停 Docker 容器 + `wsl --shutdown`

## 交付顺序与验证

A → B 是连续的（语料生成完才能定评测集），C 相对独立。每步都给你可核对的结果：A 给出语料分块数与页眉页脚剔除的验证数据，B 给出三模式 × 多档 K × 问题类型的完整指标表，C 给出构建通过 + 实测截图。

## 明确不做的事

- 不引入 Elasticsearch / Rerank / 查询改写（上一轮已定，需要更大的评测集才有意义）
- 不引 pinia / TypeScript（这个规模用不上）
- 不改动现有 `test.pdf`
- 不改后端业务逻辑（除 `DocumentResponse` 补两个字段这一处接口缺口）
