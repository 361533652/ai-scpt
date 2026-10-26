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
#### 提示词工程师（岗位降70%，已消亡）
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
### 第3-4周 Spring AI 2.x 迁移
#### 1.1.8 → 2.x（2.0.0/2.0.1/2.0.2 GA，2.1.0-RC1）
#### ⚠️ 2.0 要求 Spring Boot 4.0/4.1 + Jackson 3
#### 是跨大版本联动升级，不是改版本号
#### 建议：先跳过，跑通RAG拿到正反馈再回头
#### 核心变化：ChatClient成为主API
#### 核心变化：工具调用循环提到Advisor链
#### StructuredOutputValidationAdvisor 自动纠错
#### MCP一等公民：@McpTool，Streamable HTTP取代SSE
### 第5-8周 RAG 全链路
#### 文档解析（表格/代码块保真）
#### 分块策略 + overlap
#### 嵌入（nomic-embed-text，记得归一化）
#### 向量库（pgvector / Milvus）
#### 混合检索 dense+BM25+RRF（重点）
#### 重排序 cross-encoder
#### 查询改写 HyDE
#### 权限过滤（检索前！）
#### 动手：给 GmScript 加私有知识库问答
#### ⚠️ GraphRAG 现在别碰
##### 每查询9770 tokens vs 朴素3000，三倍成本
##### 中文实测因图谱覆盖率不足反而落后
### 第9-12周 评测 + 可观测（拉开差距的关键）
#### 黄金评测集 50-100条（正常/边界/对抗）
#### LLM-as-Judge 三大陷阱
##### 位置偏见 / 长度偏好 / 自我偏好
#### 回归测试：换模型/prompt不能退化
#### 指标：recall@k / MRR / faithfulness
#### 可观测：token/延迟/成本归因
#### 动手：建评测集 + mvn test 回归

## 阶段二：4-6个月 Agent 与工程化
### Agent 核心
#### ReAct 循环（徒手写，不靠框架）
#### Plan-and-Execute（适合长任务）
#### 工具 Schema 设计
#### 错误处理：返回模型能自救的提示（迁移率仅20%）
#### 幂等与重试
#### 权限与 HITL + 审计
#### Checkpoint 状态持久化
### MCP 协议
#### Spring AI 2.0 支持最好（@McpTool）
#### 动手：把 Lua API 包装成 MCP Server
### 生产化能力（Java 护城河）
#### 熔断降级 / 超时重试 / 限流
#### 审计 / 灰度 / 可观测
#### 面试主动讲这些，别只讲 prompt
### ⚠️ 多智能体先别碰
#### Gartner：超60%早期Agent编排达不到预期

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
#### 改变行为（语气/格式）→ 微调
#### 学全新技能 → 微调
### 12GB 现实
#### 7B QLoRA 约6GB，能跑
#### 14B 约12GB，临界值
### 顺序：提示工程 → RAG → 才考虑微调
### 真要试：Unsloth / ms-swift / LLaMA-Factory
### ⚠️ RTX50系必踩：bitsandbytes 需 CUDA12.8-12.9 构建

## 技能分级
### 必备
#### 模型：token/采样参数/结构化输出/流式
#### RAG：解析/分块/嵌入/向量库/混合检索/重排
#### Agent：ReAct/Tool Calling/错误处理/Checkpoint
#### 评测：评测集/Judge/回归/可观测
#### 框架：Spring AI / LangChain4j / MCP
### 加分
#### 多模型路由与成本优化
#### 权限过滤与脱敏
#### 多租户与审计
#### A/B 与灰度
#### LoRA 跑通一次
### 可以不学
#### 从零训练 / 手写Transformer
#### GraphRAG（现阶段）
#### 多智能体深度定制
#### 追每个新框架

## Spring AI vs LangChain4j
### 模型集成数量：LangChain4j（30+）
### 向量库支持：LangChain4j（覆盖最广）
### MCP 支持：Spring AI 2.0（注解式）
### 可观测：Spring AI（Micrometer 一致）
### 结论
#### 主力用 Spring AI（同栈、MCP最好）
#### LangChain4j 要看得懂（面试常问）
#### 别在同一项目混用

## 明确不用学（省时间）
### 从零训练大模型
### 手写 Transformer/反向传播
### GraphRAG（现阶段性价比低）
### LangChain 0.x 的 initialize_agent
### OpenAI Evals（v1/prompts 将关停）
### 追每一个新框架（原理和评测不换）

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
### RTX50系微调要 bitsandbytes CUDA12.8-12.9 构建
### 微调先别急，RAG+提示工程解决不了再考虑
### 真要试：Unsloth / ms-swift，7B 跑通一次即可
