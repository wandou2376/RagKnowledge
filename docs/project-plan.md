# 企业级 RAG 知识库系统项目计划

## 1. 项目目标

构建一个基于 Java 和 Spring Boot 的企业级 RAG（Retrieval-Augmented Generation）知识库系统，将企业文档转换为可检索知识，并基于检索结果生成带来源引用的回答。

核心目标：

- 支持 PDF、Word、Excel 等常见文档的上传和解析。
- 对文档进行清洗、自动分块和语义去重。
- 使用向量检索召回语义相关内容。
- 后续加入关键词检索和 Rerank 重排序，提高召回准确率。
- 回答中标注来源文档、页码和原文片段。
- 使用 Redis 管理多轮对话上下文。
- 具备知识库隔离、权限控制、异步任务和运行监控等企业化能力。

## 2. 当前技术栈

| 领域 | 技术选择 |
| --- | --- |
| 开发语言 | Java 21 |
| 后端框架 | Spring Boot 3.5.x |
| AI 编排 | Spring AI 1.0.0 |
| 聊天模型 | DeepSeek `deepseek-flash` |
| 模型接入方式 | OpenAI 兼容 API |
| 业务数据库 | PostgreSQL 17 |
| 向量检索 | pgvector 0.8.x |
| ORM | Spring Data JPA / Hibernate |
| 数据库版本管理 | Flyway |
| 缓存和会话记忆 | Redis |
| Embedding 模型 | 硅基流动 `BAAI/bge-m3`，1024 维，走 OpenAI 兼容 API |
| 关键词检索 | PostgreSQL `pg_trgm`（默认分词器不支持中文，实测 tsvector 不可用） |
| 文件存储 | 第一版本地文件，后续 MinIO |
| 文档解析 | PDFBox 3.0.8（Tika / POI 尚未引入） |
| 构建工具 | Maven |
| 前端 | Vue 3 + Vite + Element Plus + vue-router + axios，代码在 `frontend/` |
| 本地环境 | Docker Desktop + WSL2 |
| 测试工具 | JUnit 5、Mockito、MockMvc |

聊天模型只负责生成回答和处理图片理解，不负责文档向量化。**DeepSeek 官方不提供 Embedding 接口**（`api.deepseek.com/embeddings` 返回 404），所以向量化必须外挂一家。阶段 4 已选定：**硅基流动 `BAAI/bge-m3`，1024 维，走 OpenAI 兼容接口**，免费、中国大陆直连、中文效果均衡。

## 3. 16GB 内存运行策略

聊天模型使用 DeepSeek 云端 API，本机不运行大语言模型。4060 显卡只在后期尝试本地 Embedding、Rerank 或 OCR 时使用。

### 常用运行组合

| 场景 | 启动服务 |
| --- | --- |
| 只编写代码 | IDEA，关闭 Docker Desktop 和 WSL2 |
| 开发知识库和文档 CRUD | IDEA、Spring Boot、PostgreSQL + pgvector |
| 开发多轮对话 | 上述服务 + Redis |
| 运行模型测试 | 上述服务 + DeepSeek API |
| 检索效果调试 | PostgreSQL + Spring Boot，不启动 ES/Milvus |
| 本地模型实验 | 停止无关 Docker 服务，必要时暂时关闭 IDEA |

Docker Desktop / WSL2 建议限制在约 5~6GB 内存。需要数据库时启动：

```powershell
docker compose up -d
```

只编写代码时停止：

```powershell
docker compose stop
wsl --shutdown
```

不要使用 `docker compose down -v`，否则会删除 PostgreSQL 数据卷。

## 4. 系统总体流程

```text
上传文档
  -> 保存原文件
  -> 解析文本并保留页码
  -> 清洗、分块、语义去重
  -> 生成 Embedding
  -> 写入 PGVector
  -> 用户提问
  -> 问题改写（后续）
  -> 向量检索
  -> 关键词检索（后续）
  -> 结果融合和 Rerank（后续）
  -> 拼接上下文调用 DeepSeek
  -> 返回答案和来源引用
  -> Redis 保存对话上下文
```

每个 Chunk 必须保留以下元数据，保证后续可以准确引用：

```text
documentId
knowledgeBaseId
pageNumberStart
pageNumberEnd
sectionTitle
contentHash
tokenCount
```

## 5. 开发阶段和验收标准

### 阶段 0：项目基座（已完成）

- 创建 Spring Boot 单体项目。
- 配置 Java 21、Spring AI、JPA、Redis、PostgreSQL、Flyway。
- 使用 Docker 运行 PostgreSQL 17 + pgvector，映射到本机 `5433` 端口。
- 配置 DeepSeek OpenAI 兼容接口。
- 完成第一版数据库迁移。

当前数据库迁移表：

```text
rag.knowledge_base
rag.document
rag.document_chunk
rag.ingestion_task
```

### 阶段 1：知识库 CRUD（已完成）

实现知识库的创建、查询、更新和删除：

```text
POST   /api/knowledge-bases
GET    /api/knowledge-bases
GET    /api/knowledge-bases/{id}
PUT    /api/knowledge-bases/{id}
DELETE /api/knowledge-bases/{id}
```

