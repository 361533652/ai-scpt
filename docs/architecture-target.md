# GmScript 目标架构：Java 本地后端 + Web 前端

依据：27 个 Java 文件 / 4136 行逐文件通读。约束：钩子与 SendInput 注入必须留在 Java 进程内。
本机事实：`mvn` 不在 PATH（只有 IDEA 自带 maven3）、Node 24 + pnpm 11 可用、JDK21 在 JBR、corretto-17 在 `~/.jdks` → 需加 `mvnw`。

## 1. 文字版架构图

```
┌────────────────── 浏览器 SPA (Vite+TS+React) ──────────────────┐
│ 编辑器Monaco │ 运行 │ 日志 │ AI对话 │ 知识库 │ 脚本库/市场 │ 设置│
└─────────▲──────────────────────────────────────┬─────────────┘
   WS /ws/events (日志·状态·AI流)              HTTP /api (REST)
┌─────────┴──────────────────────────────────────▼─────────────┐
│ api   Javalin(Jetty)+虚拟线程池 · Token/Origin 校验 · DTO      │
├──────────────────────────────────────────────────────────────┤
│ app   RunService ScriptService ChatService KnowledgeService   │
├───────────────────────────────┬──────────────────────────────┤
│ runtime（唯一接触 LuaJ）       │ infra                        │
│  TriggerRegistry 热键/定时判定 │ SQLite 脚本/版本/日志/知识库FTS│
│  ScriptRuntime ×N             │ OpenAiCompatClient (OkHttp/SSE)│
│   ├ 专属线程（1 脚本 1 线程）   │ Ollama 11434/v1（默认）       │
│   ├ 邮箱 MPSC：停止/重载/回调   │ ScriptFileStore scripts/*.lua │
│   └ Globals 沙箱（去 os/io）    │ AppConfig config.json        │
├───────────────────────────────┴──────────────────────────────┤
│ input  EventRouter→每脚本有界队列(满则丢+计数)  MouseKeyState  │
│        InputGateway(Robot/SendInput+模拟登记+亚像素累加)       │
└─────────▲────────────────────────────────────────────────────┘
          │ 回调 <1ms，禁 Lua / 禁 I/O / 禁阻塞
  JNativeHook 原生派发线程（全局键鼠钩子）
```

## 2. 进程与线程模型

单进程：钩子、注入、LuaJ、HTTP 同进程；前端在浏览器，生产由 Javalin 托管静态资源。

| 线程 | 数量 | 职责 | 硬约束 |
|---|---|---|---|
| JNativeHook 派发 | 1（库自带） | 过滤自注入事件→路由入队；更新 MouseKeyState | 禁 Lua/阻塞；<1ms（超时被系统摘钩） |
| ScriptRuntime-`<id>` | 每脚本 1 | 独占 Globals：主 chunk、OnEvent、热键回调、Sleep 协作派发 | 仅此线程可触碰 LuaValue |
| 命令邮箱（复用上者） | — | 消费停止/重载/调函数/触发 | 跨线程只传 String/数值 |
| HTTP/WS | 池化 | 校验→用例；WS 推日志/状态/AI 流 | 禁碰 runtime 内部，走邮箱+超时 |
| AI 网关 | OkHttp | SSE 流式；每会话单飞防历史竞态 | 历史不入 Lua 域 |
| 定时器/关停钩子 | 各 1 | Cron 触发；还原鼠标速度、卸载钩子、停 runtime | — |

必须修的旧模型缺陷：① 事件只在 `Sleep()` 派发，死循环不 Sleep 的脚本收不到 OnEvent → 三处协作 drain（Sleep、空闲轮询、debug hook 每 N 指令），2s 未让出且队列积压时 UI 告警"事件丢弃 N 条"；② 钩子线程绝不能等待 → 每脚本有界队列 4096，满则丢最旧并计数；③ 靠 100ms 时间窗过滤自注入会吞掉真实快速点击，且未在 KeyMapping 的键名不被过滤 → 改为按下/抬起配对登记。

