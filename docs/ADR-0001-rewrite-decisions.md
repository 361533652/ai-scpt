# ADR-0001：GmScript 改造的架构裁决

日期：2026-10-27
状态：已定（用户已拍板，专家团队 7 人评审已归档于 `docs/`）

## 背景

`E:\gitbub-project\ai-scpt` 现有旧版 GmScript：Swing + FlatLaf 界面、LuaJ 3.0.1 脚本引擎、
JNativeHook 全局键鼠钩子、JNA SendInput 相对鼠标、OkHttp 的 OpenAI 兼容客户端，
32 个文件约 4000 行，一次提交（`fe0178c`）。用户自评「太粗糙，bug 一大堆」，要求在同一工作目录上改造。

评审证据：`docs/PRD-product.md`（产品）、`docs/architecture-target.md`（软件架构）、
`docs/backend-architecture.md`（后端）、`docs/ui-design.md`（界面）。

## 裁决

| 决策点 | 结论 | 依据 / 分歧处理 |
| --- | --- | --- |
| 交付形态 | Java 本地后端 + Web 前端 | 用户选定。红线：全局钩子、键盘鼠标注入、LuaJ 执行**必须留在 Java 进程内**，浏览器只做控制面 |
| 后端框架 | **Spring Boot 3.5.16**（非 Javalin） | 软件架构师推荐 Javalin（轻量），但用户选定 Spring Boot；承接上一会话的 `spring-ai-ollama` 经验，且后续 RAG / Tool Calling 直接可用 |
| AI 框架 | **Spring AI 1.1.8**：`spring-ai-starter-model-ollama`（默认）+ `spring-ai-starter-model-openai`（云端/任意兼容端点） | 用户选定「默认本地 Ollama + 保留云端 OpenAI 兼容配置」。两条通道都由 Spring AI 的 `ChatModel` 承载，切 provider 不改业务代码 |
| 前端 | **Vite + TypeScript + React 18** | 前端开发者结论：编辑器/虚拟列表生态最成熟 |
| 代码编辑器 | **CodeMirror 6** | 前端开发者与软件架构师分歧（后者推 Monaco）；裁决 CodeMirror 6——体积小一个数量级、纯 ESM 易随 fat jar 离线托管，Lua 高亮用 `@codemirror/legacy-modes`，本场景不需要 Monaco 的 TS 智能感知 |
| 存储 | **SQLite**（`~/.gmscpt/gmscpt.db`，WAL）+ 脚本正文以 `~/.gmscpt/scripts/<id>.lua` 为真源 | 后端架构师方案；纯 JSON 撑不住日志/版本/全文检索 |
| 增量路线 | 新后端建在 `com.gmscpt.server.*` 包下，旧 `com.gmscpt.*`（Swing/旧内核）原地保留至跑通后再清退 | 用户选定「增量」。理由：新内核类名与旧内核冲突（`ScriptEngine`/`InputSimulator` 等），分包子树是唯一不打架的做法 |

### 清退结果（2026-10-27 完成）

第二阶段的清退已经执行：旧包 `com.gmscpt.{gui,core,input,lua,agent,config,knowledge}` 与
`App.java` 共 32 个文件已从工作区删除，`flatlaf` / `gson` / `okhttp`（含 okio、kotlin-stdlib
这些只为 okhttp 服务的传递依赖）同步从 `pom.xml` 移除。`luaj` / `jnativehook` / `jna` 保留，
新内核依赖它们。

依据：新版代码对旧包的引用数为 **0**（唯一命中是 `GmScriptApplication` 的注释），
因此删除是纯减法。清退后 `mvn clean test` 通过（29 个用例），
`scripts/smoke.sh` 的 31 项端到端断言全部通过，行为无回归。

旧实现可从首个提交取回：`git show fe0178c --stat`。
`knowledge.json` 未删除——它仍是知识库的初始种子。
| Java 版本 | 21（本地 JBR 21.0.8） | 旧版 17 可编译通过；Spring Boot 3.5 要求 17+ |
| 范围 | 按用户选定「做全」：脚本库、热键、执行日志、AI 生成、知识库、脚本市场/多脚本管理。但**按阶段交付**，每阶段必须有可运行验收点 | 产品经理给出 MVP→三期分级，此处只把「必须可运行」作为硬门槛 |

## 旧版必须修掉的缺陷（评审取证，带行号）

