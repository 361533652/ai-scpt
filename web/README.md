# GmScript Web 前端

本地宏脚本工作台的界面层。浏览器只做控制面：全局钩子、输入注入、Lua 执行都在 Java 后端进程里（见 `docs/ADR-0001-rewrite-decisions.md`）。

接口以 `docs/api-contract.md` 为准，配色与组件规格以 `docs/ui-design.md` 为准。

## 命令

开发：

```
npm run dev
```

Vite 起在 `http://localhost:5173`，`/api` 反向代理到 `http://127.0.0.1:8790`。
代理层会把 `Origin` 改写成 `http://127.0.0.1:8790`，否则后端的写请求校验（403 ORIGIN_REJECTED）会拦掉 POST/PUT/DELETE。

构建：

```
npm run build
```

先 `tsc --noEmit` 做全量类型检查，再打包到 `../src/main/resources/static`（`emptyOutDir: true`，`base: './'`），由 Spring Boot 从 classpath 托管。
单独跑类型检查用 `npm run typecheck`。

## 访问令牌

后端每次启动生成随机 token，只监听 `127.0.0.1:8790`。页面从 URL 查询参数 `token` 读取一次并放进 `sessionStorage`，之后所有请求（含 SSE）都带 `X-GmScript-Token` 头。

正常入口是后端启动时打印的那条地址：

```
http://127.0.0.1:8790/?token=xxx
```

开发模式下把 token 手工带到 5173 也一样：`http://localhost:5173/?token=xxx`。
地址栏里没有 token、`sessionStorage` 里也没有时，页面显示一屏说明和手工填写入口，不会白屏。

## 与契约文档不一致的地方（按后端实现对接）

写界面时后端代码已在仓库里可用，以下几处 `docs/api-contract.md` 与实际实现不同，前端按**实现**对接，并在 `src/api/types.ts` 里注释标明：

| 项目 | 契约写法 | 后端实际 |
|---|---|---|
| 启用/禁用 | 契约第 2 节没有该接口 | `PUT /api/scripts/{id}/status`，body `{status}` |
| `KnowledgeEntry` | 未给字段清单 | `{id, game, title, description, script, tags, source, enabled, updatedAt}`，正文在 `script` 而不是 `content` |
| `Settings` | 有 `cloudModelName` | 没有该字段（只有 `cloudModel`） |
| `RunRecord` | 契约写 `runId` + `endedAt` | `{id, scriptId, trigger, startedAt(ISO), endedAt(ISO), exit, errorMessage, eventCount}` |
| `ActiveRun.startedAt` | 未写明类型 | epoch millis 数字 |
| `ChatMessage` | 未给字段清单 | `{id, sessionId, role(SYSTEM/USER/ASSISTANT), content, thinking, tokensIn, tokensOut, providerId, model, createdAt}`，思考链在独立 `thinking` 字段 |

以下两点需要后端确认（前端目前无法验证）：

- `POST /api/kb` 的请求体按契约「无 id」，但后端 `domain.KnowledgeEntry` 的紧凑构造器要求 `id` 非空。
  若新建知识库条目返回 500，需要后端放宽这条校验或改用独立的入参记录。
- `RunService.RunRecord` 与 `RunRepository` 的映射字段名建议联调时再核对一次
  （前端对 `endedAt` 缺失已做回退，状态栏不会崩）。

## 目录

```
src/
  api/        client.ts（token/错误体/超时）、sse.ts（fetch + ReadableStream 解析 SSE）、endpoints.ts、types.ts
  components/ 顶栏、脚本库、编辑器、AI 对话、知识库、控制台、设置、Lua 帮助
  hooks/      健康检查、日志流、对话流、Lua API 文档、错误通知
  lib/        token、热键规范化、编辑器桥、时间格式、下载与剪贴板
  store/      ui.ts（布局与通知，持久化到 localStorage）、editor.ts（草稿与 rev）
  styles/     tokens.css（设计令牌）+ 各面板样式
```

## 已知对接前提

- SSE 全部手写解析：`POST /api/ai/chat` 需要请求体和自定义头，`EventSource` 做不到，日志流同理。
- 控制台时间戳只显示到秒：ui-design.md 规定时间戳列固定 68px，13px 等宽下带毫秒会折行；
  毫秒保留在悬停提示与导出的 `.log` 里。
- 热键录制在浏览器里能捕获键盘与 `mouse4/mouse5`，但浏览器会把鼠标侧键当成前进/后退，
  可能触发页面导航。桌面端后端注册的是系统级钩子，不受此限制；Web 端只负责把规范形式写回后端。