验收标准：

- 创建知识库后状态默认为 `ACTIVE`。
- 查询不存在的 ID 返回 `404`。
- 非法参数返回 `400`。
- 删除返回 `204 No Content`。
- Entity、DTO、Service、Repository、Controller 分层清晰。

### 阶段 2：文档上传和元数据管理（已完成）

第一版只支持 PDF，文件保存到项目根目录的 `storage/documents`，数据库只保存元数据。

建议接口：

```text
POST   /api/knowledge-bases/{knowledgeBaseId}/documents
GET    /api/knowledge-bases/{knowledgeBaseId}/documents
GET    /api/documents/{documentId}
DELETE /api/documents/{documentId}
```

处理流程：

```text
接收 MultipartFile
  -> 校验文件类型和大小
  -> 计算 SHA-256
  -> 生成安全的 storageKey
  -> 保存文件
  -> 保存 document 记录
  -> parseStatus = PENDING
```

验收标准：

- 能上传 PDF 并生成文档记录。
- 文件名和实际存储名分离。
- 同一知识库重复上传相同文件时被拒绝。
- 不存在的知识库返回 `404`。
- 非 PDF 文件返回 `400`。
- 删除文档时清理数据库记录和物理文件。

以上标准已通过真实 HTTP 请求逐条验证，并核对了 `rag.document` 与 `storage/documents`。

### 阶段 3：PDF 解析和 Chunking（已完成，核心流程）

使用 PDFBox 逐页提取文本，保存页码，再按段落和 Token 长度分块。

处理流程：

```text
PDF
  -> 按页提取文本
  -> 去除多余空格和重复页眉页脚
  -> 按标题、段落、Token 数分块
  -> 计算 contentHash
  -> 保存 document_chunk
```

第一版建议使用固定长度分块，例如 500~800 Token，并保留少量 overlap。后续再根据标题层级、表格和代码块进行结构化分块。

扫描版 PDF 或文本提取为空的页面，再渲染成图片交给 DeepSeek 视觉模型处理。

#### 本阶段已实现

接口：

```text
POST   /api/documents/{documentId}/parse     解析并写入分块，同步执行
GET    /api/documents/{documentId}/chunks    查看该文档的分块结果
```

- 按页提取：PDFBox 3.0.8 逐页提取，页码取自物理页序（从 1 开始），开启按坐标排序保证阅读顺序。
- 文本清洗：压缩连续空白；把数字归一化后统计，出现在 60% 以上页面顶部或底部的行判定为重复页眉页脚并剔除。页数少于 3 页时跳过该判定，避免样本太小误杀正文。
- 分块：以「行」为最小单位打包，目标 600 token、重叠 80 token，尾块不足 120 token 并入上一块；单行本身就超长时按 token 二分硬切。参数在 `app.chunking` 下可调。
- 元数据：每个分块保存页码区间、SHA-256 内容哈希、token 数；同一知识库内按 `content_hash` 去重，重复内容跳过不写入。
- 幂等：重复解析同一文档会先清空旧分块再写入，不会产生重复数据。

验证结果：一份 6 页真实 PDF 得到 5 个分块，token 数为 603 / 608 / 617 / 612 / 608，页码区间为 1-1 / 1-3 / 3-4 / 4-5 / 5-6，与 `rag.document_chunk` 记录一致；重复解析后仍为 5 块。

#### 留待后续

- **结构化分块未实现**：按标题层级、表格、代码块切分属于后续迭代，`section_title` 列已保留但当前恒为 `null`。
- **扫描版 PDF 兜底未实现**：当前行为是提取不到任何文本时返回 `422 DOCUMENT_PARSING_FAILED`，并把文档置为 `FAILED` 且写入 `error_message`。渲染页面图片交给视觉模型识别的能力尚未开发。
- **解析是同步接口**：大文件会阻塞请求。异步任务、失败重试和 `ingestion_task` 表的实际使用按计划放在阶段 8。

### 阶段 4：Embedding 和 PGVector 检索（已完成）

选择单独的 Embedding 模型后：

```text
Chunk -> Embedding -> vector_store
问题 -> Embedding -> Top-K 相似度检索
```

#### 选型决定

| 项 | 决定 | 理由 |
| --- | --- | --- |
| 模型 | 硅基流动 `BAAI/bge-m3` | DeepSeek 不提供 Embedding 接口，必须外挂一家；这个免费、中国大陆直连、中文效果均衡 |
| 维度 | 1024 | 模型输出维度，对应 `embedding vector(1024)` |
| 相似度算法 | 余弦距离（`vector_cosine_ops`） | bge-m3 是归一化向量，余弦是这类模型的标准选择 |
| Top-K | 默认 5，可传参，上限 50 | |
| 知识库过滤 | `WHERE knowledge_base_id = ?` | 复用已有的 `idx_document_chunk_knowledge_base_id` |

**向量存储没有用计划原先写的 `vector_store` 独立表，而是扩展现有的 `document_chunk`**：正文只存一份不重复，页码、文档 ID、知识库 ID 这些字段现成就在，阶段 5 要的「来源文档 + 页码 + 原文片段」一个 join 就能拿到。pom 里原先引的 `spring-ai-starter-vector-store-pgvector` 因此移除了。

