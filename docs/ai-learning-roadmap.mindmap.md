# Java 后端 → AI 应用工程

## 定位：选对方向
### ✅ 主攻
#### AI 应用工程师（RAG/结构化输出/流式/工具调用）
#### Agent 工程师（12个月目标）
#### AI 平台/网关工程师（差异化区）
#### AI 评测工程师（备选）
### ❌ 别碰
#### 大模型算法（仅8.7%岗位，92%要硕士）
#### 预训练（需求收缩30-40%）
#### 提示词工程师（岗位降超70%，已消亡）
#### 数据标注/AI训练师

## 起点：你已有什么
### 硬件
#### RTX 5070 Ti 12GB（Blackwell sm_120）
#### i9-13900HX / 32GB RAM
### 本地模型（Ollama 已跑通）
#### qwen3:8b — 推理/tools/thinking
#### qwen2.5-coder:7b — 代码/tools
#### gemma3:4b — 视觉
#### nomic-embed-text — 向量（代码里还没用上）
### 项目 GmScript 2.0
#### Spring Boot 3.5.16 + Spring AI 1.1.8
#### 33单测 / 35接口断言 / 真机注入全绿
#### 结论：项目就是最好的教材，不用从零造

## 阶段一：0-3个月 打地基
### 第1-2周 从「调模型」到「懂模型」
#### Token/上下文窗口/采样参数
#### 模型能力边界（thinking何时有用）
#### 结构化输出 + 解析失败处理
#### 动手：设置页加模型参数
### 第3-4周 Spring AI 2.x 迁移（可选）
#### 1.1.8 → 2.x（2.0.0 GA，2.1.0-RC1）
#### ⚠️ 2.0 要求 Spring Boot 4.0/4.1 + Jackson 3
#### 是跨大版本联动升级，不是改版本号
#### 建议：先跳过，跑通RAG拿到正反馈再回头
#### 核心变化：ChatClient成为主API
#### 核心变化：工具调用循环提到Advisor链
#### StructuredOutputValidationAdvisor 自动纠错
#### MCP一等公民：@McpTool，Streamable HTTP取代SSE
### 第5-8周 RAG 全链路
#### 文档解析（表格/代码块保真）
#### 递归结构感知分块 + 元数据
#### 嵌入（nomic-embed-text）
##### ⚠️ 必须加前缀 search_document:/search_query:
##### ⚠️ 上下文只有2048 token，超了静默截断
##### 想换：qwen3-embedding:0.6b / bge-m3
#### 向量库（pgvector / Milvus）
#### 混合检索 dense+BM25+RRF（重点）
#### 重排序 cross-encoder top50→top5（性价比最高，precision+25%）
#### 查询改写 HyDE
#### 权限过滤（检索前！）
#### 形态演进：线性RAG → Agentic RAG
##### 但先别上：简单问答传统检索更快更省
##### Agentic必须加步数上限+单步评分
#### 动手：给 GmScript 加私有知识库问答
#### ⚠️ GraphRAG 现在别碰
##### 每查询9770 tokens vs 朴素3000
##### IEEE实测：浅层问答零收益，延迟2.3×
##### 只在知识本身是关系型时才值
### 第9-12周 评测 + 可观测（拉开差距的关键）
#### 黄金评测集（从真实答错的query里长出来）
#### LLM-as-Judge 三大陷阱（有硬数据）
##### 位置偏置：源于自回归解码，写"忽略位置"几乎无效
##### 长度偏好：越长分越高
##### 自我偏好：同家族10-25%系统性偏置
##### 硬门槛：与人工对齐≥85%才能用
#### 回归测试：PR触发全量golden set
#### 指标：recall@k / MRR / nDCG@10 / faithfulness
#### 可观测：trace / token / p95 / 成本归因
#### 为什么重要：EU AI Act 2026-08-02生效，要求可举证
#### 动手：建评测集 + mvn test 回归

## 阶段二：4-6个月 Agent 与工程化
### Agent 核心
#### 原生 tool calling（JSON Schema）
#### ⚠️ 不要手写 ReAct 文本解析
##### IBM 标注 ReAct 2026-07-15 废弃
##### 要学循环控制+工具契约，不是解析Thought:/Action:
#### 循环控制：步数上限/超时/失败回退
#### Plan-and-Execute 只在步骤可预列时成立
#### 工具Schema设计
#### 错误处理：带内返回+标注可重试性（迁移率仅20%）
#### 幂等键 + 回查接口
#### 权限与 HITL + 审计
#### Checkpoint 状态持久化
#### 上下文工程（预算/压缩/记忆分层）
### MCP 协议
#### 已成事实标准，41%组织生产运行
#### Spring AI 2.0 支持最好（@McpTool）
#### 动手：把 Lua API 包装成 MCP Server
### 生产化能力（Java 护城河）
#### 熔断降级 / 超时重试 / 限流
#### 审计 / 灰度 / 可观测
#### 面试主动讲这些，别只讲 prompt
### ⚠️ 多智能体先别碰
#### Anthropic数据：单agent 4×聊天token，多agent 15×
#### 只在并行子任务+各自需独立长上下文时才值
### 现实数据
#### 77%-89%的agent项目过不了pilot→production
#### 根因是缺可举证证据，不是模型能力

