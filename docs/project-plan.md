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
| 聊天模型 | DeepSeek `deepseek-v4-flash-vision-exp` |
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

聊天模型只负责生成回答和处理图片理解，不负责文档向量化。进入向量化阶段后，还需要单独选择 Embedding 模型，例如云端 Embedding API 或本地 `bge-m3`。

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

### 阶段 2：文档上传和元数据管理（实现完成，待本地联调）

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

### 阶段 3：PDF 解析和 Chunking

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

### 阶段 4：Embedding 和 PGVector 检索

选择单独的 Embedding 模型后：

```text
Chunk -> Embedding -> vector_store
问题 -> Embedding -> Top-K 相似度检索
```

需要确定：

- Embedding 模型名称。
- 向量维度。
- 相似度算法（余弦相似度优先）。
- Top-K 数量。
- 知识库过滤条件。

验收标准：

- Chunk 能被向量化并写入 PGVector。
- 输入问题能召回相关 Chunk。
- 检索结果带有文档 ID、页码和原文。

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

知识库 CRUD 和第一版本地 PDF 上传已完成，下一项是对上传链路进行本地联调，然后进入 PDF 解析和自动分块：

```text
启动 PostgreSQL + pgvector
  -> 创建知识库
  -> 上传真实 PDF
  -> 检查 rag.document 和 storage/documents
  -> 验证重复上传、非法文件和删除行为
  -> 进入 PDF 解析和自动分块
```

完成文档上传后，再进入 PDF 解析和自动分块，不要一次性把解析、向量化、问答全部堆进上传接口。