#### 本阶段已实现

接口：

```text
POST   /api/documents/{documentId}/embed              向量化该文档下待处理的分块
POST   /api/knowledge-bases/{knowledgeBaseId}/search  语义检索，返回 Top-K 分块
```

- 迁移 `V5__add_chunk_embedding_column.sql`：`document_chunk` 增加 `embedding vector(1024)`、`embedding_model`、`embedded_at`，并建 HNSW + 余弦距离索引。
- 向量化按 `batch-size` 分批调用外部服务，调用在事务外、只在写库时开短事务；重复调用幂等，已由当前模型处理过的分块会跳过；`embedding_model` 与配置不一致时会自动重算，支持换模型。
- `embedding` 列不做 JPA 映射（Hibernate 没有内置 vector 类型），写入和检索都走原生 SQL，中间把 `float[]` 转成 pgvector 字面量并校验维度与合法值。
- 检索返回分块 ID、文档 ID、来源文件名、页码区间、原文和余弦相似度。
- 未配置 API Key、外部服务报错、返回维度不符等情况统一抛 `EmbeddingFailedException`，HTTP 层映射成 **503**（外部依赖问题，与 500 服务端故障区分开）。
- 聊天模型名从已退役的 `deepseek-v4-flash-vision-exp` 更新为 `deepseek-flash`。

#### 验证情况

全部通过，包括完整的「上传 → 解析 → 向量化 → 检索」链路：

- 全量单元测试 86 个（本阶段新增 27 个），覆盖向量字面量转换、向量化编排、检索服务，以及两个新接口的响应契约。
- 数据库侧集成测试 3 个（`ChunkVectorStoreIT`，需 `RUN_EXTERNAL_TESTS=true`）：在真实 pgvector 上写入向量、验证按余弦相似度排序、验证知识库隔离、验证未向量化的分块被排除。原生 SQL 写入与接口投影映射这两处风险点由此覆盖。
- 真实启动验证：Flyway V5 干净应用，`embedding` 列与 HNSW 索引建对，应用正常启动（`ddl-auto=validate` 通过），Embedding 客户端按配置装配成功。
- **真实调用硅基流动完成向量化**：6 页 PDF 切成 5 个分块，4 页的文本全部向量化成功，数据库里每行 `vector_dims(embedding) = 1024`、`embedding_model = BAAI/bge-m3`、`embedded_at` 非空，整批耗时 1.1 秒。
- **幂等**：同一个文档再调一次 `/embed`，返回 `embeddedCount=0`、`alreadyEmbeddedCount=5`，没有重复消耗额度。
- **换模型自动重算**：把库里的 `embedding_model` 改成 `old-model-v1` 模拟换模型，再调 `/embed` 返回 `embeddedCount=5`，5 条全部重新向量化并写回新模型名。
- **语义检索质量**：英文提问 `artificial intelligence and pattern recognition journals` 跨语言召回了中文文档中含「中国人工智能学会」「中国图象图形学学会」的段落，相似度 0.5342；中文提问「机器人相关的期刊有哪些」命中含「机器人 中国科学院沈阳自动化研究所」的段落，相似度 0.6205。两次都按相似度单调递减，结果带文档 ID、来源文件名、页码区间和原文。
- **知识库隔离**：在不存在的知识库检索返回 404，在空知识库检索返回空数组而不是报错。
- **参数校验**：空 query 返回 400，消息为中文。

一处如实说明：表里只有 5 行数据时，PostgreSQL 的执行计划选择 Sort 而不是 HNSW 索引，所以**索引在这个数据量下没有被用到，也无法演示**。索引本身已正确创建（`USING hnsw (embedding vector_cosine_ops)`），数据量上去之后才会生效。

#### 留待后续

- 检索没有相似度阈值过滤，只取 Top-K，低质量召回会一并返回。阈值调优放到阶段 7。
- 没有关键词检索、结果融合和 Rerank，按计划属于阶段 7。
- 检索没有缓存，按计划属于阶段 6（Redis）。
- 换 Embedding 模型需要新建迁移改 `embedding vector(N)` 的维度——Flyway 迁移是静态 SQL，无法参数化。

### 阶段 5：RAG 问答和引用溯源（已完成）

```text
用户问题
  -> 向量检索
  -> 获取 Top-K Chunk
  -> 组装系统 Prompt 和上下文
  -> 调用 DeepSeek
  -> 返回答案、来源文档、页码、引用片段
```

回答必须要求模型：

- 只依据提供的上下文回答。
- 上下文不足时明确说不知道。
- 不编造来源。
- 使用结构化引用编号。

最终验收目标：

> 上传一份 PDF，提问后能得到答案，并显示来源文件名、第几页和对应原文片段。

#### 本阶段已实现

接口：

```text
POST   /api/knowledge-bases/{knowledgeBaseId}/chat   知识库问答，返回答案与来源引用
```

