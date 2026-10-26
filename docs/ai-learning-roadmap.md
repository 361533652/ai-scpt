# Java 后端 → AI 应用工程 · 学习路线

> 为「已有 Spring Boot 实战能力、本机 RTX 5070 Ti 12GB 可跑 7B–14B」的转行者定制。
> 生成日期：2026-10-27。版本号与市场数据均经联网核实，随生态变化会过期。

---

## 一、先定位：你该走哪条路

2026 年的 AI 岗位已经分层，**不是所有方向都值得投**：

| 方向 | 是否适合你 | 理由 |
| --- | --- | --- |
| **AI 应用工程师**（RAG / 结构化输出 / 流式 / 工具调用） | ✅ 主攻 | Java 生态已补齐（Spring AI 2.x GA、LangChain4j 1.22），银行/券商/国央企大量要 Spring 技术栈 |
| **Agent 工程师** | ✅ 12 个月目标 | 吃的是后端纵深：状态机、幂等、权限、失败恢复——Python 转行者反而弱 |
| **AI 平台 / 网关工程师** | ✅ 差异化优势区 | 模型路由、限流、计费、审计，本质是分布式系统问题 |
| **AI 评测工程师** | ✅ 备选 | 转行者最容易忽略、招聘方最看重；你的工程素养在这里直接变现 |
| 大模型算法 / 预训练 | ❌ 别碰 | 仅占 AI 岗位 8.7%，其中 92% 要求硕士以上，且预训练需求在收缩 |
| 提示词工程师（独立岗） | ❌ 已消亡 | 岗位量较峰值降超 70%，已退化为隐性基础能力 |
| 数据标注 / AI 训练师 | ❌ 别碰 | 正被模型自身替代 |

**一句话路径**：
`Java 后端 → Spring AI 单 Agent + 自建评测集 → RAG 混合检索闭环 → 银行/券商 AI 应用岗（6 个月）→ AI 平台/网关岗（12 个月）`

---

## 二、你的真实起点（已核实）

**硬件**：RTX 5070 Ti Laptop（12GB VRAM，Blackwell sm_120）、i9-13900HX、32GB 内存、Win11
**本地模型**（Ollama 已跑通）：

| 模型 | 体积 | 能力 | 用途 |
| --- | --- | --- | --- |
| `qwen3:8b` | 5.2GB Q4_K_M | completion / tools / **thinking** | 通用推理、Agent 主模型 |
| `qwen2.5-coder:7b` | 4.7GB Q4_K_M | completion / **tools** / insert | 代码生成、工具调用 |
| `gemma3:4b` | 3.4GB Q4_K_M | completion / **vision** | 多模态、轻量任务 |
| `nomic-embed-text` | 0.3GB F16 | **embedding** | 向量检索（目前代码里还没用上） |

**项目现状**：GmScript 2.0（Spring Boot 3.5.16 + Spring AI 1.1.8 + LuaJ + JNativeHook + SQLite + React 前端）已可运行，33 单测 / 35 接口断言 / 真机注入与钩子链路全绿。

**这意味着一件重要的事**：你不需要从零造项目。**项目就是最好的教材**——它已经跑通，你只需要按下面的路线，一块一块把 AI 能力装进去，每装一块就是一次真实工程训练。

---

## 三、三个月速通（打地基 + 出第一个可投递的东西）

### 第 1–2 周：把「调模型」变成「懂模型」

| 学什么 | 到什么程度算会 | 资源 |
| --- | --- | --- |
| Token / 上下文窗口 / 采样参数（temperature、top_p、top_k、repeat_penalty） | 能解释为什么同一个 prompt 两次结果不同；能针对任务调参 | 官方 API 文档 + 自己实测 |
| 模型能力边界 | 知道 qwen3 的 thinking 什么时候有用、什么时候是浪费 token | 用本地模型实测对比 |
| 结构化输出 | 能用 JSON Schema 约束输出，并处理解析失败 | Spring AI `entity(Class)` |

**动手**：在 GmScript 里加一个「设置 → 模型参数」页，把 temperature / num_ctx / 是否开 thinking 做成可调项，改完立刻影响下一次对话。

