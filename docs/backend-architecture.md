# GmScript 后端架构设计（Java 本地服务 + Web 前端）

## 一、旧版契约与并发缺陷

**须保留的契约**

| 契约 | 位置 | 问题 |
|---|---|---|
| `chat/chatStream/ping` + `ChatMessage(role,content)` + `StreamCallback.onThinking/onContent` | agent/ | 双通道已有；无取消/重试/离线降级 |
| Lua 全局函数名、`OnEvent(event,arg)`、`KEY_*`/`MOUSE_BUTTON_*` | lua/, Constants | 前端与提示词的稳定面 |
| `getApiDocumentation()` | lua/ | 提示词是手写副本，与注册函数漂移（缺 `ResetMouseAccumulator`） |
| `ScriptContext` 队列 + `processPendingEvents()` | core/ | "钩子只入队、Lua 只在脚本线程跑"的约束是对的 |
| `AppConfig` JSON | config/ | 字段扁平、apiKey 明文、写入非原子 |

**并发缺陷**

1. 引擎无收口：`runScript` 直接覆盖 `currentEngine` 且不 join 旧线程，旧引擎仍注册在 `EventDispatcher` 继续派发（MainFrame.java:141,162,191）。
2. 无超时/硬中止：只靠 `debug.sethook`(5k 指令) 与 `Sleep`；`sethook` 失败被静默 `catch`（ScriptEngine.java:60），`while true do end` 占满一核且停不住。
3. 派发依赖 `Sleep`：只有 `UtilApi.Sleep` 泵事件（:27），`PressAndReleaseKey` 直接调 `sim.sleep`（KeyboardApi.java:67）→ 长按期间事件全堵；队列无界 → OOM。
4. 单例污染：`InputSimulator` 模拟键集合、`Robot`、`MouseKeyState` 全进程唯一 → A 脚本的模拟键吞掉 B 脚本真实事件，`IsKeyPressed` 读到他人状态。
5. 回声抑制用 100ms 时间窗启发式（InputSimulator.java:55-76）：真实快速点击被误丢、异步释放漏判。
6. 钩子线程干业务：急停回调直接在 JNativeHook 线程执行（EventDispatcher.java:78），超时会被 Windows 摘钩。
7. AI 侧：`AiAgent.history` 无锁全局列表、单会话（AiAgent.java:35）；流式 `readTimeout(0)` 永不超时（OpenAiClient.java:123）；解析异常 `continue` 吞错（:171）；`rebuildClient()` 泄漏连接池。
8. 其他：`stopRequested` 不复位（引擎不可复用）、`scriptThread` 非 volatile（可能漏 interrupt）、`beginPreciseMode` 改系统全局鼠标速度、`GetRunningTime` 起点错误。

## 二、接口清单（前缀 `/api/v1`，SSE/WS 用于推送，其余 REST）

| 方法 路径 | 请求 | 响应 | 错误 |
|---|---|---|---|
| GET /health · /health/ready · /metrics | — | 存活/依赖检查/Prometheus | 503 |
| GET /scripts?q=&status=&cursor= | — | `{items,nextCursor}` | 400 |
| POST /scripts | `{name,description,source}` | 201 `{id,rev}` | 400,422 |
| GET·PUT·DELETE /scripts/{id} | PUT `{source,rev,note}` | `{script,rev,compile:{ok,errors[]}}` | 404,409 REV_CONFLICT |
| POST /scripts/{id}/validate | `{source}` | `{ok,diagnostics[{line,col,msg}]}` | 422 |
| POST /scripts/{id}/run | `{trigger}` | 202 `{runId,status}` | 404,409,423 |
| POST /runs/{runId}/stop | `{force}` | 202 `{status}` | 404,409 |
| GET /runs?scriptId=&from=&to= | — | `{items,nextCursor}` | 400 |
| GET·PUT /scripts/{id}/hotkeys | `{hotkeys[{accelerator,event,action,enabled}]}` | 同左 | 400,404,409 ACCEL_CONFLICT |
| GET /logs?runId=&level=&since=&cursor= | — | `{items,nextCursor}` | 400 |
| GET /logs/stream（SSE） | `Last-Event-ID` | `event:log` + 15s 心跳 | 400 |
| WS /ws/events | `{op:subscribe,topics[]}` | 推送 run.status/logs/status | 400,429 |
| POST /ai/chat（SSE） | `{sessionId?,message,kb,provider?,model?,temperature?,scriptId?}` | `started{requestId,provider,model}` → `delta{channel:content\|thinking}` → `usage` → `done{finishReason}`；`error{code,message}` | 400,404,429,502,503,504 |
| POST /ai/chat/{requestId}/cancel | — | 202 `{status:cancelled}` | 404,409 |
| GET /ai/sessions · /ai/sessions/{id}/messages · DELETE | — | 会话与消息（含 thinking、tokens） | 404 |
| POST /ai/generate-script（SSE） | `{prompt,scriptId?,kb}` | 同 chat，`done` 附 `{script,codeBlocks[]}` | 422 NO_CODE,503 |
| GET /llm/providers | — | `[{id,kind,baseUrl,model,health{state,latencyMs,models[]}}]` | — |
| GET /llm/models?providerId= · POST /llm/test | `{baseUrl?,apiKey?,model}` | `{ok,latencyMs,models[]}` | 400,502,503,504 |
| GET·POST·PUT /kb/entries[/{id}] · POST /kb/search | `{query,topK,mode}` | `{hits[{id,title,score,snippet}]}` | 400,404,503 |
| POST /kb/reindex | `{embeddingModel?}` | 202 `{jobId,chunks}` | 503 |
| GET·PUT /settings | 配置对象（apiKey 只回 `hasApiKey`） | 同左 | 400 |
| POST /emergency-stop | — | 202 `{stopped[]}` | — |