- 提示词组装：检索到的分块按顺序编号为 `[1]`、`[2]`……，每段标注来源文件名与页码；系统提示词固定五条约束（只依据参考资料、不足时拒答、按方括号编号引用、不许编造编号、用中文简洁作答）。
- 引用溯源：从回答里抽取 `[n]` 标记，映射回具体分块。**超出本次参考资料条数的编号视为模型编造，直接丢弃并记 warn**——这是「不编造来源」这条要求的技术兜底，不只靠提示词。
- 返回结构里每条来源都带 `cited` 标记，区分「召回了但没被引用」和「真正被引用」，前端可自行决定展示策略。
- 一次都没召回到时**不调用模型**，直接返回固定话术。既省一次调用，也避免模型脱离上下文自由发挥。
- 返回 token 消耗（prompt / completion / total），为后续统计成本做准备。
- 调用模型是阻塞的外部请求，编排层不做任何数据库写入，因此不涉及事务。
- 模型调用失败抛 `AnswerGenerationException`，HTTP 层映射成 **503**，与 500 服务端故障区分开。
- 模型接入沿用 `spring.ai.openai.*`（DeepSeek `deepseek-flash`），通过一层 `AnswerGenerator` 接口隔离，编排逻辑不直接依赖 `ChatClient` 的流式调用链，便于测试。

#### 验证情况

全部通过，含真实调用 DeepSeek 的端到端验证：

- 全量单元测试 114 个（本阶段新增 28 个）：提示词组装（编号、页码格式、页码缺失时的降级）、引用编号抽取（去重、排序、超范围丢弃、非数字方括号忽略）、编排逻辑（引用标记映射、无召回时跳过模型、编造编号丢弃、topK 透传、异常传播），以及问答接口的响应契约。
- **真实问答验证**：上传 6 页 PDF、解析出 5 个分块、向量化后提问「文档里有哪些中文期刊？请举几个例子」，模型基于文档内容列出了 T1/T2/T3 三类中文期刊并举出具体刊名，引用标记 `[1][2][3]`，sources 数组把每个编号映射回了分块 ID、来源文件名、页码区间和原文。耗时 6.3 秒，token 消耗 prompt=1390 / completion=1249 / total=2639。
- **拒答验证**：问「文档里写的公司年假政策是多少天」返回「根据现有资料无法回答该问题。」且 3 条召回全部标记为未引用；问「用 Python 写快速排序」同样拒答，并主动说明参考资料只包含期刊目录、不含编程内容。模型没有编造。
- **引用精确性验证**：问「T3 类里那个叫『机器人』的期刊主办单位是谁」，回答准确，只引用了 `[3]`，另外两条召回正确标记为 `cited: false`。

#### 留待后续

- **没有多轮对话记忆**：每次提问都是独立的，模型看不到上一轮。按计划属于阶段 6（Redis）。
- 回答没有流式输出，一次性返回；前端做打字机效果需要 SSE，属于后续优化。
- 没有检索结果缓存，重复提问会重复调用模型。
- 提示词没有版本管理，改动后无法回溯是哪一版产出的回答。

### 阶段 6：Redis 多轮对话记忆（已完成）

Redis 保存：

```text
conversationId
最近 5~10 轮消息
过期时间 TTL
```

不要无限保存历史上下文。必要时对旧消息进行摘要，避免上下文过长和模型成本持续增加。

#### 本阶段已实现

接口：

```text
POST   /api/knowledge-bases/{knowledgeBaseId}/chat                     提问，请求可带 conversationId
GET    /api/knowledge-bases/{knowledgeBaseId}/conversations/{id}       查看某个会话已记住的历史
```

- **会话标识由调用方生成**（`conversationId`，限字母数字下划线连字符，最长 64）。不传就是单轮问答，不读写任何历史，阶段 5 的行为完全不变。
- Redis 结构：key 为 `rag:chat:{知识库ID}:{会话ID}`，value 是消息 JSON 的 List；`RPUSH` 追加、`LTRIM` 只留最新的 `max-turns × 2` 条，天然是一个有上限的滑动窗口。会话按知识库隔离，同一个 `conversationId` 在不同库里互不干扰。
- 配置在 `app.chat.memory` 下：`max-turns: 5`（即最多 10 条消息）、`ttl: 7d`，每次追加消息都会滑动续期。
- 历史以**真实的 user / assistant 角色消息**发出去，而不是拼进一段文本里——模型对角色消息的处理比一大段文本可靠。消息列表是自己拼的（system → 历史 → 本次提问），顺序确定，不依赖框架的拼装细节。
- 系统提示词新增第 6 条：可以参考历史理解追问，但事实依据仍然只能来自本次的【参考资料】。
- 模型调用成功后才写历史，调用失败的一轮不会被记住，避免下一轮看到一条没有回答的提问。召回到空结果而被拒答的那一轮同样记入历史。
- 单条无法解析的历史记录会被跳过，不影响整段历史（应对以后改存储格式留下的旧数据）。
- **Redis 不可用时降级为「没有历史」而不是让问答失败**：记忆是增强能力，不该因为它挂掉就让整个接口不可用。降级会记 warn。

#### 验证情况

全部通过，含真实 Redis 与真实模型的端到端验证：