安全与健壮性：`JsePlatform.standardGlobals()` 带 `os/io/luajava`（AI 生成的脚本可 `os.execute`、`luajava.newInstance("java.lang.ProcessBuilder")`）→ 换沙箱 Globals；`beginPreciseMode()` 改系统鼠标速度，进程被 kill 不还原 → 落盘 `mouse-speed.bak` + 引用计数 + 启动/关停双向还原；本地 HTTP 可被任意网页 CSRF → 只绑 127.0.0.1 + 每次启动随机 token + Origin 校验 + 单实例文件锁。

## 3. 包结构（单 Maven 模块 + `web/`）

```
src/main/java/com/gmscpt/
├─ Bootstrap.java   # 唯一组合根：配置→infra→app→api→关停钩子
├─ domain/          # 无框架依赖：script(Script/仓储端口) runtime(ScriptState/RunLogEntry)
│                   # hotkey(Hotkey/Binding) knowledge chat
├─ app/             # RunService ScriptService ChatService KnowledgeService
│                   # LibraryService SettingsService LuaFormatter
├─ runtime/         # 仅此包 import luaj：RuntimeRegistry ScriptRuntime LuaRuntime
│                   # LuaApiRegistry Mouse/Keyboard/UtilApi TriggerRegistry InputGateway
├─ input/           # GlobalInputListener NativeMouseInput KeyMapping MouseKeyState EventRouter
├─ infra/db|llm|fs|bus/  # SQLite 仓储(迁移) PromptBuilder OpenAiCompatClient 事件总线
└─ api/http|ws|dto/ # 控制器 + /ws/events + DTO（不外泄 domain 实体）
web/ → Vite 产物进 src/main/resources/static
```

依赖方向 `api→app→domain`，`runtime|infra|input` 实现 domain 端口。热键/定时/进程触发统一产出 `TriggerCommand` 投进脚本邮箱，与手动 Run 同路径。

## 4. 技术选型结论

| 决策 | 结论 | 理由 | 主动放弃 |
|---|---|---|---|
| Web 框架 | **Javalin 6.x**（内嵌 Jetty，Java 17+），不用 Spring Boot 3 | 启动 ~0.3s、依赖 ~5MB、一等 WebSocket 与静态资源；单机工具用不上 DI/Actuator 全家桶 | 自动配置/Actuator/声明式校验；以 1 个组合根+~150 行手写校验替代。`api/` 保持薄，日后换 Spring 是 1 天重构 |
| 前端 | **Vite+TS+React**（TanStack Query+Zustand+原生 WS），不用 Vue | 聊天流/日志流/多面板状态复杂，React 编辑器与虚拟列表生态最成熟 | Vue SFC 的书写简洁 |
| 编辑器 | **Monaco**（Lua 高亮内置，补全源来自 `/api/lua-api`） | 补全/悬停/诊断一次到位，本地加载不在意体积 | 首屏体积；备选 CodeMirror 6 |
| 存储 | **SQLite**（sqlite-jdbc+手写 SQL+FTS5），脚本正文以 `~/.gmscpt/scripts/<id>.lua` 为准 | 日志/版本/知识库需查询与全文检索；文件为真源，DB 存索引+元数据，用户可用自己的编辑器与 git | 纯 JSON 无法胜任查询与并发写；放弃"单文件即全部状态"（外部改动按 last-write-wins 导入） |
| LLM | Ollama 默认 `http://localhost:11434/v1`（OpenAI 兼容，apiKey 占位 "ollama"）+ 云端兼容端点共用代码路径 | 换 baseUrl/model 即切换，现有 SSE 逻辑可复用 | Ollama 原生 `/api` 高级参数（需要时再加适配器） |
| 构建 | 单模块 pom + `mvnw`；`web/` 由 Vite 产出；`build-exe.ps1` 扩展 jpackage | 平滑演进，避免双构建系统 | 多模块的编译期边界，改由包+测试守护 |

## 5. 旧代码逐文件判定

**保留（3）**：`KeyMapping.java`（键名词表，补 numpad/media，前后端共用）、`MouseKeyState.java`（原子共享态）、`LlmClient.java`（接口合理，加 cancel/usage）；资源 `knowledge.json`、`i18n/*.properties` 作导入种子与文案源。

