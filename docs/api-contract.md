# GmScript 后端 API 契约（v1）

前端与后端之间的唯一契约。所有接口前缀 `/api`，绑定 `127.0.0.1:8790`。

## 鉴权与跨域

- 除静态资源外，所有 `/api/**` 都要求 token：优先取请求头 `X-GmScript-Token`，其次取查询参数 `token`。
  token 在服务启动时随机生成，打印在控制台并在启动时以 `http://127.0.0.1:8790/?token=xxx` 打开浏览器；
  前端把它存进 `sessionStorage` 后统一走请求头。
- 写请求（非 GET/HEAD）若带 `Origin` 头且不是 `http://127.0.0.1:8790` / `http://localhost:8790` → `403 ORIGIN_REJECTED`。
- 错误体统一：`{"code":"...","message":"...","details":{...},"traceId":"..."}`。
- 错误码：`VALIDATION_FAILED(400)`、`SCRIPT_NOT_FOUND(404)`、`REV_CONFLICT(409)`、`ACCEL_CONFLICT(409)`、
  `SCRIPT_ALREADY_RUNNING(409)`、`LUA_COMPILE_ERROR(422)`、`LLM_OFFLINE(503)`、`LLM_UPSTREAM_ERROR(502)`、
  `INTERNAL(500)`。

## 1. 健康与运行态

| 方法 路径 | 请求 | 响应 |
| --- | --- | --- |
| GET `/api/health` | — | `{version, authRequired, hook:{registered,error}, ollama:{baseUrl,reachable,models[]}, running[]}` |

## 2. 脚本库

| 方法 路径 | 请求体 | 响应 |
| --- | --- | --- |
| GET `/api/scripts` | — | `{items:[Script]}` |
| POST `/api/scripts` | `{name,description?,source?}` | `201 Script` |
| GET `/api/scripts/{id}` | — | `Script` |
| PUT `/api/scripts/{id}` | `{name?,description?,source?,rev}` | `Script`／409 REV_CONFLICT |
| DELETE `/api/scripts/{id}` | — | `204` |
| PUT `/api/scripts/{id}/status` | `{status}` | `Script` |
| POST `/api/scripts/{id}/validate` | `{source?}` | `{ok, diagnostics:[{line,column,message}], hasOnEvent}` |
| POST `/api/scripts/import` | `{filename,content}` | `201 Script` |
| GET `/api/scripts/{id}/export` | — | `text/plain` Lua 正文 |

`Script`：`{id,name,description,source,rev,status,createdAt,updatedAt}`，`status` ∈ `ENABLED|DISABLED`。
`rev` 是乐观锁：客户端提交过期的 `rev` 返回 409，避免 AI 与手改互相覆盖。
删除是**软删除**（保留 `.lua` 文件与历史版本，可用仓储层的 `purge` 做物理清理）。

## 3. 运行控制

| 方法 路径 | 请求体 | 响应 |
| --- | --- | --- |
| POST `/api/scripts/{id}/run` | `{trigger?}` | `202 {runId,scriptId,status:"RUNNING"}` |
| POST `/api/scripts/{id}/stop` | — | `{stopped:boolean}` |
| POST `/api/runs/stop-all` | — | `{stopped:[scriptId]}` |
| GET `/api/runs/active` | — | `{items:[ActiveRun]}` |
| GET `/api/runs?scriptId=&limit=` | — | `{items:[RunRecord]}` |

`ActiveRun`：`{scriptId,scriptName,runId,trigger,startedAt,eventCount,actionCount,droppedEvents}`，
其中 **`startedAt` 是 epoch millis 数字**。
`RunRecord`：`{id,scriptId,trigger,startedAt,endedAt,exit,errorMessage,eventCount,actionCount,droppedEvents}`，
主键字段叫 **`id`**（不是 `runId`），时间是 **ISO-8601 字符串**；`exit` ∈ `OK|LUA_ERROR|STOPPED|TIMEOUT|KILLED`。
注意 `RunRecord` 不是 `ActiveRun` 的超集，前端按需分别取。

## 4. 热键绑定

| 方法 路径 | 请求体 | 响应 |
| --- | --- | --- |
| GET `/api/scripts/{id}/hotkeys` | — | `{items:[Hotkey]}` |
| PUT `/api/scripts/{id}/hotkeys` | `{items:[{accelerator,event,action,enabled}]}` | `{items:[Hotkey]}`／409 ACCEL_CONFLICT |

`accelerator` 形如 `ctrl+shift+f8`、`f8`、`mouse4`，全小写、修饰键顺序固定为 `ctrl+alt+shift+meta`。
`event` ∈ `KEY_PRESSED|KEY_RELEASED`；`action` ∈ `RUN|STOP|TOGGLE`。