- 全量单元测试 127 个（本阶段新增 13 个）：提示词携带历史、编排层的读历史与写历史、不传 `conversationId` 时不碰记忆、拒答轮次也记入历史、Redis 读回空时仍能正常回答、历史接口的 404 与空数组。controller 层新增 4 个契约测试（`conversationId` 回显、非法字符 400、历史接口、未知知识库 404）。
- **Redis 集成测试 7 个**（`RedisConversationMemoryIT`，需 `RUN_EXTERNAL_TESTS=true`）：追加与读取顺序、裁剪到上限、TTL 生效、按知识库隔离、按会话隔离、跳过无法解析的记录。这些行为用 mock 验证不了——mock 只能证明调用了什么方法，证明不了 Redis 里最终留下什么。
- **真实多轮验证**：3 轮连续提问后，Redis 里是 6 条消息、TTL 604792 秒（≈7 天，与配置一致）、key 为 `rag:chat:{知识库}:{会话}`。问「我上一个问题问的是什么？请原样复述」，**带会话时准确复述出上一轮原文**，不带会话时回答「根据现有资料无法回答该问题」——这一对照证明记忆的读写与「不传即无记忆」三条都成立。
- **历史长度上限验证**：往会话里灌到 22 条消息，再提一次问，条数被裁到 **10 条**（正好是 `max-turns 5 × 2`），最早一条变成被保留下来的第 5 轮，符合预期。
- **降级验证**：停掉 Redis 后提问，仍能正常回答（从参考资料答出 T1 类中文期刊），日志出现读、写两条降级告警。

#### 排查出并修掉的一个问题

Lettuce 的**默认命令超时是 60 秒**。第一次测降级时发现，Redis 挂掉后读历史要等 60 秒才降级、写历史再等 60 秒，一次提问要卡两分钟左右——降级逻辑虽然生效，但慢到请求已经不可用。已在配置里显式设置 `timeout: 2s` / `connect-timeout: 1s`，修复后同样场景耗时从约 66 秒降到 **2 秒**。

#### 留待后续

- **没有对历史做摘要**。当前策略是直接截断到最近 5 轮，更早的对话直接丢失。计划里提到的「必要时对旧消息进行摘要」尚未实现，属于成本与效果需要权衡时的优化项。
- 会话没有归属校验：任何人都可以拿着别人的 `conversationId` 读到那段历史。等阶段 8 有了登录认证再按用户隔离。
- 回答没有流式输出，前端做打字机效果需要 SSE。
- 检索结果和回答都没有缓存，重复提问会重复调用模型。

### 阶段 7：检索优化（已完成，核心部分）

按以下顺序逐步增加：

1. 问题改写和查询扩展。
2. PostgreSQL 全文检索作为关键词检索的低成本版本。
3. Elasticsearch 关键词检索。
4. 向量召回和关键词召回结果融合。
5. Rerank 重排序。
6. 检索评测集和效果指标。

不要在数据规模较小时同时维护 PostgreSQL、Milvus 和 Elasticsearch。先使用 PGVector 跑通闭环，再根据数据量和效果决定是否拆分。

本阶段实际完成的是 **第 2、4、6 项**：关键词召回、结果融合、评测集与指标。第 1、5 项（问题改写、Rerank）和第 3 项（Elasticsearch）留待后续，理由见下。

#### 关键词检索为什么不用 tsvector

计划里写的是「PostgreSQL 全文检索」，但实测发现**它对中文基本不可用**：

```text
to_tsvector('simple', '计算领域高质量科技期刊分级目录（2025）')
  -> '2025':2 '计算领域高质量科技期刊分级目录':1
```

整句中文被切成一个 token，搜「期刊」根本匹配不上；镜像里也没有 zhparser / pg_jieba 这类中文分词扩展（`pg_available_extensions` 里查不到）。

改用 **pg_trgm**：它走字符三元组，对中英文都能工作，而且它的 GIN 索引正好能加速 `LIKE '%关键词%'` 这类包含匹配。实测 `similarity('机器人','机器人学报')` = 0.429、`similarity('机器人','计算机学报')` = 0.000，区分度正确。

但整句直接做 trigram 匹配区分度很差（长问句和每个分块的相似度都在 0.08 上下）。所以先把问句拆成词元：按非中英文数字的字符切分，中文片段额外产出所有 2 字滑动窗口（中文里二字词最常见，不用分词器也能覆盖「期刊」「机器人」这类词）。实测拆词元后，「智能系统学报的主办单位」能命中 4 个词元、其他分块只命中 1 个，区分度才出来。

排序按**命中词元的长度之和**：长度是「专有性」的粗略代理。命中「智能系统学报」显然比命中「中文」更有说服力，后者几乎每个分块都有；不这么做，通用词会把专有词的分辨力稀释掉。

#### 本阶段已实现

- 迁移 `V6__add_keyword_search_index.sql`：启用 `pg_trgm`，给 `document_chunk.content` 建三元组 GIN 索引。
- `retrieval/keyword`：词元抽取（纯函数）+ 关键词召回通道，不依赖任何外部服务。
- `retrieval/vector/ReciprocalRankFusion`：按排名的融合算法（k=60）。选它而不是「加权分数求和」，因为余弦相似度在 0~1 之间、关键词分数是词元长度之和，量纲不可比，RRF 只用排名，天然绕开这个问题，也不用调权重。
- `RetrievalMode`：`VECTOR` / `KEYWORD` / `HYBRID`（默认）。单通道模式保留下来，既方便排查「某条结果是哪一路召回的」，也是评测能分别量出通道贡献的前提。
- `/search` 请求体新增 `mode`，响应新增 `vectorScore` / `keywordScore`，可以看出每条结果由哪一通道召回。融合后的 `score` 是 RRF 分数，绝对值只反映排名，**不要当成相似度**。
- 问答链路改走统一的检索入口，默认 HYBRID。

