# 2026 下半年 AI 应用/平台工程师：该学什么、该弃什么

> 调研日期 2026-10-27。结论均附来源；无法核实的单独标注。

## 1. RAG：不是被取代，是"循环化 + 上下文工程"

线性"朴素 RAG"（切块→嵌入→top-k→塞 prompt）在复杂场景已过时。2026 默认形态是 **Agentic RAG**：检索变成被 agent 调用的工具，带查询改rewrite、自我纠错、多跳回退。有评测称 agentic RAG 在多跳基准上高于朴素 RAG 18–25 分，**但前提是加了步数上限和单步检索评分**，否则成本失控（[FutureAGI](https://futureagi.com/glossary/agentic-rag/)）。简单事实型问答仍走传统检索，延迟更低（[AI-Agents-public](https://github.com/vasilyu1983/AI-Agents-public/blob/3424d6f5e94010409da012eeb1cb84aaecec88b3/frameworks/shared-skills/skills/ai-rag/references/agentic-rag-patterns.md)）。

- **分块**：递归结构感知切分是默认（按标题/列表边界切）。语义分块、late chunking、contextual retrieval 属于有收益但非必需的进阶（[You.com](https://you.com/resources/rag-chunking-strategies)）。
- **嵌入**：nomic-embed-text 在 MTEB English v1 为 62.28，仍是 CPU 默认（8930 万次下载）。两个必知坑：必须加 `search_document:` / `search_query:` 前缀，且 Ollama 默认 `num_ctx` 只有 2048 而非原生 8192。本地更优选择是 qwen3-embedding:0.6b（639MB、多语言 64.33、32K 上下文）；有 GPU 上 4b/8b；中文与混合检索用 bge-m3（dense+sparse+多向量三合一）（[morphllm](https://www.morphllm.com/ollama-embedding-models)）。
- **重排序**：性价比最高的一环，必须掌握。cross-encoder（bge-reranker-base 约 5–30ms/文档，Cohere API 100–200ms）做 top-50→top-5 精排，有报告称 precision 提升 25%（[阿里云](https://developer.aliyun.com/article/1772293)、[rerank 横比](https://raw.githubusercontent.com/wangyong9999/lakehouse-wiki/main/docs/compare/rerank-models.md)）。
- **GraphRAG**：被过度炒作的是"默认上 GraphRAG"。IEEE 实测显示它对多跳推理和领域事实性显著提升，但对浅层事实问答收益可忽略，而延迟与成本大幅上升（[IEEE 11579073](https://ieeexplore.ieee.org/document/11579073)）；另一份对比给出平均延迟 2.3 倍、单次检索 61 万 token 对 LightRAG 约 100 token，差距 6000 倍（[阿里云](https://developer.aliyun.com/article/1710174)）。判据：知识本身是关系的（组织架构、供应链、法条引用）才上。
- **上下文工程**已取代"提示工程"成为系统级学科，覆盖检索/工具/记忆/执行四类上下文（[Zenodo](https://zenodo.org/records/22713977)）。长上下文不替代 RAG，二者分工：前者适合单文档全覆盖（[Atlan](https://atlan.com/know/llm-context-window-limitations/)）。

## 2. Agent：确实落地了，但多数死在 pilot→production

- 采用面：Gartner 预测 2026 年底 40% 企业应用集成任务型 agent（2025 年不足 5%）（[Redwerk](https://redwerk.com/blog/langgraph-vs-crewai/)）；财富 1000 中位数已跑 3–5 个生产 agent（[Pronix](https://pronix.ai/resources/reports/state-of-agentic-ai-enterprise-2026)）。
- 但 **77%–89% 的 agent 项目过不了试点到生产**（Fiddler AI 88%、Hendricks 89%、CMU 实测常见办公任务失败率约 70%）（[LayerLens](https://layerlens.ai/blog/why-77-of-ai-agent-projects-never-reach-production)）。项目结构也偏浅：2025–2026Q1 通用辅助场景占 83.7%，专业深度场景仅 16.3%（[36Kr](https://eu.36kr.com/en/p/3958434354986112)）。
- **ReAct 进入废弃期**：IBM watsonx 明确标注 ReAct 自 2026-07-15 起废弃（[IBM Docs](https://www.ibm.com/docs/vi/watsonx/watson-orchestrate/base?topic=agents-choosing-style-of-agent)），社区也在移除"解析 `Thought:/Action:` 文本"的回退路径，要求原生 tool calling（[issue](https://github.com/cedricziel/assistant/issues/10)）。Plan-and-Execute 只在**步骤可预先列全**的任务上成立，中间失败即脆断；生产任务实际需要 plan-execute-replan（[FutureAGI](https://futureagi.com/blog/agent-architecture-patterns-2026/)）。
- **多智能体**：默认架构下是成本灾难——Anthropic 数据是单 agent 消耗 4 倍聊天 token，多 agent 系统是它的 15 倍（Forrester 引用，并提到 ServiceNow 数月烧完整年 Anthropic 编码预算）（[Forrester](https://www.forrester.com/blogs/your-ai-bill-is-a-context-problem/)）。只在**子任务可并行、且各自需要独立长上下文**时才是真需求。
- **工具调用工程**：MCP 已成事实标准，Anthropic/OpenAI/Google/Microsoft/AWS 全部原生支持，41% 软件组织已在生产运行 MCP（[Avaya](https://www.avaya.com/en/blogs/mcp-standard-for-agentic-ai-integration/)、[Hotglue](https://hotglue.com/blog/mcp-ai-agent-integrations-saas)）。要点：错误**带内返回**而非抛异常；每个工具声明变更属性（只读/幂等/非幂等）；写操作必须有幂等键且能回查结果；区分可重试与不可重试错误（[tool-design-for-llms](https://github.com/bcbeidel/toolkit/blob/2b19966eefe1ce7542e3b9a1dc0930f5940e6976/docs/context/tool-design-for-llms.md)、[function-tool-calling](https://github.com/szylover/knowledge-library/blob/37fb169d2cf78f001dea316570709c9f3bf8af91/ai-engineering-book/book/part2_ai_engineering/chapter-05-function-tool-calling.md)）。AWS 强调工具访问本身即安全边界，guardrail 不能替代 IAM（[AWS](https://builder.aws.com/content/3JXE5IYh0BDMKZe3PBa0DLXGXir/what-happens-when-an-ai-agent-gets-aws-access)）。

## 3. 微调边界：12GB 够用，但只该用在"行为"上

核心判据：**知识缺失用 RAG，行为缺失才微调**——语气、格式、拒绝校准、输出结构（[FutureAGI 决策框架](https://futureagi.com/blog/rag-vs-fine-tuning-decision-framework-2026/)、[Progress](https://www.progress.com/blogs/rag-vs-fine-tuning-when-to-use-which)）。

12GB 可行性：Unsloth 官方数据 7B QLoRA 最低 5GB、8B 6GB，但 16-bit LoRA 8B 要 22GB（[Unsloth](https://unsloth.ai/docs/get-started/beginner-start-here/unsloth-requirements)）；LLaMA-Factory 估算 7B QLoRA-4bit 6GB、8bit 10GB（[LLaMA-Factory](https://github.com/hiyouga/LLaMA-Factory)）。即 **12GB 能跑 7–8B QLoRA，跑不了正经 LoRA**。

工具现状：Unsloth（单卡最快最省显存，自称 2× 提速、省显存 70%，属厂商口径未独立核实）、Axolotl / LLaMA-Factory（YAML/WebUI 配置驱动，方法最全）、TRL（HF 生态、DPO/GRPO）、PEFT（底层适配器层）。**torchtune 官方 README 已声明不再维护，新项目别选**（[Aquanode](https://www.aquanode.io/blog/llm-fine-tuning-frameworks)）。

## 4. 评测与可观测：招聘方最看重、转行者最容易漏

两个原因。其一，agent 失败是"错误的流程"而非"错误的答案"，传统评测抓不到越权调用、数据泄漏、未授权承诺。其二，EU AI Act GPAI 条款 2026-08-02 生效，罚则最高全球营收 3%，要求的是可举证记录而非"跑过一次 eval"（[LayerLens](https://layerlens.ai/blog/why-77-of-ai-agent-projects-never-reach-production)）。

必须会的具体项：
- **数据集构建**：从真实生产失败里长出来，按失败模式分类打标、版本化，保持覆盖均衡而非全是 happy path（[Arthur.ai](https://www.arthur.ai/column/regression-test-datasets-ai-agents-production-failures)）。
- **LLM-as-judge 陷阱**：位置偏置源于自回归解码，在 rubric 里写"忽略位置"几乎无效；自我偏好带来 10–25% 系统性偏置；judge 须先与人工标注对齐到 85–90% 一致率才可用（[FutureAGI](https://futureagi.com/blog/evaluating-llm-judge-bias-mitigation-2026/)）。有 ground truth 的项优先确定性断言（[Atlas](https://github.com/Laoujin/Atlas/blob/main/research/2026-06-04-evals-how-do-you-know-your-ai-works-session-blueprint/eval-methodologies-metrics/index.md)）。
- **回归测试**：PR 触发全量 golden set；修 bug 要能重放原失败场景并给出机械可验证的差值。
- **可观测**：Langfuse（MIT、OTel 原生、最广泛采用的开源方案）、Arize Phoenix、LangSmith；Braintrust 走 eval 优先、非 OTel 路线（[morphllm](https://www.morphllm.com/ai-agent-observability-tools)）。成本与 p95 延迟必须进看板。

## 5. 提示工程：技能还在，岗位没了

多方一致：提示/上下文工程已并入 AI engineer、Applied AI、FDE 等岗位描述，不再是独立岗位（[BankInfoSecurity](https://www.bankinfosecurity.net/ai-jobs-whats-actually-sticking-a-30416)、[Scaler](https://www.scaler.com/topics/fde-vs-prompt-engineer/)）。结构化输出的正确做法是 JSON Schema / constrained decoding，**不要再写自由文本解析**（[Dupple](https://dupple.com/blog/what-is-prompt-engineering)）。薪资为自报口径、未交叉核实：junior LLM 工程师 13–18 万美元，中级 18–28 万（[genai.qa](https://genai.qa/blog/hire-llm-engineer-salary-skills-interview-2026/)）。

## 6. 2026 年不必再花大力气学的清单

| 不必学 | 理由 |
|---|---|
| 手写 ReAct 文本解析循环 | 原生 tool calling 普及，IBM 已废弃；解析 `Thought:/Action:` 是纯技术债 |
| 把"提示工程师"当职业定位 | 岗位已合并；技术本身降为基本功 |
| GraphRAG 作默认方案 | 浅层问答零收益，延迟 2.3×、token 成本差 6000× |
| 手写向量索引/HNSW、自建向量库 | pgvector/Qdrant 等成熟，无必要 |
| 个人规模全参微调、DeepSpeed ZeRO/Megatron 分布式 | 单卡无意义，属平台/训练团队范畴 |
| torchtune | README 自述停止维护 |
| 深绑 LangChain/LlamaIndex 专有 DSL | 两者已互相侵入对方领域，抽象随版本漂移；概念可迁移，DSL 不值得背 |
| 默认上多智能体 swarm | 15× token 成本；仅并行子任务才值 |
| 刷 MTEB 榜换嵌入模型 | 换模型须全量重嵌；同维度≠同向量空间 |
| all-minilm 上生产 | 2022 年 SOTA，现仅适合原型/边缘 |
| 自训嵌入模型；为应用工程师深钻反向传播数学 | 投入产出比极低，招聘不看 |

*未核实*：WarpGrep 类"用 RL 搜索 agent 替代向量索引"来自其销售方页面，属厂商口径（[morphllm](https://www.morphllm.com/ollama-embedding-models)）。

## 7. 分级判断

**必备**：递归结构感知分块 + 元数据设计｜BM25+dense 混合检索与 RRF 融合｜cross-encoder 重排序 top-50→top-5｜检索评测（recall@k / MRR / nDCG@10）｜原生 function calling（JSON Schema、带内错误、幂等键、可重试性分类）｜agent 循环控制（步数上限、超时、失败回退）｜LLM-as-judge 校准（≥85% 对齐人工）｜全链路 trace + 成本/p95 监控｜回归集进 CI｜上下文工程（预算分配、压缩、记忆分层）｜MCP 读写与安全边界｜结构化输出（JSON Schema / constrained decoding）

**加分**：Agentic RAG 查询改写、HyDE、多跳回退｜GraphRAG（仅关系型知识域）｜长上下文与 RAG 混合编排｜late chunking / contextual retrieval｜QLoRA 微调（仅行为类）+ DPO｜多智能体（仅并行子任务）｜本地部署与量化（Ollama/vLLM）｜合规举证（hash-chain 审计轨迹）

**可以不学**：见第 6 节 11 项。

## 8. 什么样的程度算"掌握"

**项目 A：检索系统（唯一必做）**
在自己的 500–5000 篇语料上做，必须逐项给出**改动前后的评测数字**：混合检索提升多少、重排提升多少、换分块策略提升多少。评测集不能编，要从你实际答错的 query 里攒出来。只会 `langchain.Retriever` 不算会 RAG。

**项目 B：单 agent 干一件真事**
5–8 个工具，至少一个写操作。要求：写工具带幂等键 + 回查接口；错误带内返回并标注可重试性；Langfuse 能看到每步 trace、单次任务成本、p95 延迟；50+ 回归用例在 CI 跑，judge 与自标注一致率 ≥85%。不要做多智能体聊天玩具。

**项目 C：微调（可选，须能证明必要性）**
先用 prompt+RAG 建立基线，再用 1–2K 条精选样本在 12GB 卡上 QLoRA 微调 7–8B。**做不出超过基线的数字，就把这次当"我验证了不该微调"的结论**——这本身就是掌握了边界。