## 阶段三：7-12个月 平台/网关
### AI 网关
#### 模型路由 / Token计费 / 限流 / 多租户
### AI 平台
#### 模型管理 / Prompt版本化 / 评测平台
### 成本治理
#### Token归因 / 前缀缓存 / 模型分级路由
### 动手：给 GmScript 加多模型路由层

## 微调：现在别做
### 决策边界
#### 注入事实知识 → RAG
#### 改变行为（语气/格式/拒绝校准）→ 微调
### 12GB 现实（Unsloth 官方数据）
#### 7B QLoRA 最低5GB，8B 6GB → 能跑
#### 16-bit LoRA 8B 要22GB → 跑不了
### 顺序：提示工程 → RAG → 才考虑微调
### 工具：Unsloth / Axolotl / LLaMA-Factory / TRL / PEFT
### ⚠️ torchtune 已停止维护，别选
### ⚠️ RTX50系：bitsandbytes 需 CUDA12.8-12.9 构建

## 技能分级
### 必备
#### 模型：token/采样参数/结构化输出/流式
#### RAG：递归分块/嵌入/向量库/混合检索/重排/检索评测
#### Agent：原生tool calling/循环控制/幂等/Checkpoint
#### 评测：评测集/Judge校准≥85%/回归/可观测
#### 框架：Spring AI / LangChain4j / MCP
### 加分
#### Agentic RAG（查询改写/多跳回退）
#### 长上下文与RAG混合编排
#### late chunking / contextual retrieval
#### QLoRA微调（仅行为类）+ DPO
#### 多模型路由与成本优化
#### 权限过滤与脱敏 / 多租户审计
#### 合规举证（hash-chain审计轨迹）
### 可以不学（11项）
#### 手写ReAct文本解析
#### GraphRAG作默认
#### 手写向量索引/HNSW
#### 个人规模全参微调/DeepSpeed/Megatron
#### torchtune（已停维护）
#### 深绑LangChain/LlamaIndex专有DSL
#### 默认上多智能体swarm
#### 刷MTEB换嵌入模型
#### all-minilm上生产
#### 自训嵌入模型
#### 为应用工程师深钻反向传播数学

## 项目验收标准（做到什么程度算会）
### 项目A：检索系统（唯一必做）
#### 500-5000篇自有语料
#### 必须逐项给出改动前后评测数字
#### 评测集从实际答错的query里攒
#### 不合格：只调了Retriever，说不出数字
### 项目B：单Agent干一件真事
#### 5-8个工具，至少一个写操作
#### 写工具带幂等键+回查接口
#### 错误带内返回并标注可重试性
#### Langfuse可见每步trace/单次成本/p95
#### 50+回归用例在CI跑
#### judge与自标注一致率≥85%
#### 不合格：多智能体聊天玩具
### 项目C：微调（可选）
#### 先建prompt+RAG基线
#### 做不出超基线数字 → 把这次当"验证了不该微调"

## Spring AI vs LangChain4j
### 模型集成数量：LangChain4j（30+）
### 向量库支持：LangChain4j（覆盖最广）
### MCP 支持：Spring AI 2.0（注解式）
### 可观测：Spring AI（Micrometer 一致）
### 结论
#### 主力用 Spring AI（同栈、MCP最好）
#### LangChain4j 要看得懂（面试常问）
#### 别在同一项目混用

## 你的差异化（面试怎么讲）
### 存量系统接入（企业不重写，只接入）
### Agent 生产化外壳（权限/幂等/熔断/审计）
### 强类型治理（entity(Class) / Tool Schema 当 DTO）
### 有一个真实能跑的系统，不是教程 demo
### ⚠️ 减分项
#### 只会说调API写Prompt
#### 张口就说要转Python学训练
#### 没有降级兜底 / 不聊权限审计
#### 把 demo 当项目上线

## 时间预期（现实版）
### 3个月：有可投递项目，不保证 offer
### 6个月：主要求职窗口（银行/券商/国央企）
### 12个月：Agent/平台/网关岗
### 窗口期约12-18个月，之后「会调模型」不再是优势

## 本机环境备忘
### 12GB 边界：7B舒适，14B临界
### nomic-embed-text：前缀必加，2048 token 上限
### RTX50系微调要 bitsandbytes CUDA12.8-12.9 构建
### 微调先别急，RAG+提示工程解决不了再考虑