#### 评测集与实测指标

评测集：`src/test/resources/retrieval-eval-set.json`，11 个问题。**期望答案不是凭印象写的**——先用 SQL 从真实分块内容里反查出每个实体落在哪些分块，再据此填写。语料是 `test.pdf`，5 个分块。

跑法：

```bash
RUN_EXTERNAL_TESTS=true mvn -o test -Dtest=RetrievalEvaluationIT
```

第一次跑出来的是**假结果**，值得记下来：

```text
K=5（= 语料分块总数）时，三种模式的 Recall@K 全是 1.0000
```

因为语料只有 5 个分块，取 topK=5 时每种模式都必然返回全部分块，召回率被撑满，指标完全没有区分度。改成多档 K（1/2/3）之后才有信息量：

```text
K=1   VECTOR  Recall@1=0.4545  MRR=0.6364  命中率=0.6364
      KEYWORD Recall@1=0.5909  MRR=0.7273  命中率=0.7273
      HYBRID  Recall@1=0.4545  MRR=0.6364  命中率=0.6364

K=2   VECTOR  0.6818  0.7273  0.8182
      KEYWORD 0.8182  0.8182  0.9091
      HYBRID  0.7727  0.7273  0.9091

K=3   VECTOR  0.7273  0.7576  0.9091
      KEYWORD 0.9091  0.8485  1.0000
      HYBRID  0.8636  0.7576  1.0000
```

结论与保留意见：

- **关键词通道在这份语料上全面优于向量通道**（三档 K 的指标都更高）。原因是文档是期刊名单，11 个问题里 9 个直接点了期刊名，字面匹配占尽便宜；换成叙述型文档，优势会明显缩小。
- **融合没有带来排序质量的提升**：它的 Recall 介于两者之间，但 MRR 从未超过关键词单通道。原因是语料太小，两个通道返回的几乎是同一组结果，RRF 退化成取平均，把强通道的第一名拉平了。RRF 在这种「两路返回完全相同集合」的情形下本就不是优势场景。
- **指标有跑间波动**：同一套评测连跑两次，VECTOR 的 Recall@1 从 0.3636 变成 0.4545。近似并列的相似度会翻转名次，所以 0.01 量级的差距不能当结论。关键词优于向量这个差距两次都成立，这一条相对可靠。
- 因此**目前不能声称融合提升了效果**。HYBRID 仍是默认值（RRF 是更有普适性的选择），但要判断它是否真的更好，前提是把评测集做大：更多文档、更多提问方式（含不含原词的模糊问法）。

#### 留待后续

- **问题改写与查询扩展（第 1 项）未做**：会增加一次模型调用，等评测集做大、能测出收益之后再决定。
- **Rerank（第 5 项）未做**：需要再引一个 rerank 模型（硅基流动也有对应接口）。同样要先有能分辨效果的评测集，否则是凭感觉调参。
- **Elasticsearch（第 3 项）未做**，按计划里那条原则：数据规模还小时不要引入，先用 PGVector 加 pg_trgm 就够。
- **评测集只有 11 个问题、1 份文档、5 个分块**，样本太小，指标只能当基线参考，不能当过拟合的依据。扩充评测集是后续任何检索优化的前置条件。
- 三元组 GIN 索引在 5 行数据下规划器仍走顺序扫描，索引的价值要到数据量上去才体现。

### 阶段 8：企业化能力

- Spring Security 登录认证。
- RBAC 角色和知识库成员权限。
- 多租户或知识库隔离。
- 文档解析异步任务和失败重试。
- 操作审计日志。
- 请求限流和模型调用超时。
- Actuator、Prometheus、Grafana 监控。
- MinIO 对象存储。
- Docker Compose 部署和生产配置分离。

### 阶段之外的横向改动（已完成）

这些不属于某一个阶段，但已经落地，后续写新接口都要遵守：

