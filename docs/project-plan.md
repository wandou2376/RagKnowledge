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
| 文件存储 | 第一版本地文件，后续 MinIO |
| 文档解析 | PDFBox、Apache Tika、Apache POI |
| 构建工具 | Maven |
| 本地环境 | Docker Desktop + WSL2 |
| 测试工具 | JUnit 5、Mockito、Postman/Apifox |

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

### 阶段 5：RAG 问答和引用溯源

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

### 阶段 6：Redis 多轮对话记忆

Redis 保存：

```text
conversationId
最近 5~10 轮消息
过期时间 TTL
```

不要无限保存历史上下文。必要时对旧消息进行摘要，避免上下文过长和模型成本持续增加。

### 阶段 7：检索优化

按以下顺序逐步增加：

1. 问题改写和查询扩展。
2. PostgreSQL 全文检索作为关键词检索的低成本版本。
3. Elasticsearch 关键词检索。
4. 向量召回和关键词召回结果融合。
5. Rerank 重排序。
6. 检索评测集和效果指标。

不要在数据规模较小时同时维护 PostgreSQL、Milvus 和 Elasticsearch。先使用 PGVector 跑通闭环，再根据数据量和效果决定是否拆分。

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

- **统一响应格式**：所有接口返回 `Result` 信封 `{code, message, data}`，同时保留真实的 HTTP 状态码。错误响应唯一出口是 `GlobalExceptionHandler`，新增自定义异常必须同步加 `@ExceptionHandler`。两个删除接口返回 `204` 且无 body，是唯一的例外。面向用户的消息统一用中文，`code` 保持英文常量。
- **日志规范**：全部改用 Lombok `@Slf4j`，按「业务边界 `info` / 明细 `debug` / 可恢复 `warn` / 失败 `error`」分级，每个业务操作覆盖开始、关键步骤和结果；开发环境在本项目包下开到 `debug`。
- **接口契约测试**：Controller 层补了 `@WebMvcTest` 切片测试，覆盖 11 个接口的成功路径和 9 类错误路径，不需要数据库。

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

阶段 4 的向量化与检索已联调通过，下一步是**阶段 5：RAG 问答和引用溯源**。

开工前不需要再定别的事——检索接口返回的字段（文档 ID、来源文件名、页码区间、原文）已经足够组装带引用的回答。阶段 5 要做的是把检索结果拼成上下文、约束模型只依据上下文作答、并把引用编号映射回具体分块。

推进顺序：

```text
调用向量检索拿到 Top-K 分块
  -> 组装系统 Prompt 与上下文（每段带引用编号）
  -> 调用 DeepSeek（deepseek-flash）
  -> 解析回答里的引用编号，映射回文档、页码和原文
  -> 返回答案 + 来源列表
```

两个已经踩过、写新代码时要记住的坑：

- **`app.embedding.base-url` 不能带 `/v1`**。Spring AI 会自己拼路径，照着服务商文档里按 OpenAI 官方 SDK 约定写的 `.../v1` 抄过来会变成 `/v1/v1/embeddings` 并返回 404。
- **外部依赖（Embedding）失败映射成 503**，与 500 服务端自身故障区分开。阶段 5 调用 DeepSeek 失败时沿用同一约定。

仍然遵守同一条原则：一次只推进一步，不要一次性把检索、问答、多轮记忆全部堆进去。