**改写（18）**：`App.java`→Bootstrap；`AppConfig.java`（只留非领域设置，key 掩码+权限收紧）、`Constants.java`（收编事件名）、`I18n.java`（收敛为后端错误码，界面文案迁 `web/src/locales`）；`ScriptContext.java`→ScriptRuntime（状态机+邮箱+日志总线，去 Swing 回调）、`ScriptEngine.java`→LuaRuntime（去 EDT 假设、三处 drain、沙箱 Globals）、`EventDispatcher.java`→EventRouter（N 脚本广播+有界队列+丢帧计数）；`GlobalInputListener.java`（紧急停止改可配置热键，接 TriggerRegistry）、`InputSimulator.java`→InputGateway（唯一注入入口+配对过滤，保留亚像素累加）、`NativeMouseInput.java`（留 SendInput，加崩溃安全还原+引用计数）；`lua/` 四个文件轻改（断言仅在脚本线程调用；API 文档改机器可读 schema，同时供前端补全与 AI 提示词）；`AiAgent.java`→ChatService+PromptBuilder（多会话、入库、单飞、并发安全）、`impl/OpenAiClient.java`（删 stdout 全量打印，改可开关脱敏日志；SSE 解析独立；加取消/超时/重试；默认指向 Ollama）；`GameData.java`+`KnowledgeBase.java`→SQLite 仓储+KnowledgeService（保留 JSON 导入格式）；`build-exe.ps1`（加 web 产物与 SQLite native）。

**删除（6 + flatlaf 依赖）**：`MainFrame.java`（→RunService+前端）、`ScriptEditorPanel.java`（高亮/缩进/文件 IO 归前端；纯函数 `formatLua` 移植为 `app/LuaFormatter` 并加测试）、`ChatPanel.java`（流式卡片/代码块提取归前端，系统提示词归 PromptBuilder）、`ConsolePanel.java`（前端虚拟滚动+后端环形缓冲）、`SettingsDialog.java`、`KnowledgeViewerDialog.java`。

## 6. 分阶段落地与验收点

| 阶段 | 内容 | 可运行验收点 |
|---|---|---|
| P0 骨架 1d | `mvnw`+Javalin+Bootstrap+`/api/health`+WS 心跳+web 空壳，旧 Swing 暂留 | `mvnw package` 出 fat jar；`java -jar` 后页面显示版本与钩子注册状态 |
| P1 输入内核 3–5d（风险最高先做） | 钩子、EventRouter、ScriptRuntime（线程+邮箱+有界队列+丢帧计数）、4 组 Lua API、沙箱 Globals、Run/Stop/热键、日志 WS | rapid-fire 游戏内生效；Stop 与 Ctrl+Shift+F12 ≤1s 停；停止或进程被 kill 后鼠标速度均还原；2 脚本并行互不干扰；钩子回调 p99 <1ms；不 Sleep 紧循环给出丢弃告警；`os.execute`/`luajava` 被拒 |
| P2 持久化与脚本库 3–4d | SQLite 迁移、脚本 CRUD+版本、`.lua` 镜像与外部改动导入、热键绑定+冲突检测、日志入库查询 | 重启后脚本/热键/历史全量恢复；外部编辑自动导入；热键冲突被拒；连跑 30 分钟不 OOM |
| P3 前端完整+AI 4–6d | Monaco、多会话流式对话（代码块一键插入并 Run）、知识库 CRUD+FTS、设置页（`/api/models` 拉模型、云端配置、连通测试）、脚本库/市场 | 断网下本地模型可用；AI 生成脚本直接 Run 成功；切 provider 后同一对话可用；可勾选知识条目注入上下文 |
| P4 触发与打磨 3d | 热键/定时/窗口标题/进程触发、单实例锁、token+Origin、jpackage EXE（内嵌 JRE21+静态前端+SQLite native）、首启向导 | 双击 EXE 可用；第二实例被拒；第三方网页无法调用本地 API |
| P5 清退 | 删 `gui` 包与 Swing 依赖，`config.json` 的 lastScript 迁为脚本库首条 | 无 Swing 依赖仍通过全部验收点 |

关键 ADR：① 沙箱化 LuaJ ② 脚本文件为真源、SQLite 为索引 ③ 单线程协作式 runtime（不做多线程调度 Lua）④ 选 Javalin 而非 Spring Boot ⑤ 本地 API 的 token+Origin+单实例防护。