- **统一响应格式**：所有接口返回 `Result` 信封 `{code, message, data}`，同时保留真实的 HTTP 状态码。错误响应唯一出口是 `GlobalExceptionHandler`，新增自定义异常必须同步加 `@ExceptionHandler`。两个删除接口返回 `204` 且无 body，是唯一的例外。成功时 `message` 固定为 `success`，失败消息统一用中文，`code` 保持英文常量。
- **日志规范**：全部改用 Lombok `@Slf4j`，按「业务边界 `info` / 明细 `debug` / 可恢复 `warn` / 失败 `error`」分级，每个业务操作覆盖开始、关键步骤和结果；开发环境在本项目包下开到 `debug`。
- **接口契约测试**：Controller 层补了 `@WebMvcTest` 切片测试，覆盖接口的成功路径和各类错误路径，不需要数据库。
- **修掉一个磁盘泄漏**：删除知识库时此前只删数据库行，靠外键级联清掉文档和分块记录，但磁盘上的原文件不在数据库里，会永远留着。现在删除知识库前会先清理其下文档的记录和物理文件。这个问题是阶段 7 做评测时发现磁盘上多出 3 个孤儿文件才暴露出来的。
- **前端工作区**：Vue 3 + Vite + Element Plus + vue-router + axios，代码在 `frontend/` 子目录。实际两条路由：聊天主界面、检索调试页；知识库 CRUD 在弹窗中，文档上传 / 解析 / 向量化 / 分块预览 / 删除在抽屉中。保留多轮会话、引用溯源和 `cited` 来源标记。
- **桌面 Agent 风格界面（2026-10-14 已完成，界面回归通过）**：深色可收起侧栏、暖白阅读区、右侧用户气泡、轻量助手正文、底部圆角输入框、折叠来源与回答复制；处理中文输入法 Enter 误提交，并修正助手占位对象直接赋值不触发响应式更新的问题。未改后端接口，未新增流式输出。验证：`npm run build` 成功，`npm run test:ui` 的 22 项隔离浏览器检查通过，检查尺寸为 1440×900、1024×768；覆盖两轮问答、历史恢复、503 恢复、来源展开、弹窗/抽屉和检索结果展示。截图来自本地夹具，不是本次真实模型联调；主包超过 500 kB 的构建警告仍待后续优化。
- **测试语料与检索评测集**：原来的语料是一份 6 页的期刊名单，同质化严重、切不出几个分块，体现不出检索能力。现在改为项目自己生成的虚构文档集（星云数据平台，6 份文档、32 个分块，含表格、错误码、FAQ、术语表、值班手册、变更记录），生成脚本在 `tools/corpus/`。配套的评测集 52 题、分五类（事实 / 表格 / 跨文档 / 专有名词 / 模糊问法），期望分块由脚本从真实内容里反查而不是手写。

具体约定见 `AGENTS.md`（该文件只保留在本地，未提交到仓库）。

## 6. 可优化和升级方向

以下能力应在最小 RAG 闭环完成后逐步加入。简历中只描述已经实现并完成验证的能力，指标必须来自实际测试。

### 文档处理

- 扩展 PDF、Word、Excel、PPT、HTML、Markdown 等格式解析。
- 对扫描版 PDF 使用 OCR，并将图表和截图交给视觉模型理解。
- 按标题层级、段落、表格和代码块进行结构化分块。
- 对文档和 Chunk 分别做哈希去重与语义去重。
- 使用异步任务、失败重试和进度记录处理大文件。

### 检索和回答质量

- 使用 PostgreSQL 全文检索或 Elasticsearch 实现关键词检索。
- 融合向量检索和关键词检索结果，加入 Rerank 重排序。
- 实现查询改写、查询扩展和多路召回。
- 依据知识库、租户和用户权限过滤检索结果。
- 要求模型在上下文不足时拒答，并返回文档名、页码和原文片段。
- 建立检索评测集，统计 Recall@K、MRR、引用准确率、响应耗时和 Token 消耗。

### 企业工程能力

- 使用 Spring Security、JWT 和 RBAC 实现用户、角色和知识库成员权限。
- 接入阿里云 OSS 或 MinIO：对象使用私有 Bucket，数据库只保存 objectKey；通过临时 URL 或 STS 进行受控访问和前端直传。
- 使用 Redis 保存有限轮次的会话记忆和检索缓存。
- 使用 RabbitMQ 或 Kafka 解耦上传、解析、Embedding 和索引任务。
- 增加审计日志、接口限流、超时、重试和熔断。
- 接入 Actuator、Prometheus、Grafana 和集中日志。
- 使用 Docker Compose 部署；生产环境将应用、PostgreSQL、Redis 和对象存储按需拆分，数据库不暴露公网并配置备份。

## 7. 推荐简历表述

> 基于 Spring Boot、Spring AI、DeepSeek、PostgreSQL/PGVector 构建企业级 RAG 知识库系统；实现 PDF 文档安全上传、元数据管理、自动分块、向量检索和带页码的引用溯源。通过 Flyway 管理数据库版本，并设计对象存储、异步解析、权限隔离和检索评测等可扩展能力。

可在功能稳定后补充真实指标，例如支持的文档格式数量、单文件大小、平均解析耗时、Top-K 召回率、引用准确率和并发量。

## 8. 推荐代码结构

```text
com.itdou.ragknowledgesystem
├── common
│   ├── exception
│   ├── response
│   └── config
├── knowledgebase
│   ├── controller
│   ├── service
│   ├── repository
│   ├── entity
│   └── dto
├── document
│   ├── controller
│   ├── service
│   ├── repository
│   ├── entity
│   ├── dto
│   └── storage
├── ingestion
│   ├── parser
│   ├── chunk
│   └── service
├── retrieval
│   ├── vector
│   ├── keyword
│   ├── rerank
│   └── service
├── chat
│   ├── controller
│   ├── service
│   ├── prompt
│   └── memory
└── infrastructure
    ├── ai
    ├── redis
    ├── search
    └── storage
```