> ⚠️ **这是你现有代码里已经踩过的坑**：`OllamaAiGateway` 曾经对 `qwen2.5-coder:7b` 硬发 `think:true`，Ollama 直接回 400 `does not support thinking`。这类「模型能力差异」是真实工程里最常见的坑，比任何教程都值钱。

### 第 3–4 周：Spring AI 2.x 迁移（顺便吃掉生态现状）

**为什么先做这个**：你用的是 1.1.8，而 Spring AI 已到 **2.1.0-RC1**（2.0.0/2.0.1/2.0.2 已 GA）。
这次迁移会逼你读懂官方 release notes、搞清 API 演进逻辑——这本身就是「跟进技术」的能力训练。

**先说清楚代价（来自官方 2.0 GA 公告，这点很重要）**：

> Spring AI 2.0 设计为配合 **Spring Boot 4.0/4.1 + Spring Framework 7.0** 使用，
> 并且把 Jackson 从 2 升级到了 **Jackson 3**。

也就是说这不是「改个版本号」的升级，而是**一次跨大版本的联动升级**：
Spring Boot 3.5.16 → 4.x，Jackson 2 → 3。你的 GmScript 里 Spring Boot、Jackson 都在用，
`SqlXxxRepository` 的 JSON 处理、`ApiExceptionHandler` 的序列化都受影响。

**所以这一步有两种做法，自己选**：

| 做法 | 适合 | 代价 |
| --- | --- | --- |
| **A. 留在 1.1.8，只把概念学透** | 想先把 RAG/Agent 跑通 | 学的是即将过时的 API |
| **B. 真升级到 2.x** | 想练「跨大版本迁移」这个稀缺能力 | 可能要花 2–4 周，会踩 Spring Boot 4 的坑 |

**我的建议：先做 A 的 1–2 周，把 RAG 跑通拿到正反馈，再回头做 B。**
理由：迁移是苦活，不该放在刚开始、还没有成就感的时候。等你熟悉了这套代码再动它，效率高得多。

| 2.x 要掌握的核心变化 | 具体内容 |
| --- | --- |
| **ChatClient 成为主 API** | `prompt()` / `advisors()` / `entity()` / `stream()`；ChatModel 退为底层构件 |
| **工具调用循环提到 Advisor 链** | `ToolCallingAdvisor` 自动注册，1.x 里每个模型各自私有的 tool loop 被移除。这是 2.0 最重要的架构变化 |
| **StructuredOutputValidationAdvisor** | 结构化输出校验失败时**自动自我纠正**，不用你自己写重试 |
| **ToolSearchToolCallingAdvisor** | 工具数量上百时的渐进式披露，不用每次请求都注册全部工具 |
| **Options 全面重构** | 默认值统一定义在 options 层；options 不可变、用 builder 创建；配置属性去掉了 `.options` 段 |
| **MCP 一等公民** | `@McpTool` / `@McpResource` / `@McpPrompt` 注解即可暴露服务；**Streamable HTTP 取代 SSE 成为默认传输** |
| 空安全 | 全量 JSpecify 注解 |

**动手**：把 GmScript 的 `OllamaAiGateway` / `OpenAiCompatAiGateway` 迁到 Spring AI 2.x。
**这是本路线里最有价值的一次练习**——因为它有真实约束（流式要保留、thinking 要分帧、要能取消），
比跟着教程敲 demo 难得多，也真实得多。

