## 目标

把后端所有 HTTP 响应统一成 `Result<T>` 信封，保留真实 HTTP 状态码。这是一次**破坏性变更**（响应体结构变了），当前没有前端对接，时机最便宜。

## 已确定的决策

- 手工包装：controller 方法显式返回 `Result`，不用 `ResponseBodyAdvice`（少一层魔法，面试也好讲）
- 保留真实状态码：201 / 204 / 400 / 404 / 409 / 422 / 500
- `code` 继续用字符串，成功时 `"OK"`，失败时沿用现有常量（`KNOWLEDGE_BASE_NOT_FOUND` 等）
- **创建接口保留 201 + Location 头**，body 用 Result 包住；**删除接口保持 204 无 body**（是 11 个接口里唯一不返回信封的例外，前端拦截器要为无 body 加一个判断）
- `Result` 三个字段：`code` / `message` / `data`，**不带 timestamp**（比现在的 `ApiError` 少一个字段，这是明确接受的取舍）
- 同一批改动里补上 controller 层 MockMvc 测试

## 新增文件（3 个）

**1. `common/response/Result.java`**
```java
public record Result<T>(String code, String message, T data)
```
静态工厂：`success(T data)`、`success()`（data 为 null）、`failure(String code, String message)`。
放在 `common/response`，正好补上 `project-plan.md` 第 8 节里规划过但一直没建的目录。

**2. `knowledgebase/controller/KnowledgeBaseControllerTest.java`**
**3. `document/controller/DocumentControllerTest.java`**

## 修改文件（3 个）

**4. `KnowledgeBaseController.java`** — 5 个方法
- `create` → `ResponseEntity<Result<KnowledgeBaseResponse>>`，`created(location).body(Result.success(response))`，201 + Location 不变
- `list` → `Result<List<KnowledgeBaseResponse>>`
- `getById` / `update` → `Result<KnowledgeBaseResponse>`
- `delete` → **保持不变**，204 无 body

**5. `DocumentController.java`** — 6 个方法，同上模式
- `upload` → `ResponseEntity<Result<DocumentResponse>>`，201 + Location 不变
- `listByKnowledgeBase` / `listChunks` → `Result<List<...>>`
- `getById` → `Result<DocumentResponse>`
- `parse` → `Result<DocumentParseResponse>`
- `delete` → **保持不变**，204

**6. `GlobalExceptionHandler.java`** — 8 个 handler 全部改为返回 `ResponseEntity<Result<Void>>`，状态码一个都不动，`new ApiError(code, msg, Instant.now())` 换成 `Result.failure(code, msg)`。

## 删除文件（1 个）

**7. `common/exception/ApiError.java`**
探索确认它的引用完全封闭在 `common/exception` 包内，测试里零命中，删了没有任何外溢影响。

## 必须一起补的异常处理（否则信封不统一）

这是本次改动里我会主动扩出去的部分，但它是「统一响应格式」这个目标成立的**前提**：现在**没有兜底 handler**，下面这些情况会返回 Spring 默认的错误体 `{timestamp, status, error, path}`，而不是 `Result`。信封只做成 8 个业务异常的样子，那叫格式不统一。

在 `GlobalExceptionHandler` 里新增 5 个 handler：

| 异常 | 状态码 | 触发场景 |
| --- | --- | --- |
| `HttpMessageNotReadableException` | 400 | 请求体不是合法 JSON、枚举值非法 |
| `MethodArgumentTypeMismatchException` | 400 | 路径参数不是合法 UUID |
| `HttpRequestMethodNotSupportedException` | 405 | 方法不匹配 |
| `NoResourceFoundException` | 404 | 访问了不存在的路径 |
| `Exception`（兜底） | 500 | 未预期的异常 |

兜底 handler **必须 `log.error` 打完整堆栈**，否则 500 会变成静默失败。Spring 按最具体匹配选择 handler，所以顺序无关，但我仍会把兜底写在文件末尾便于阅读。

actuator 端点走 `WebMvcEndpointHandlerMapping`，不属于 `@ControllerAdvice` 的作用范围，不会被这些 handler 影响。

## 测试计划

用 `@WebMvcTest` 切片测试 + `@MockitoBean`（Spring Boot 3.4+ 的新写法，`@MockBean` 已废弃），**不需要数据库**，service 全部 mock 掉。`@WebMvcTest` 会自动加载 `@RestControllerAdvice`，所以错误信封能被真实断言。

断言覆盖：
- 11 个接口的成功路径：`status()`、`jsonPath("$.code")` 为 `"OK"`、`jsonPath("$.data.xxx")` 字段值
- 错误路径：400（校验失败、非 PDF、畸形 JSON、非法 UUID）、404、409（重复上传）、422（解析失败）、405、500 兜底，全部断言 `$.code` 和 `$.message`
- 两个删除接口：断言 204 且 `content().string("")` 为空，确认没被塞 body
- 两个创建接口：断言 201 且 `Location` 头存在

**已知风险**：切片上下文会读到 `application-dev.yml` 里的 `${RAG_POSTGRES_PASSWORD}`、`${DEEPSEEK_API_KEY}` 占位符。测试工作目录是项目根目录，`.env` 会被 `spring.config.import` 加载，正常情况下能解析。如果因为 `.env` 缺失导致上下文起不来，用 `@WebMvcTest(properties = {...})` 在注解里显式补上假值，不依赖 `.env`。

## 明确不做的事

- 不动 service 层和 repository 层（一行都不用改）
- 不动现有 7 个测试文件（全在 service 和工具层，对信封变化无感）
- 不加分页、文件下载接口、`DocumentResponse` 的 `errorMessage`/`parsedAt` 字段
- 不引入 springdoc/Swagger

## 验证方式

1. `mvn -B -o test` — 现有 32 个测试保持全绿 + 新增的 controller 测试
2. 启动 Docker 和 Tomcat 后 curl 抽查 5 个接口（成功、创建、删除、404、400），把真实响应贴给你确认

## 我对结果的把握程度

- **编译通过 + 现有 32 个测试全绿**：接近百分之百。改动不碰 service 层，而现有测试全在 service 和工具层。
- **11 个接口信封形状正确**：靠新增的 MockMvc 测试证明。`@WebMvcTest` 断言的是真实 DispatcherServlet + 真实 `@ControllerAdvice` 的行为，覆盖度足够，不是自欺欺人的 mock 断言。
- **唯一无法在本地自测的**：真机 Tomcat 起来之后的完整链路（需要 Docker，当前没运行）。这一步会交给你，我会给出 curl 命令和预期响应。