| 编号 | 缺陷 | 位置 | 新设计对策 |
| --- | --- | --- | --- |
| F1 | **停止机制从未生效**：`JsePlatform.standardGlobals()` 不含 `debug` 库，`installStopHook()` 直接 return，死循环停不住 | 旧 `core/ScriptEngine.java:21,43-46` | 沙箱用 `debugGlobals()`；停止阶梯「置位→interrupt→指令钩子→超时判 killed」 |
| F2 | 事件队列无上限 → OOM | 旧 `core/ScriptContext.java:22,83-93` | 每脚本有界队列 + 溢出丢最旧 + 丢弃计数上报 |
| H1 | 停止后按键卡死，且该键此后永久收不到事件 | 旧 `input/InputSimulator.java:152-161` + `gui/MainFrame.java:188-199` | `releaseAll()` 纳入停止路径；模拟键集合与物理态分离 |
| H2 | `SendInput` 返回值被忽略，权限不足时静默失效 | 旧 `input/NativeMouseInput.java:143,164,185,202` | 校验返回值并上报 UI |
| H3 | F11/F12 键名解析错（`VC_F10=68/VC_F11=87/VC_F12=88`），并导致自反馈回环 | 旧 `input/GlobalInputListener.java:80-82,124` | 显式键码表（含 numpad、F13+），未映射一律 `unknown(N)` |
| H4 | SSE 无限读超时 + 无法取消 → 聊天框永久锁死；每次请求新建 OkHttpClient | 旧 `agent/impl/OpenAiClient.java:121-124,189-219` | Reactor `Flux` + `Disposable` 取消；客户端复用；有限超时 |
| H5 | 相对鼠标改全局系统设置且不还原 | 旧 `input/NativeMouseInput.java:102-104` | 默认不改系统设置；必须改时落盘备份 + 关停钩子还原 |
| H6 | 亚像素累加器跨运行泄漏、`(int)` 截断 | 旧 `input/InputSimulator.java:31-32,126-136` | 每次运行/每段位移前 reset；显式取整 |
| H7 | 模拟输入过滤失效：存的是 Lua 语义 1/2/3，比的是 AWT 掩码 16/8/4，查的是 JNativeHook 按钮号 → 集合永不相等，合成点击被当真实事件回灌 | 旧 `input/InputSimulator.java:87-96,204-213` + `input/GlobalInputListener.java:188` | 统一为「注入时打标 `dwExtraInfo`/配对登记」单一语义；按钮号统一为 1=左 2=中 3=右 |
| M1 | 事件只在 `Sleep()` 派发，不 Sleep 的脚本收不到 OnEvent | 旧 `lua/UtilApi.java:22-44` + `gui/MainFrame.java:180` | 三处协作泵：阻塞 API、空闲轮询、指令钩子 |
| — | 无「热键绑定」功能，仅硬编码 Ctrl+Shift+F12 急停；事件无条件广播给所有引擎 | 旧 `input/GlobalInputListener.java:211-222`、`core/EventDispatcher.java:56-60` | `HotkeyRegistry`：按键→脚本绑定表 + 冲突检测；只派发给订阅者 |
| — | 知识库只读、无向量检索；脚本库/多脚本管理不存在 | 旧 `knowledge/KnowledgeBase.java:34-47`、`config/AppConfig.java:27` | SQLite `kb_entry`/`kb_chunk` + `script` 表；二期接 `nomic-embed-text` |
| — | 配置损坏即无法启动；API key 明文落盘；请求体全量打屏 | 旧 `config/AppConfig.java:43-59,66`、`agent/impl/OpenAiClient.java:59-63` | 原子写 + 解析失败回退默认；key 单独存储且接口只回 `hasApiKey`；结构化脱敏日志 |
| — | 语法高亮自激循环（`changedUpdate` → 定时器 → `setCharacterAttributes` → `changedUpdate`），CPU 常驻 | 旧 `gui/ScriptEditorPanel.java:158-162,380,395` | 该 UI 整体迁前端，高亮逻辑由 CodeMirror 承担 |
| — | 中文 i18n 缺续行符导致 `chat.welcome` 丢行 | 旧 `resources/i18n/messages_zh_CN.properties:15-21` | 文案迁前端 `web/src/locales`，后端只留错误码 |

## 安全红线

1. 只监听 `127.0.0.1`；每次启动生成随机 token，写操作校验 `X-GmScript-Token` + `Origin`；单实例文件锁。
2. Lua 沙箱去掉 `os`/`io`/`luajava`，AI 生成的脚本不得直接触达文件系统与 JVM。
3. 输入注入与钩子只在 Java 进程内；前端只调 loopback API，绝不直接注入。
4. 不改系统级设置（鼠标速度等）；确需修改时必须有备份与还原路径。

## 不做

- 不做 macOS/Linux 客户端（SendInput 仅 Windows）。
- 不读写游戏内存、不绕反作弊、不做驱动级注入。
- 不做账号体系、云同步、付费与公网暴露。
- 不使用 CDN 与外链字体（离线可用是硬要求）。