错误码：400 VALIDATION_FAILED；404 `*_NOT_FOUND`；409 SCRIPT_ALREADY_RUNNING/REV_CONFLICT/ACCEL_CONFLICT；422 LUA_COMPILE_ERROR/NO_CODE；423 RUN_LIMIT_REACHED；429 RATE_LIMITED；502 LLM_UPSTREAM_ERROR；503 LLM_OFFLINE/KB_DEGRADED；504 LLM_TIMEOUT；500 INTERNAL。统一错误体 `{code,message,details,traceId}`，`traceId` 全链路贯通。

## 三、LLM 提供方抽象

`interface ChatProvider { id(); kind(); capabilities(); chat(ChatRequest, ChatSink, CancelToken); embed(List<String>, CancelToken); health(); }`

内部统一事件 `Started | Delta(channel=CONTENT|THINKING) | Usage | Done(finishReason) | Error(code)`，前端只认一套，两个实现各自翻译，模型可热切换。

- **OllamaProvider**：`POST {base}/api/chat`，体 `{model,messages,stream:true,keep_alive:"5m",options:{temperature,num_ctx,num_predict}}`；响应是 NDJSON（非 SSE），逐行 `{message:{content,thinking},done,done_reason,prompt_eval_count,eval_count}`；`/api/tags` 探活与模型存在性；**不设 Authorization**（修复旧版对本地端硬发 Bearer）；嵌入 `POST /api/embed`。
- **OpenAiCompatProvider**：`POST {base}/v1/chat/completions`，`stream:true,stream_options:{include_usage:true}`；解析 `data:` 行、`delta.content/reasoning_content`、`[DONE]`；嵌入 `POST /v1/embeddings`。
- **取消**：每请求 `requestId` + `CancelToken(标志位 + Call.cancel())`；SSE 断开 / `.../cancel` / 服务端 deadline 任一触发即取消并释放连接（修复旧版 `readTimeout(0)` 挂死）。
- **超时**：connect 本机 2s、远程 8s；TTFB 30s（本地冷加载可配，加载中推 `warming`）；块间静默 30s；总 300s。仅"未出首 token 且错误为连接/502/503"重试 ≤2 次并抖动退避，出 token 后绝不重试。
- **上下文裁剪**：按 token 预算（保守 chars/2.5）= system + top-k 知识片段 + 最近 K 轮，超出折叠为滚动摘要（失败则截断并标注）；Ollama 显式设 `num_ctx`（旧版是整库注入 + 固定 20 条裁剪，既爆上下文又丢语义）。
- **离线降级**：探针缓存 10s + 熔断（3 次失败 → OPEN 30s → 半开）。脚本编辑/运行/热键/日志不依赖 LLM；AI 接口回 503 LLM_OFFLINE（带 `retryAfterMs`）而非 500；知识检索降级为 BM25（`mode:keyword`）。