**资源**：
- [Spring AI 2.0.0 GA 官方公告](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now)（英，**一手信息，必读**）
- [Spring AI 官方参考文档](https://docs.spring.io/spring-ai/reference/index.html)（英，权威）
- [Spring AI Alibaba](https://java2ai.com/)（中文，Agent/Graph 一站式）
- [LangChain4j 官方文档](https://docs.langchain4j.dev/)（英，另一条路线，对比着看）

> ❗**避坑**：网上大量「Spring AI 1.0 GA 重磅发布」的中文博客，示例代码在 2.x 上多数跑不通。
> 凡版本敏感 API，**以英文官方 release notes 为准**。

### Spring AI vs LangChain4j：该选哪个

这不是「谁更好」，而是分工不同（[三框架同台对比](https://github.com/java-ai-in-action/framework-compare)）：

| 维度 | 更强的一方 | 说明 |
| --- | --- | --- |
| 模型集成数量 | **LangChain4j** | 30+ 模型开箱即用 |
| 向量库支持 | **LangChain4j** | 覆盖最广，两者差异 <10% |
| MCP 支持 | **Spring AI 2.0** | `@McpTool` 注解 47 行暴露 10 个方法 |
| 可观测性 | **Spring AI** | 与 Micrometer / Actuator 天然一致 |

**给你的结论**：
- **主力用 Spring AI** —— 你的项目已经是 Spring Boot，接入成本最低，MCP 支持最好。
- **LangChain4j 要看得懂** —— 面试常问对比，且很多企业（尤其非 Spring 栈）在用。
- 两者可以共存，但**别在同一个项目里混用**，抽象层打架。

### 第 5–8 周：RAG 全链路（这是转行核心考点）

| 环节 | 必须掌握 | 容易踩的坑 |
| --- | --- | --- |
| 文档解析 | PDF/Word/Markdown 抽取，表格与代码块保真 | 直接 `pdftotext` 会把表格拍平 |
| 分块 | 固定长度 vs 语义分块 vs 递归分块，overlap 的作用 | 中文按字符切会切碎词 |
| 嵌入 | `nomic-embed-text` 的维度、归一化、批量调用 | 忘了归一化导致相似度失真 |
| 向量库 | pgvector（推荐，和 Spring Boot 同栈）或 Milvus | 索引类型选错，数据量一大就慢 |
| 混合检索 | dense + BM25 + RRF 融合 | 只做向量检索，专有名词命中率极低 |
| 重排序 | BGE-reranker 等 cross-encoder | 把 rerank 当万能药，忽略召回本来就没命中 |
| 查询改写 | HyDE、多查询展开 | 过度改写反而跑偏 |
| 权限过滤 | 检索前按用户权限过滤 | 检索后才过滤 = 数据泄露 |

**动手（这条线直接产出可面试的项目）**：给 GmScript 加「私有知识库问答」——
把你自己写的 `docs/*.md` 和 Lua API 文档喂进去，问「怎么给脚本绑热键」能答对且给出处。
`nomic-embed-text` 你已经装了但代码里没用，这次正好用上。

**做到什么程度算掌握**：能对着一个答错的问题，说清楚是「没召回」还是「召回了但没用好」，并能用数据证明改进有效。

#### 关于 GraphRAG：现在别碰

2026 年有多组实测数据，结论一致：

- 微软的对比里 GraphRAG 在「全局性、综合性」问题上胜率 72–83%，Recall@5 从 73.4% 提到 87.8%
  （[Persistent 分析](https://www.persistent.com/blogs/when-does-graphrag-outperform-traditional-rag/)）。
- **但代价是每查询喂给模型 9,770 tokens，而朴素 RAG 只要 3,000 左右**——
  三倍成本换多跳问题的提升（[FutureAGI 实测](https://futureagi.com/blog/graphrag-vs-rag/)）。
- 中文实测更直接：KG-GraphRAG 因**图谱覆盖率只有 65%** 而全面落后，
  「实体缺失是致命伤」；混合方案（RAG + 轻量图谱）比纯 RAG 高 8–10 个点，比纯 GraphRAG 高 5–6 个点
  （[腾讯云社区实测](https://cloud.tencent.com.cn/developer/article/2740285)）。

**给你的判断**：GraphRAG 解决的是「多跳、全局归纳」类问题，你的知识库问答不属此类。
**先把混合检索（dense + BM25 + RRF）做扎实**，这比上 GraphRAG 收益高得多。
面试时能说清「我评估过 GraphRAG，但我的场景不需要，因为 X」反而是加分项。

**资源**：
- [RAG From Zero](https://github.com/MisterBooo/rag-from-zero)（中文，手写切分/BM25/重排，不用框架，专治「跑通了不知道为啥」）
- [Milvus 中文文档](https://milvus.io/docs/zh)（混合检索与 rerank 有专章）
- [RAGFlow 文档](https://ragflow.io/docs/dev/)（看工业级文档解析怎么做）
- [FlagEmbedding / BGE-M3](https://github.com/FlagOpen/FlagEmbedding)（中文场景 embedding+reranker 默认选择）
- [Production RAG 2026 指南](https://www.premai.io/blog/building-production-rag-architecture-chunking-evaluation-monitoring-2026-guide/)（英，工程视角完整）

### 第 9–12 周：评测 + 可观测（**这是你和 90% 转行者拉开差距的地方**）

传统单测断言不了概率输出。这是 Java 工程师迁移率最低的能力（约 10%），也是招聘方最看重的。

| 要掌握 | 具体内容 |
| --- | --- |
| 黄金评测集 | 自己攒 50–100 条「问题 + 期望要点」，覆盖正常/边界/对抗 |
| LLM-as-Judge | 用强模型打分，**必须知道它的陷阱**：位置偏见、长度偏好、自我偏好 |
| 回归测试 | 每次改 prompt / 换模型，跑一遍评测集，指标不能退化 |
| 指标 | 检索侧 recall@k / MRR；生成侧 faithfulness / answer relevancy |
| 可观测 | 每次调用的 token 消耗、延迟、成本归因、失败率 |

**动手**：给 GmScript 的知识库问答建一套评测集 + 一个 `mvn test` 能跑的回归测试。
这一步做完，你的项目从「demo」变成「有质量保障的系统」——面试时这比多两个功能管用得多。

**资源**：
- [Ragas 文档](https://docs.ragas.io/en/stable/)（RAG 评测指标最标准实现）
- [Promptfoo](https://www.promptfoo.dev/docs/intro/)（提示词回归 + 红队）
- [Langfuse](https://langfuse.com/docs)（调用链追踪、token 成本，可自部署）
- [EvalScope](https://github.com/modelscope/evalscope)（中文友好，一条命令跑基准 + 压测 TTFT/TPOT）

---

## 四、第 4–6 个月：Agent 与工程化

### Agent 核心

| 概念 | 要理解到什么程度 |
| --- | --- |
| ReAct 循环 | 能徒手写出「思考→调工具→观察→再思考」的循环，不依赖框架 |
| Plan-and-Execute | 知道它适合长任务、不适合需要频繁纠偏的任务 |
| 工具 Schema 设计 | 参数尽量扁平、给模型明确的「什么时候该用/不该用」 |
| 错误处理 | **不是 `throw`，而是返回模型能自救的提示**（迁移率仅 20%） |
| 幂等与重试 | 工具调用必须幂等，否则重试会造成副作用 |
| 权限与安全 | 高危操作要人工确认（HITL）、要有审计日志 |
| 状态持久化 | Checkpoint：长任务中断后能续跑 |

**关于「多智能体」**：2026 年企业真实落地的比例很低，多数是 demo。Gartner 预测超 60% 的早期 Agent 编排实施达不到预期。**先把单 Agent 做扎实，别一上来就上多智能体。**

**MCP（Model Context Protocol）**：值得学。Spring AI 与 LangChain4j 都已接入，是 Java 侧 Agent 的必修协议。
- [MCP 官方文档](https://modelcontextprotocol.io/)
- [MCP 中文社区镜像](https://modelcontextprotocol.info/zh-cn/docs/)

**动手**：把 GmScript 的 Lua API 包装成一个 MCP Server，让外部 AI 客户端能调用你的脚本引擎。这是个很出彩的项目——它同时展示协议理解、Java 服务化、以及你对自己项目的深度掌握。

### 生产化能力（Java 背景的护城河）

这些是 Python 转行者普遍欠缺、而你已经会的：熔断降级、超时重试、限流、审计、灰度、可观测。
**面试时要主动讲这些**，而不是只讲 prompt 怎么写。

---

## 五、第 7–12 个月：往平台/网关走（差异化区）

| 方向 | 关键技能 | 为什么适合你 |
| --- | --- | --- |
| AI 网关 | 模型路由、Token 计费、限流、多租户、审计 | 本质是 API Gateway + 计量，你熟 |
| AI 平台 | 模型管理、Prompt 版本化、评测平台、GPU 调度 | 分布式 + K8s 纵深 |
| 成本治理 | Token 归因、缓存（前缀缓存）、模型分级路由 | 企业最痛的点，能直接省钱 |

**动手**：给 GmScript 加一个「多模型路由」层——按任务类型/成本/延迟自动选模型，并统计各模型消耗。这就是一个微型 AI 网关。

---

## 六、微调：现在别做，但要知道边界

2026 年的共识很清晰（[决策框架](https://sivaro.in/articles/fine-tune-large-language-model-with-limited-gpu-the-no-bs/)）：

| 需求 | 该用什么 |
| --- | --- |
| 注入**事实性知识**（公司文档、你的项目资料） | **RAG** |
| 改变**行为**（语气、输出格式、推理结构） | 微调 |
| 让模型学会**全新技能** | 微调（只改 prompt 做不到） |

**给你 12GB 显存的现实判断**：

- 7B 做 QLoRA 约需 6GB，**能跑**；14B 约需 12GB，是**临界值**，很紧。
- 但对转行者来说，**微调的投资回报率远低于 RAG + 评测**。
  面试时问「你做过微调吗」远少于问「你怎么保证回答质量」。
- **顺序**：提示工程 → RAG → （真的解决不了）才考虑 LoRA 微调。

**如果将来真要试**（跑通一次即可，别陷进去）：
- [Unsloth](https://github.com/unslothai/unsloth)（12GB 上 7B QLoRA 的现实选择，[中文文档](https://unsloth.ai/docs/zh)）
- [ms-swift](https://github.com/modelscope/ms-swift)（魔搭，国内网络友好，单卡十几分钟跑通 Qwen3-4B LoRA）
- [LLaMA-Factory](https://github.com/hiyouga/LLaMA-Factory)（零代码 WebUI 起步）

> ⚠️ **RTX 50 系（sm_120）必踩的坑**：微调要用 `bitsandbytes` 的 **CUDA 12.8–12.9 构建**，
> 旧 wheel 直接报错。这是最容易卡住的一步，别在环境上浪费一周。

---

## 七、明确不用学的（省下你的时间）

| 别碰 | 原因 |
| --- | --- |
| 从零训练大模型 | 岗位仅占 8.7%，92% 要硕士以上，且预训练需求收缩 30–40% |
| 手写 Transformer / 反向传播 | 除非你想做算法岗。理解概念即可，不必能手推 |
| **GraphRAG（现阶段）** | 每查询喂 9,770 tokens vs 朴素 RAG 的 3,000，三倍成本；中文实测因图谱覆盖率不足反而落后 |
| LangChain 0.x 的 `initialize_agent` / `AgentExecutor` 写法 | 已迁到 1.x + LangGraph，旧写法不是推荐路径 |
| OpenAI Evals 搭评测 | `v1/prompts` 计划 2026-11-30 关停，官方推荐迁 Promptfoo |
| 提示词工程师岗位技能树 | 岗位已消亡，能力已融入日常开发 |
| 一上来就搞多智能体 | Gartner 预测超 60% 早期 Agent 编排达不到预期；先做扎实单 Agent |
| 追每一个新框架 | 框架半年一换，**原理和评测能力不换** |

---

## 八、技能分级清单（要掌握什么，一目了然）

### 必备（不会就问不倒，必须能动手）

**模型与提示**
- Token / 上下文窗口 / 截断策略
- temperature / top_p / top_k / repeat_penalty 的实际影响
- System prompt / Few-shot / CoT 的适用与误用
- 结构化输出（JSON Schema 约束 + 校验 + 重试）
- 流式输出（SSE）与取消语义

**RAG（转行核心）**
- 文档解析（PDF/Word/MD，表格与代码块保真）
- 分块策略（固定 / 语义 / 递归 + overlap）
- Embedding（维度、归一化、批量）
- 向量库（pgvector 或 Milvus，索引选型）
- 混合检索（dense + BM25 + RRF）
- 重排序（cross-encoder）
- 上下文拼装与引用溯源

**Agent**
- ReAct 循环（能手写，不靠框架）
- Tool Calling（Schema 设计、多轮上限、幂等）
- 错误处理：返回模型能自救的提示
- 状态持久化（Checkpoint）

**评测与工程（差异化关键）**
- 评测集构建（覆盖正常/边界/对抗）
- LLM-as-Judge 及其三大陷阱
- 回归测试与指标（recall@k、faithfulness）
- 可观测：token / 延迟 / 成本归因
- 熔断降级、超时重试、限流

**框架**
- Spring AI（ChatClient / Advisor / Tool Calling / MCP）
- LangChain4j（能看懂、能对比）
- MCP 协议

### 加分（有就更好，面试亮点）

- 多模型路由与成本优化（前缀缓存、模型分级）
- 权限过滤与数据脱敏（检索前过滤）
- 多租户与审计日志
- A/B 测试与灰度发布
- LoRA / QLoRA 跑通一次（知道边界即可）
- Spring AI Alibaba Graph（多智能体编排）
- 中文 embedding / reranker 选型经验（BGE-M3 等）

### 可以不学（省下时间）

- 从零训练模型、预训练
- 手写 Transformer / 反向传播
- GraphRAG（现阶段性价比低）
- 多智能体框架的深度定制
- 每个新框架都追一遍
- 提示词工程作为独立技能树

---

## 九、你的差异化优势（面试时这么讲）

1. **存量系统接入能力**：企业是把模型接进现有 Java 系统，不是重写。你懂鉴权、事务、MQ、部署。
2. **Agent 的生产化外壳**：权限校验、风控二次确认、幂等键、熔断降级、审计日志——你的长板。
3. **强类型治理**：把概率输出落成 Java 对象（`entity(Class)`），把 Tool Schema 当 DTO 治理，把 Prompt 当接口契约。
4. **你有一个真实的、能跑的系统**：不是跟着教程敲的 demo。33 个单测、真机注入验证、SSE 流式、可取消——这些细节面试官一问就知道深浅。

**要避免的减分项**：只会说「调 API、写 Prompt」；张口就说「我得转 Python 去学训练」；系统没有降级兜底；不聊权限/脱敏/审计；把 demo 跑通当项目上线。

---

## 十、时间预期（现实版）

| 节点 | 能达到什么 | 注意 |
| --- | --- | --- |
| 3 个月 | 有可投递的项目，但**不保证拿 offer** | 裸转（无项目无评测）几乎必被筛 |
| 6 个月 | **主要求职窗口**，可投银行/券商/国央企 AI 应用岗 | 有 3 年+ Java 经验者与 JD 年限要求天然匹配 |
| 12 个月 | 可争 Agent 工程师 / AI 平台 / 网关岗 | 吃平台工程纵深，最难被替代 |

**窗口期判断**：应用层岗位仍在放量，但同质化供给也在涌入。**约 12–18 个月后**，「会调模型」将不再是差异化，只有「能把模型做成可交付生产系统」的人留存。所以：**现在开始，6 个月内必须有一个带评测的真实项目。**

---

## 十一、本机环境备忘

- **12GB VRAM 的现实边界**：7B Q4 舒适，14B Q4 是临界（约 12GB），再大必须量化或 CPU offload。
- **RTX 50 系（sm_120）的坑**：微调要用 `bitsandbytes` 的 **CUDA 12.8–12.9 构建**，旧 wheel 直接报错。这是最容易卡住的一步。
- **微调该不该做**：先别急。用 RAG + 提示工程解决不了再考虑。真要试，用 Unsloth（12GB 上 7B QLoRA 约 6GB）或 ms-swift，在 7B 上跑通一次即可，别陷进去。
- 本机已装：`qwen3:8b`、`qwen2.5-coder:7b`、`gemma3:4b`（视觉）、`nomic-embed-text`（向量）。