## 5. 日志（REST + SSE）

| 方法 路径 | 说明 |
| --- | --- |
| GET `/api/logs?scriptId=&level=&limit=&sinceSeq=` | 历史日志，`{items:[LogEntry]}` |
| GET `/api/logs/stream?sinceSeq=` | **SSE**：`event: log` 推 `LogEntry`；`event: run` 推运行态变化；每 15s 一行 `: ping` |

`LogEntry`：`{seq,timeMillis,level,source,scriptId,runId,message}`，`level` ∈ `DEBUG|INFO|WARN|ERROR`。
`seq` 单调递增并作为 SSE 的 `id:`，断线后用 `sinceSeq` 续传。

## 6. AI 对话（SSE）

| 方法 路径 | 请求体 | 响应 |
| --- | --- | --- |
| POST `/api/ai/chat` | `{sessionId?,message,providerId,model?,scriptId?,includeScript?,kbEntryIds?}` | **SSE**（见下） |
| POST `/api/ai/chat/stop` | `{sessionId}` | `{stopped:boolean}`（取消该会话在飞的请求） |
| GET `/api/ai/providers` | — | `{items:[{id,kind,baseUrl,model,available}]}` |
| GET `/api/ai/models?providerId=` | — | `{models:[...]}` |
| GET `/api/ai/sessions` | — | `{items:[ChatSession]}` |
| GET `/api/ai/sessions/{id}/messages` | — | `{items:[ChatMessage]}` |
| DELETE `/api/ai/sessions/{id}` | — | `204` |

SSE 事件序列（全部 `data:` 为 JSON）：

```
event: started   data: {"sessionId":"...","messageId":"...","providerId":"ollama","model":"qwen2.5-coder:7b"}
event: delta     data: {"channel":"thinking","text":"..."}      // qwen3 的思考链，可折叠
event: delta     data: {"channel":"content","text":"..."}
event: usage     data: {"tokensIn":123,"tokensOut":456}
event: done      data: {"messageId":"...","codeBlocks":["..."]}  // 已落库的完整答复
event: error     data: {"code":"LLM_OFFLINE","message":"..."}
```

注意「思考链」有两种形态，别混：
- **SSE 流里的**是 `channel=thinking` 的 delta 帧（这是给前端实时渲染用的）；
- **落库后的 `ChatMessage`** 把思考链放在独立的 `thinking` 字段里，`role` ∈ `USER|ASSISTANT`（大写），
  正文在 `content`。历史消息里没有 `channel` 这个概念。

## 7. 知识库

| 方法 路径 | 请求体 | 响应 |
| --- | --- | --- |
| GET `/api/kb?q=&limit=` | — | `{items:[KnowledgeEntry]}` |
| POST `/api/kb` | `KnowledgeEntry`（**id 可省略**，`enabled` 省略按 `true` 处理） | `201 KnowledgeEntry` |
| PUT `/api/kb/{id}` | `KnowledgeEntry` | `KnowledgeEntry` |
| DELETE `/api/kb/{id}` | — | `204` |
| POST `/api/kb/search` | `{query,topK?,mode?}` | `{hits:[{entry,score,snippet}]}`，`mode` ∈ `keyword|auto` |

`KnowledgeEntry`：`{id, game, title, description, script, tags[], source, enabled, updatedAt}`。
**脚本正文的字段名是 `script`**（不是 `content`）。

一期 `keyword` 为子串/标签匹配；二期接入 `nomic-embed-text` 做向量检索，`mode=auto` 时自动降级。

## 8. 设置与 Lua API 文档

| 方法 路径 | 请求体 | 响应 |
| --- | --- | --- |
| GET `/api/settings` | — | `Settings` |
| PUT `/api/settings` | `Settings` + 可选 `cloudApiKey` | `Settings` |
| GET `/api/lua/api` | — | `{functions:[{name,signature,doc}],keys:[...],events:[...],mouseButtons:[...],sandbox}` |

`Settings`：`{providerId, ollamaBaseUrl, ollamaModel, cloudBaseUrl, cloudModel, hasCloudApiKey,
emergencyHotkey, preciseRelativeMove, eventQueueCapacity, stopGraceMs}`。
**没有 `cloudModelName`**（早期草案里的笔误，模型名只有 `cloudModel`）。
**apiKey 只进不出**：PUT 时可传 `cloudApiKey`（null = 不改动，空串 = 清除），GET 只回 `hasCloudApiKey`。

`/api/lua/api` 同时喂给三处：编辑器补全、AI 系统提示词、前端帮助面板，保证「文档与实际注册的 Lua 函数」不漂移
（旧版的提示词是手写副本，已经和 API 注册表对不上了）。