## 四、配置与持久化

`config.json` 只放引导项（host/port/dataDir/logLevel/ui）；业务数据入 SQLite `gmscpt.db`（WAL、`busy_timeout=5000`、`foreign_keys=ON`、版本化迁移）。

- `script(id ULID,name,description,source,content_hash,rev,status,created_at,updated_at,deleted_at)`；`script_revision(id,script_id,rev,source,note,created_at)`
- `script_hotkey(id,script_id,accelerator,event,action[run|stop|toggle],enabled)`，`UNIQUE(accelerator,event)`
- `run(id,script_id,trigger[manual|hotkey|api],started_at,ended_at,exit[ok|lua_error|stopped|timeout|killed],error_message,event_count,action_count)`
- `log(id,ts,level,run_id,script_id,source,message,data_json)`，索引 `(run_id,ts)`，按天保留
- `chat_session(id,title,provider_id,model,created_at)`；`chat_message(id,session_id,role,content,thinking,tokens_in,tokens_out,provider,model,request_id,created_at)`
- `kb_entry(id,game,title,description,script,tags_json,source,enabled,content_hash,updated_at)`；`kb_chunk(id,entry_id,ordinal,text,tokens,embedding BLOB float32 LE,dim,model,content_hash)`
- `setting(key,value_json,updated_at)`（apiKey 经 Windows DPAPI `CryptProtectData` 加密，接口只回 `hasApiKey`）；`audit(id,ts,actor,action,target,detail_json)`

写入用临时文件 + 原子替换（修复旧版非原子写导致的半写损坏）。

## 五、脚本执行的并发与安全

1. **单脚本单 Lua 状态、单承运线程（lane）**：编译、顶层执行、OnEvent、重载全部排到同一 lane，其他线程永不触碰 `Globals`/`LuaValue`。
2. **钩子线程零阻塞**：回调只做时间戳 → 解码 → 写入有界 MPSC 环形队列 → 返回，不碰 Lua/Swing/磁盘；队列满计数 `events_dropped_total`，优先丢 press、保留 release（防卡键）。
3. **协作式泵**：所有阻塞型 Lua API 统一走 `Scheduler.pump()`（含 `PressAndReleaseKey`，修复旧版只有 `Sleep` 才泵的问题）；无主循环的脚本走 5ms 空闲泵；按"急停 > 热键/释放 > 按下"派发并设每轮事件预算。
4. **脚本间隔离**：SendInput 用 `dwExtraInfo=scriptRunId` 打标做回声抑制（取代 100ms 启发式）；每脚本独立"已按下键集"，结束或异常时强制全部释放；`IsKeyPressed` 只读物理态。
5. **停止阶梯**：`requestStop` → 置位 + `interrupt` → Lua 钩子每 5k 指令检查并泵 → 超 `stopGraceMs`(3s) 未退出即判 `killed`：摘除输入通道、**弃用该状态不再触碰**、释放全部按键、回收 lane、写审计。
6. **预算与限速**：`maxRunMs`（事件驱动默认不限）、`onEventBudgetMs`(2s)、单轮 wall clock、输入速率上限（默认 200 动作/秒）防失控洪泛。急停与热键匹配在独立调度线程执行，不在钩子线程跑回调。

## 六、日志与可观测性

- 结构化 JSONL 落盘 + SQLite，字段 `ts,level,logger,traceId,scriptId,runId,requestId,provider,model,event,durationMs,errCode`；apiKey 与提示词正文默认脱敏，正文仅 DEBUG 且显式开启。
- 指标：`script_runs_total{exit}`、`events_dispatched_total`/`events_dropped_total`、`input_actions_total`、`llm_requests_total{provider,outcome}`、`llm_tokens_total`；直方图 `llm_ttfb_ms`、`llm_total_ms`、`hook_to_onEvent_ms`、`pump_lag_ms`、`api_request_ms`；仪表 `scripts_running`、`event_queue_depth{script}`、`sqlite_wal_bytes`。
- SLO：REST p95 < 200ms（不含 LLM）；钩子 → 入队 p99 < 2ms；泵延迟 p95 < 20ms；本地热模型首 token p95 < 2s；回声抑制误判 = 0。
- `traceparent` 在 HTTP 层生成并透传到 LLM 调用；SSE/WS 15s 心跳 + `Last-Event-ID` 断线续传。
