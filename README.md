# RAG-KnowledgeSystem

一个基于 Spring Boot、Spring AI 和 Vue 3 的知识库问答项目。上传 PDF 后，可完成文本解析、分块和向量化，再结合检索结果生成带文档来源与页码的回答。

## 核心功能

- 知识库管理：创建、编辑、查询和删除知识库。
- 文档处理：上传 PDF、清理重复页眉页脚、分块预览和向量化。
- 混合检索：支持向量、关键词和 RRF 融合三种模式。
- 知识库问答：展示引用来源、原文和页码，支持 Redis 多轮会话记忆。
- 桌面聊天工作区：会话侧栏、文档管理抽屉、来源折叠和检索调试页。
- 检索评测：提供固定 PDF 语料与评测集，用于对比不同检索模式。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.5.16、Spring AI 1.0.0 |
| 数据存储 | PostgreSQL 17、pgvector、Spring Data JPA、Flyway |
| 会话记忆 | Redis 7 |
| 文档解析 | PDFBox 3.0.8 |
| 聊天模型 | 任意会话模型，如 DeepSeek等 |
| 向量模型 | 硅基流动 `BAAI/bge-m3`，1024 维 |
| 前端 | Vue 3、Vite、Element Plus、vue-router、axios |

## 本地运行

准备 JDK 21、Maven、Node.js 24（含 npm）以及支持 Docker Compose 的 Docker 环境。确认 `JAVA_HOME` 指向 JDK 21。

### 1. 配置环境变量

在项目根目录执行（已有 `.env` 时跳过复制，避免覆盖）：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，设置数据库密码以及自己的 `DEEPSEEK_API_KEY`、`SILICONFLOW_API_KEY`。不要提交真实密码和 API Key。

后端默认使用 `dev` 配置，并从项目根目录加载 `.env`。Embedding Key 可以暂时留空，但向量化和向量检索需要有效 Key。

### 2. 启动数据库和 Redis

在项目根目录执行：

```powershell
docker compose up -d
```

PostgreSQL 使用 `localhost:5433`，默认数据库为 `ragdb`、用户为 `rag_app`；Redis 使用 `localhost:6380`。注意不要与本机的 5432、6379 端口混用。

### 3. 启动后端

在项目根目录执行：

```powershell
mvn spring-boot:run
```

后端默认地址为 `http://localhost:8080`。启动时 Flyway 自动执行数据库迁移，Hibernate 校验实体与表结构是否一致。

### 4. 启动前端

另开一个终端，在项目根目录执行：

```powershell
cd frontend
npm ci
npm run dev
```

浏览器打开 `http://localhost:5173`。开发服务器会将 `/api` 代理到后端；后端端口不同时，可用 `VITE_BACKEND` 环境变量覆盖代理目标。

## 使用流程

创建知识库 → 上传 PDF → 点击解析 → 检查分块 → 点击向量化 → 开始提问。

上传、解析和向量化是三个独立操作。解析与向量化目前同步执行，较大文件需要等待；PDF 单文件上限为 50 MB。文档保存在本地 `storage/documents/`。

## 测试与构建

后端常规测试（项目根目录，依赖已缓存时可离线运行）：

```powershell
mvn -B -o test
```

首次运行缺少依赖时，去掉 `-o` 下载依赖。真实数据库与模型相关测试需要单独启用，不属于默认常规测试。

前端构建与隔离界面测试（在 `frontend/` 中执行）：

```powershell
npm run build
npm run test:ui
```

## 当前范围

当前面向网页使用，仅支持 PDF 解析与同步问答。
