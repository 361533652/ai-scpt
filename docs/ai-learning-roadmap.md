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

#### ⚠️ 安全前提：你当前版本有已知 CVE

我核实了 Spring 官方安全公告：**CVE-2026-59318** 影响
`Spring AI 1.0.0–1.0.9 / 1.1.0–1.1.8 / 2.0.0`（[官方公告](https://spring.io/security/cve-2026-59318/)）。

- **漏洞性质**：tool calling 时，请求级工具列表只是「告知模型」，派发时没有完全强制校验。
  模型可能通过 prompt injection 调用**当前请求未声明**的工具，导致越权。
- **修复版本**：`2.0.1`（OSS 可用）；`1.1.9` 和 `1.0.10` **只对企业支持开放**。

**你的处境**：GmScript 用的是 **1.1.8**，正好在受影响范围内。
但我检查了代码——**你当前没有暴露任何工具**（`@Tool` / `ToolCallback` 命中为 0），
所以**实际风险暂时为零**。

**这件事的真正意义**：它把「什么时候必须升级」从「看心情」变成了硬约束。
**你一旦开始做 Agent（第 4–6 个月），就必须在 2.0.1+ 上做，不能在 1.1.8 上做。**
因为那时你才会真的暴露工具，而这个漏洞的触发条件正是「暴露了工具」。

> 这也是个很好的面试素材：能说清「我知道我的依赖版本有什么 CVE、为什么现在不受影响、
> 什么时候必须升」——比背八股强得多。

#### 另一个硬事实：1.x 的 OSS 支持已经结束

Spring AI **1.0.x / 1.1.x 的 OSS 支持在 2026-06 已终止**，后续只有企业支持。
也就是说，你继续留在 1.1.8 意味着：**没有新功能、安全修复要付费**。

**所以修正后的建议**：

| 时间点 | 做什么 |
| --- | --- |
| 现在（第 1–8 周） | **可以**留在 1.1.8 跑通 RAG——因为你没暴露工具，且不引入新依赖 |
| 开始做 Agent 前 | **必须**升到 2.0.1+（安全硬要求，不是偏好） |
| 有余力时 | 升到 2.1.0-RC1 之外的稳定版，跟住生态 |

**动手**：把 GmScript 的 `OllamaAiGateway` / `OpenAiCompatAiGateway` 迁到 Spring AI 2.x。
**这是本路线里最有价值的一次练习**——因为它有真实约束（流式要保留、thinking 要分帧、要能取消），
比跟着教程敲 demo 难得多，也真实得多。

| 2.x 要掌握的核心变化 | 具体内容 |
| --- | --- |
| **ChatClient 成为主 API** | `prompt()` / `advisors()` / `entity()` / `stream()`；ChatModel 退为底层构件 |
| **工具调用循环提到 Advisor 链** | `ToolCallingAdvisor` 自动注册，1.x 里每个模型各自私有的 tool loop 被移除。这是 2.0 最重要的架构变化 |
| **StructuredOutputValidationAdvisor** | 结构化输出校验失败时**自动自我纠正**，不用你自己写重试 |
| **ToolSearchToolCallingAdvisor** | 工具数量上百时的渐进式披露，官方称省 34%–64% token |
| **Options 全面重构** | 默认值统一定义在 options 层；options 不可变、用 builder 创建；配置属性去掉了 `.options` 段 |
| **MCP 一等公民** | `@McpTool` / `@McpResource` / `@McpPrompt` 注解即可暴露服务；**Streamable HTTP 取代 SSE 成为默认传输** |
| 空安全 | 全量 JSpecify 注解 |
| 版本基线 | **Java 17**（建议 21）；Spring Boot 4.0/4.1 + Spring Framework 7.0 |

#### 2.x 已知的坑（升级时会踩到）

- **1.x → 2.0 破坏性极强**：`toolNames()` / Function bean / `SpringBeanToolCallbackResolver` /
  `internalToolExecutionEnabled` 全删；Vertex AI chat 移除；ZhipuAI 迁出主仓库。
- **原生结构化输出 + 工具同用会重复调用**（[issue #6327](https://github.com/spring-projects/spring-ai/issues/6327)）。
- **2.0.1 把 OpenAI 工具调用 strict mode 默认改成 false**，带可选参数的工具会被 400。
- **ChatMemory 在 2.1 起 deprecated、3.0 删除**，替代为事件溯源 session memory
  （但 Cassandra/Neo4j/Mongo/Redis 的 session 版还没出）。
- **MCP 客户端工具列表有缓存**，server 热更工具后可能长期不感知（[issue #4928](https://github.com/spring-projects/spring-ai/issues/4928)）。
- **同步阻塞调 LLM 会打满 Tomcat 线程池**；`@Transactional` 里调 LLM 会耗尽连接池。
  ——这条对你有用：你的项目已经开了虚拟线程，但要注意别在事务里等模型。

**资源**：
- [Spring AI 2.0.0 GA 官方公告](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now)（英，**一手信息，必读**）
- [CVE-2026-59318 官方公告](https://spring.io/security/cve-2026-59318/)（英，必读）
- [Spring AI 2.1.0-RC1 公告](https://spring.io/blog/2026/10/23/spring-ai-2-1-0-RC1-available-now)
- [Spring AI 官方参考文档](https://docs.spring.io/spring-ai/reference/index.html)（英，权威）
- [Spring AI Alibaba](https://java2ai.com/)（中文，但注意：多个中文分析称其半年无正式版，用前先看 GitHub Releases 核实）
- [LangChain4j 官方文档](https://docs.langchain4j.dev/)（英，另一条路线，对比着看）

> ❗**避坑**：网上大量「Spring AI 1.0 GA 重磅发布」的中文博客，示例代码在 2.x 上多数跑不通。
> 凡版本敏感 API，**以英文官方 release notes 为准**。

### Spring AI vs LangChain4j：该选哪个

这不是「谁更好」，而是分工不同。除功能覆盖外，还有一份**故障场景实测**值得看：

arXiv:2610.25480（2026-10）用 15 个故障场景 × 同步/流式 × 三轮测试：

| 框架 | 通过数 |
| --- | --- |
| **Spring AI 2.0.1** | **72/77**（显式配置后 76/83） |
| LangChain4j 1.21.0 | 47/77（51/83） |

两者都把「缺终止符的流」当完整答案返回——**这是你写流式代码时要自己兜的**。

功能覆盖对比（[三框架同台对比](https://github.com/java-ai-in-action/framework-compare)）：

| 维度 | 更强的一方 | 说明 |
| --- | --- | --- |
| 模型集成数量 | **LangChain4j** | 30+ 模型开箱即用 |
| 向量库支持 | **LangChain4j** | 覆盖最广，两者差异 <10% |
| MCP 支持 | **Spring AI 2.0** | `@McpTool` 注解即可暴露 |
| 可观测性 | **Spring AI** | 与 Micrometer / Actuator 天然一致 |
| 故障场景健壮性 | **Spring AI 2.0.1** | 72/77 vs 47/77 |

> 注：网上流传的「LangChain4j 68% vs Spring AI 52%」采用率数据，来源为 JetBrains 2025 Q1 调查的
> 中文二手引用，**未找到一手英文出处，谨慎引用**。

**给你的结论**：
- **主力用 Spring AI** —— 你的项目已经是 Spring Boot，接入成本最低，MCP 支持最好。
- **LangChain4j 要看得懂** —— 面试常问对比，且很多企业（尤其非 Spring 栈）在用。
- 两者可以共存，但**别在同一个项目里混用**，抽象层打架。

### 第 5–8 周：RAG 全链路（这是转行核心考点）

| 环节 | 必须掌握 | 容易踩的坑 |
| --- | --- | --- |
| 文档解析 | PDF/Word/Markdown 抽取，表格与代码块保真 | 直接 `pdftotext` 会把表格拍平 |
| 分块 | **递归结构感知分块**（按标题/列表边界切）+ 元数据设计 | 中文按字符切会切碎词 |
| 嵌入 | 维度、归一化、批量；**注意任务前缀与上下文长度** | 见下方两个实测坑 |
| 向量库 | **pgvector（首选，运维成本最低）**；或 Milvus / Qdrant | 索引类型选错，数据量一大就慢 |
| 混合检索 | dense + BM25 + RRF 融合 | 只做向量检索，专有名词命中率极低 |
| 重排序 | cross-encoder（bge-reranker 等）top-50→top-5 | 把 rerank 当万能药，忽略召回本来就没命中 |
| 查询改写 | HyDE、多查询展开 | 过度改写反而跑偏 |
| 权限过滤 | 检索前按用户权限过滤 | 检索后才过滤 = 数据泄露 |

#### ⚠️ 你本机 `nomic-embed-text` 的两个实测坑

我直接在你的 Ollama 上验证过，这两条会直接影响检索效果：

**坑 1：必须加任务前缀。** 这个模型用 `search_document:` / `search_query:` 前缀训练。
实测同一句话加不加前缀，向量确实不同（前 3 维 `[-0.0146, 0.0401, -0.1266]` vs `[-0.016, 0.029, -0.1276]`）。
**入库文档用 `search_document:`，查询用 `search_query:`**，混用会掉召回率。

**坑 2：上下文只有 2048 token。** `nomic-bert.context_length = 2048`。
实测喂 6000 字符仍返回 768 维向量，但**超出部分被静默截断**——不会报错，
你只会发现「明明写在文档里的内容检索不到」。分块大小要控制在 2048 token 以内。

> 补充：有资料说「Ollama 默认 num_ctx 只有 2048」，但在你的机器上 `api/show` 显示
> `num_ctx` 已被设为 8192。真正的硬限制是模型原生 2048，别指望调 num_ctx 突破。
>
> 想换更强的嵌入模型，本地可选 `qwen3-embedding:0.6b`（639MB、多语言、32K 上下文）；
> 中文混合检索用 `bge-m3`（dense+sparse+多向量三合一）。

#### RAG 的形态演进：从线性到循环

2026 年的默认形态已不只是「切块→嵌入→top-k→塞 prompt」这条直线，
而是 **Agentic RAG**：检索变成 agent 可调用的工具，带查询改写、自我纠错、多跳回退。

**但别急着上。** 简单事实型问答走传统检索延迟更低、成本更可控。
Agentic RAG 只在多跳/需反复求证的问题上有优势，且**必须加步数上限 + 单步检索评分**，
否则成本失控。

**顺序**：先把线性 RAG 做到可测量（混合检索 + 重排），再考虑引入 agentic 循环。
跳过第一步直接上 agentic，你根本不知道好在哪。

#### 重排序是性价比最高的一环

如果只优化一处，选它。cross-encoder 做 top-50 → top-5 精排，
有报告称 precision 提升约 25%，延迟只增加 5–30ms/文档。

**Java 侧怎么跑 reranker**：两种路线，按需选。
- 调 Ollama / 远程 API（最省事，适合起步）
- [ONNX Runtime Java](https://mvnrepository.com/artifact/com.microsoft.onnxruntime/onnxruntime_gpu)（1.31.0，活跃）
  加载 cross-encoder 模型在进程内跑——**延迟更低、不依赖外部服务**，
  这是 Java 侧做 embedding/rerank 的主流做法。

> ⚠️ 别用 DJL 走这条路：它在 Windows 上有 `StackBatchifier.batchify` 的
> JVM Access Violation 崩溃（[djl#3859](https://github.com/deepjavalibrary/djl/issues/3859)）。
> 另外 Spring AI 的 `TransformersEmbeddingModel` 钉住了 ONNX Runtime 1.21.1 + DJL 0.36.0
> （落后于 1.31.0/0.39.0），还会尝试联网下载 PyTorch native 库——
> 生产环境要锁版本或自带模型。

#### 向量库怎么选

- **pgvector 首选**：运维成本最低，和你的 Spring Boot + PostgreSQL 天然同栈。
- Milvus / Qdrant：数据量大、需要独立扩缩容时再上。
- 别在新项目里单用 Cassandra/Neo4j 当向量库；Chroma 的 Java 生态较弱。

**动手（这条线直接产出可面试的项目）**：给 GmScript 加「私有知识库问答」——
把你自己写的 `docs/*.md` 和 Lua API 文档喂进去，问「怎么给脚本绑热键」能答对且给出处。
`nomic-embed-text` 你已经装了但代码里没用，这次正好用上。

**做到什么程度算掌握**：能对着一个答错的问题，说清楚是「没召回」还是「召回了但没用好」，
并能用**改动前后的评测数字**证明改进有效。只会调 `Retriever` 不算会 RAG。

#### 关于 GraphRAG：现在别碰

2026 年有多组实测数据，结论一致：

- 微软的对比里 GraphRAG 在「全局性、综合性」问题上胜率 72–83%，Recall@5 从 73.4% 提到 87.8%
  （[Persistent 分析](https://www.persistent.com/blogs/when-does-graphrag-outperform-traditional-rag/)）。
- **但代价是每查询喂给模型 9,770 tokens，而朴素 RAG 只要 3,000 左右**——
  三倍成本换多跳问题的提升（[FutureAGI 实测](https://futureagi.com/blog/graphrag-vs-rag/)）。
- IEEE 实测：对**浅层事实问答收益可忽略**，平均延迟 2.3×；
  另一份对比给出单次检索 61 万 token 对 LightRAG 约 100 token
  （[IEEE 11579073](https://ieeexplore.ieee.org/document/11579073)）。
- 中文实测：KG-GraphRAG 因**图谱覆盖率只有 65%** 而全面落后，「实体缺失是致命伤」
  （[腾讯云社区实测](https://cloud.tencent.com.cn/developer/article/2740285)）。

**给你的判断**：GraphRAG 只在**知识本身是关系型的**场景才值得（组织架构、供应链、法条引用）。
你的知识库问答不属此类。**先把混合检索做扎实**，这比上 GraphRAG 收益高得多。
面试时能说清「我评估过 GraphRAG，但我的场景不需要，因为 X」反而是加分项。

**资源**：
- [RAG From Zero](https://github.com/MisterBooo/rag-from-zero)（中文，手写切分/BM25/重排，不用框架，专治「跑通了不知道为啥」）
- [Milvus 中文文档](https://milvus.io/docs/zh)（混合检索与 rerank 有专章）
- [RAGFlow 文档](https://ragflow.io/docs/dev/)（看工业级文档解析怎么做）
- [FlagEmbedding / BGE-M3](https://github.com/FlagOpen/FlagEmbedding)（中文场景 embedding+reranker 默认选择）
- [Production RAG 2026 指南](https://www.premai.io/blog/building-production-rag-architecture-chunking-evaluation-monitoring-2026-guide/)（英，工程视角完整）

### 第 9–12 周：评测 + 可观测（**这是你和 90% 转行者拉开差距的地方**）

传统单测断言不了概率输出。这是 Java 工程师迁移率最低的能力（约 10%），也是招聘方最看重的。

**为什么招聘方这么看重？** 两个硬理由：

1. agent 的失败往往是「**流程错了**」而不是「答案错了」——越权调用、数据泄漏、
   未授权承诺，传统断言根本抓不到。
2. **EU AI Act 的 GPAI 条款 2026-08-02 已生效**，罚则最高全球营收 3%，
   要求的是**可举证的记录**，不是「我跑过一次 eval」。

| 要掌握 | 具体内容 |
| --- | --- |
| 黄金评测集 | 50–100 条「问题 + 期望要点」，**从你实际答错的 query 里长出来**，按失败模式分类打标、版本化 |
| LLM-as-Judge | 用强模型打分，**必须知道它的陷阱**（见下） |
| 回归测试 | PR 触发全量 golden set；改 prompt / 换模型，指标不能退化 |
| 指标 | 检索侧 recall@k / MRR / nDCG@10；生成侧 faithfulness / answer relevancy |
| 可观测 | 每次调用的 token 消耗、延迟（p95）、成本归因、失败率 |

#### LLM-as-Judge 的陷阱有硬数据，不是玄学

| 陷阱 | 实测表现 | 能不能靠 prompt 解决 |
| --- | --- | --- |
| **位置偏置** | 源于自回归解码，A/B 顺序影响结果 | **几乎无效**——rubric 里写「忽略位置」没用，只能交换顺序取两次结果 |
| **长度偏好** | 越长的答案分越高 | 部分可缓解：控制长度、明确「简洁也加分」 |
| **自我偏好** | 同家族模型互评有 **10–25% 系统性偏置** | 不能：换不同家族的模型当 judge |

**硬门槛**：judge 必须先与人工标注对齐到 **85–90% 一致率**才能用。
对齐不到，你的评测数字就是自欺欺人。

**有 ground truth 的项优先用确定性断言**，别什么都丢给 LLM 打分。

**动手**：给 GmScript 的知识库问答建一套评测集 + 一个 `mvn test` 能跑的回归测试。
这一步做完，你的项目从「demo」变成「有质量保障的系统」——面试时这比多两个功能管用得多。

**资源（已按 2026-10 现状修正）**：

- ❌ **RAGAS 不要再用了**。我核实了它的仓库：227 个未合并 PR，
  issue #3004 直问「这项目还在维护吗」，作者承认**当前 release 和 main 都跑不通**。
  别把评测体系绑在它上面。
- ✅ **Java 原生路径**：
  - [spring-ai-ragas](https://github.com/ai-qa-solutions/spring-ai-ragas)（Maven Central `io.github.ai-qa-solutions`，
    含 Spring Boot starter + Allure 报告）——Java 侧少见的开箱评测
  - Spring AI 自带 `RelevancyEvaluator`，够做基础相关性评估
- ✅ [Promptfoo](https://www.promptfoo.dev/docs/intro/)（语言无关 CLI，测 Java 服务就是打 HTTP 接口；
  **2026-03 已被 OpenAI 收购**，仍开源。注意：Gitee 上的 `promptfoo-java` 分叉可信度未核实，别用）
- ✅ [Langfuse](https://langfuse.com/docs)（MIT、OTel 原生，可自部署；**2026-01 被 ClickHouse 收购**，
  产品仍在。官方有 [Spring AI OTLP 集成指南](https://langfuse.com/integrations/frameworks/spring-ai)）
- ✅ [Arize Phoenix](https://phoenix.arize.com/)（备选，OTel 路线）
- ✅ [EvalScope](https://github.com/modelscope/evalscope)（中文友好，一条命令跑基准 + 压测 TTFT/TPOT）

> ⚠️ **OTel 的 `gen_ai.*` 语义约定到 2026-05 仍是 Development、未 Stable**，
> 且逐消息内容属性已改为默认不采集。**别把字段名写死在业务代码里**，否则规范一变就全挂。

---

## 四、第 4–6 个月：Agent 与工程化

### Agent 核心

| 概念 | 要理解到什么程度 |
| --- | --- |
| **原生 tool calling 循环** | 用 JSON Schema 声明工具、走原生 function calling，**不要手写 `Thought:/Action:` 文本解析** |
| 循环控制 | 步数上限、超时、失败回退——没有这三样，agent 会烧钱且卡死 |
| Plan-and-Execute | 只在**步骤能预先列全**时成立；中间失败即脆断，生产要 plan-execute-replan |
| 工具 Schema 设计 | 参数尽量扁平、明确「什么时候该用/不该用」 |
| 错误处理 | **带内返回错误**而非抛异常，并标注可重试性（迁移率仅 20%） |
| 幂等与回查 | 写操作必须带幂等键 + 回查接口，否则重试造成副作用 |
| 权限与安全 | 高危操作要人工确认（HITL）、要有审计日志；工具访问本身就是安全边界 |
| 状态持久化 | Checkpoint：长任务中断后能续跑 |

#### ⚠️ 重要修正：不要手写 ReAct 文本解析

我最初把「徒手写 ReAct 循环」列成了必学项，**这条需要改**：

- IBM watsonx 已明确标注 ReAct 自 **2026-07-15 起废弃**（[IBM Docs](https://www.ibm.com/docs/vi/watsonx/watson-orchestrate/base?topic=agents-choosing-style-of-agent)）。
- 社区也在移除「解析 `Thought:/Action:` 文本」的回退路径，要求走原生 tool calling。

**要学的是「循环控制 + 工具契约」，不是「解析模型输出的文本格式」。**
理解 ReAct 的**思想**（推理与行动交替）仍然有价值，但实现上直接用原生 function calling。

**关于「多智能体」**：默认架构下是成本灾难——Anthropic 数据是单 agent 消耗 4 倍聊天 token，
多 agent 系统是它的 **15 倍**（[Forrester](https://www.forrester.com/blogs/your-ai-bill-is-a-context-problem/)）。
只在**子任务可并行、且各自需要独立长上下文**时才是真需求，否则就是 demo 玩具。
**先把单 Agent 做扎实。**

**MCP（Model Context Protocol）**：已成事实标准，各大厂原生支持，41% 软件组织已在生产运行。
Spring AI 与 LangChain4j 都已接入，是 Java 侧 Agent 的必修协议。
- [MCP 官方文档](https://modelcontextprotocol.io/)
- [MCP 中文社区镜像](https://modelcontextprotocol.info/zh-cn/docs/)

**动手**：把 GmScript 的 Lua API 包装成一个 MCP Server，让外部 AI 客户端能调用你的脚本引擎。
这是个很出彩的项目——它同时展示协议理解、Java 服务化、以及你对自己项目的深度掌握。

> 真实数据供参考：**77%–89% 的 agent 项目过不了 pilot→production**
> （Fiddler 88%、Hendricks 89%、CMU 实测办公任务失败率约 70%）。
> 根因通常不是模型能力，而是**缺少可举证的证据**——这正是下一节「评测」要解决的。

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
| **手写 ReAct 文本解析**（`Thought:/Action:`） | IBM 已标注 ReAct 2026-07-15 废弃；原生 tool calling 普及，解析文本是纯技术债 |
| **GraphRAG 作默认方案** | 浅层问答零收益；延迟 2.3×、单次检索 61 万 token vs LightRAG 约 100 token |
| **手写向量索引 / 自建向量库** | pgvector、Qdrant 等已成熟，没必要自己造 HNSW |
| **torchtune** | 官方 README 已声明停止维护，新项目别选 |
| 深绑 LangChain / LlamaIndex 专有 DSL | 抽象随版本漂移；概念可迁移，DSL 不值得背 |
| 个人规模全参微调 / DeepSpeed ZeRO / Megatron | 单卡无意义，属平台/训练团队范畴 |
| LangChain 0.x 的 `initialize_agent` / `AgentExecutor` 写法 | 已迁到 1.x + LangGraph，旧写法不是推荐路径 |
| OpenAI Evals 搭评测 | `v1/prompts` 计划 2026-11-30 关停，官方推荐迁 Promptfoo |
| 提示词工程师岗位技能树 | 岗位已消亡，能力已融入日常开发 |
| **刷 MTEB 榜换嵌入模型** | 换模型须全量重嵌；同维度 ≠ 同向量空间，迁移成本远超收益 |
| **all-minilm 上生产** | 2022 年的 SOTA，现在只适合原型或边缘设备 |
| 自训嵌入模型 | 投入产出比极低，招聘不看 |
| 一上来就搞多智能体 | 15× token 成本（Anthropic 数据）；只在并行子任务时才值 |
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
- **递归结构感知分块** + 元数据设计
- Embedding（维度、归一化、批量、**任务前缀**、上下文长度上限）
- 向量库（pgvector 或 Milvus，索引选型）
- 混合检索（dense + BM25 + RRF）
- 重排序（cross-encoder，top-50→top-5）
- 检索评测（recall@k / MRR / nDCG@10）
- 上下文拼装与引用溯源

**Agent**
- **原生 tool calling**（JSON Schema、带内错误、幂等键、可重试性分类）
- 循环控制（步数上限、超时、失败回退）
- 状态持久化（Checkpoint）
- 上下文工程（预算分配、压缩、记忆分层）

**评测与工程（差异化关键）**
- 评测集构建（**从真实失败里长出来**，分类打标、版本化）
- LLM-as-Judge 及校准（**与人工对齐 ≥85%** 才可用）
- 回归测试与指标（recall@k、faithfulness）
- 可观测：全链路 trace / token / 延迟 p95 / 成本归因
- 熔断降级、超时重试、限流

**框架**
- Spring AI（ChatClient / Advisor / Tool Calling / MCP）
- LangChain4j（能看懂、能对比）
- MCP 协议（读写与安全边界）

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

## 十、项目验收标准（做到什么程度算「会」）

不要用「我做了个 RAG 项目」这种说法，它没有信息量。下面三档，**每档都有可验收的硬指标**。

### 项目 A：检索系统（唯一必做）

**规模**：自己的 500–5000 篇语料（你的 `docs/` + Lua API 文档 + 你读的技术文章）。

**必须能拿出来**：

- [ ] **改动前后的评测数字**，逐项列：混合检索提升多少、加重排提升多少、换分块策略提升多少
- [ ] 评测集**从你实际答错的 query 里攒出来**，不是编的
- [ ] 能回答「这个答错的问题，是没召回还是召回了没用对」

**不合格的样子**：只调了 `Retriever`，说不出任何一个数字。

### 项目 B：单 Agent 干一件真事

**规模**：5–8 个工具，**至少一个写操作**。

**必须能拿出来**：

- [ ] 写工具带**幂等键 + 回查接口**
- [ ] 错误**带内返回**并标注可重试性（不是抛异常）
- [ ] Langfuse 能看到**每步 trace、单次任务成本、p95 延迟**
- [ ] **50+ 回归用例在 CI 跑**
- [ ] judge 与自标注一致率 **≥85%**

**不合格的样子**：多智能体聊天玩具；只能读不能写；出错了直接抛异常。

> 提醒：**77%–89% 的 agent 项目过不了 pilot→production**，根因是缺可举证证据。
> 上面这五条就是「可举证」的具体形态。

### 项目 C：微调（可选，须能证明必要性）

- [ ] 先用 prompt + RAG 建立**基线数字**
- [ ] 再用 1–2K 条精选样本在 12GB 卡上 QLoRA 微调 7–8B
- [ ] 对比基线，给出提升数字

**做不出超过基线的数字，就把这次当「我验证了不该微调」的结论**——这本身就是掌握了边界，
面试时讲这个结论比硬吹「我做了微调」更显水平。

---

## 十一、时间预期（现实版）

| 节点 | 能达到什么 | 注意 |
| --- | --- | --- |
| 3 个月 | 有可投递的项目，但**不保证拿 offer** | 裸转（无项目无评测）几乎必被筛 |
| 6 个月 | **主要求职窗口**，可投银行/券商/国央企 AI 应用岗 | 有 3 年+ Java 经验者与 JD 年限要求天然匹配 |
| 12 个月 | 可争 Agent 工程师 / AI 平台 / 网关岗 | 吃平台工程纵深，最难被替代 |

**窗口期判断**：应用层岗位仍在放量，但同质化供给也在涌入。**约 12–18 个月后**，「会调模型」将不再是差异化，只有「能把模型做成可交付生产系统」的人留存。所以：**现在开始，6 个月内必须有一个带评测的真实项目。**

---

## 十二、本机环境备忘

### 12GB VRAM 的量化边界（实测口径）

| 模型规模 | 推荐量化 | 显存占用 | 可用性 |
| --- | --- | --- | --- |
| 7B–8B | **Q5_K_M** | 约 6–7GB | ✅ 最划算，留足 KV cache |
| 7B–8B | Q4_K_M | 约 5GB | ✅ 更省，质量略降 |
| 14B | **Q4_K_M** | 约 9GB | ✅ 极限可用 |
| 14B | Q5_K_M | 约 10.5GB | ❌ **剩不下 KV cache，基本不可用** |

**结论**：14B 的极限就是 Q4_K_M。别贪 Q5，上下文一长就爆。
你现在的 `qwen3:8b`（Q4_K_M，5.2GB）属于舒适区，可以放心用。

### 本地推理工具怎么选

| 工具 | 定位 | 你该用吗 |
| --- | --- | --- |
| **Ollama** | 一键跑模型 + OpenAI 兼容接口 | ✅ **主力**，你已经在用 |
| **LM Studio** | GUI 舒适，0.4.x 有 tensor parallelism + headless + 原生 MCP | ✅ 想图形化调参时用 |
| llama.cpp | 极致压缩/边缘/CPU，GGUF 1.5–8bit | 需要深度调优时 |
| **vLLM** | 多并发生产服务（paged attention） | ❌ **本地开发别上**，单用户是纯开销，要 4+ 并发才体现优势 |

> 注：Ollama 在 2026-05 删掉了自带的 GGML/llama.cpp backend，改用上游 `llama-server`
> （[PR #16031](https://github.com/ollama/ollama/pull/16031)）。这不影响你使用，但意味着它的行为会更贴近 llama.cpp。

### 其他

- **RTX 50 系（sm_120）的坑**：微调要用 `bitsandbytes` 的 **CUDA 12.8–12.9 构建**，
  旧 wheel 直接报错。这是最容易卡住的一步。
- **微调该不该做**：先别急。RAG + 提示工程解决不了再考虑。
  真要试，用 Unsloth（12GB 上 7B QLoRA 约 6GB）或 ms-swift，跑通一次即可，别陷进去。
- 本机已装：`qwen3:8b`、`qwen2.5-coder:7b`、`gemma3:4b`（视觉）、`nomic-embed-text`（向量）。

---

## 十三、版本红线（2026-10 现状，别踩）

这份清单会过期，但下面这些是**现在就会让你白干**的：

| 别用 | 原因 |
| --- | --- |
| **Spring AI 1.0.x / 1.1.x** | OSS 支持 2026-06 已终止；且落在 CVE-2026-59318 影响范围内 |
| **Spring AI 2.0.0** | 同样受 CVE-2026-59318 影响，**必须 2.0.1+** |
| **MCP 的 SSE transport** | 已弃用 → Streamable HTTP（无状态变体可水平扩展） |
| Spring AI 1.x 的 `toolNames()` / Function bean / `SpringBeanToolCallbackResolver` | 2.0 全删，改用显式 `ToolCallback` / `@Tool` |
| **`ChatMemory` / `MessageWindowChatMemory` / `MessageChatMemoryAdvisor`** | 2.1 deprecated、3.0 删除 → 事件溯源 session memory |
| **LangChain4j 1.19.1** | 官方公告「误发版，不要用」。**若已在 1.19.1，千万别升到 1.19.2**（会静默删掉约 108 个提交的功能），直接去 1.20+ |
| `langchain4j-github-models` | 模块已删除 |
| **RAGAS（Python）** | 维护停滞，当前 release 与 main 都跑不通（[issue #3004](https://github.com/vibrantlabsai/ragas/issues/3004)） |
| **torchtune** | 官方 README 已声明停止维护 |
| Tribuo | 传统 ML，与 GenAI 无关，别碰 |
| 手写 ReAct 文本解析 / 手写向量索引 / 个人规模全参微调 | 见第七节「明确不用学」 |

> **关于 Spring AI Alibaba**：多个 2026 年 9–10 月的中文分析称其半年无正式版
> （最新 v1.1.2.2 / 2026-03-10），项目重心转向 AgentScope Java。
> **我未找到一手官方英文声明**——用之前请自己打开 GitHub Releases 核实，别只看中文博客。