依赖方向保持为：

```text
Controller -> Service -> Repository / Infrastructure
```

## 9. 数据库和配置约定

### 本地 Docker 数据库

```text
Host: localhost
Port: 5433
Database: ragdb
User: rag_app
```

原生 PostgreSQL 使用 `5432`，Docker 的 pgvector 使用 `5433`，不要混用。

### 本地 Docker Redis

```text
Host: localhost
Port: 6380
```

映射到 `6380` 而不是默认的 `6379`，理由同上：避免和本机原生安装的 Redis 撞端口。

### Flyway 迁移

迁移文件位于：

```text
src/main/resources/db/migration
```

命名规则：

```text
V1__create_knowledge_base_tables.sql
V2__enable_pgvector.sql
V3__your_next_change.sql
```

已经执行过的迁移不要直接修改，应新建更高版本的迁移文件。

### 敏感配置

API Key 和数据库密码只放在本地 `.env` 或环境变量中，不要写入 `application.yml`，也不要提交到 Git。

## 10. 当前下一步

阶段 7 的检索优化、以及前端管理后台和检索评测集都已完成，下一步是**阶段 8：企业化能力**。

本次界面优化仅面向桌面使用，属于已完成的横向调整，不代表阶段 8 已开工。下一步仍先确定认证方案和知识库归属关系，再推进后端与前端的认证契约。

已经补上的两件事（原计划里没有单列，记在这里以免漏掉）：

- **前端工作区已完成**：知识库 CRUD、文档上传/解析/向量化/分块预览、检索模式切换对比、多轮问答与引用溯源。界面实际是两条路由加知识库弹窗、文档抽屉，不是四个独立页面。2026-10-14 完成桌面 Agent 风格调整，生产构建和 22 项隔离浏览器回归通过；这次没有重新调用真实模型或数据库验证接口业务。
- **检索评测集已扩容**：语料从「1 份 6 页期刊名单 / 5 个分块」换成项目自己生成的 6 份文档 / 32 个分块；评测集 52 题、分五类，期望分块由脚本反查而非手写。

实测指标（K=3 的 MRR，按问题类型拆开）：

```text
          FACT    TABLE   MULTI_HOP  TERM    PARAPHRASE
VECTOR    0.400   0.133   0.950      0.625   0.617
KEYWORD   0.767   0.933   0.883      0.764   0.600
HYBRID    0.600   0.450   1.000      0.792   0.717
```

这份数据本身解释了每个通道的适用面：表格类（提取出来的文本列间交错，语义被破坏）关键词明显更强；
跨文档综合和模糊问法上向量更强，融合在两处都是最好或接近最好。**整体上关键词通道更强**，
但这和语料类型强相关（技术文档、问题里多为专有名词），不能推出「关键词比向量好」这个一般结论。

开工前需要先定下来的事：**阶段 8 有九项，性质差别很大，不要一次全上。** 建议按这个顺序挑：

1. **Spring Security 登录认证 + 知识库归属**。这一项应该最先做，因为它会改变所有现有接口的调用方式，越晚做改动面越大；前端也要同步加登录页和令牌处理。做完之后还能顺带修掉一个已知缺口：现在任何人都能拿着别人的 `conversationId` 读到那段会话历史。
2. **文档解析异步化 + 失败重试**。现在解析是同步接口，大文件会阻塞请求；`ingestion_task` 表在 V1 就建好了但一直没用上，正好这时候接。
3. **MinIO 对象存储**。本地磁盘存储已经跑通，换对象存储是纯替换，不改变业务逻辑。
4. 审计日志、限流、超时、监控、生产配置分离按需补。

推进顺序：

```text
定下认证方案（会话 / JWT）和用户-知识库的归属关系
  -> 加 Spring Security，给现有接口挂上认证
  -> 把既有数据补上归属（否则老数据没人认领）
  -> 再做异步解析与重试
```

四点已经踩过、写新代码时要记住的约定：

- **`app.embedding.base-url` 不能带 `/v1`**。Spring AI 会自己拼路径，照着服务商文档里按 OpenAI 官方 SDK 约定写的 `.../v1` 抄过来会变成 `/v1/v1/embeddings` 并返回 404。
- **外部依赖（Embedding、聊天模型）失败映射成 503**，与 500 服务端自身故障区分开。
- **模型的引用编号要当成不可信输入**：超出参考资料条数的编号必须丢弃，不能直接透给前端，这是「不编造来源」的技术兜底。
- **Redis 的 `spring.data.redis.timeout` 必须显式设置**。Lettuce 默认 60 秒，不设的话 Redis 挂掉时请求要卡两分钟。
- **PostgreSQL 默认分词器不认识中文**，做任何文本检索之前先确认方案对中文有效，别照抄英文教程里的 tsvector 用法（实测见阶段 7）。

仍然遵守同一条原则：一次只推进一步。要继续调检索的话，现在的评测集（52 题 / 32 个分块 / 6 份文档）比之前强得多，但仍然是单语料、单领域、问题偏专有名词，指标只能当基线参考，不足以支撑「融合一定更好」这类一般性结论——要下这种结论得先把语料和提问方式都做大。
