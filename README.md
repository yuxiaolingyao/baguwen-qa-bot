# 八股问答Bot

[![Java](https://img.shields.io/badge/Java-17%2B-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen)](https://spring.io/projects/spring-boot)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.13.1-blue)](https://docs.langchain4j.dev/)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

基于 **RAG（检索增强生成）** 的 Java 面试智能问答机器人。以结构化八股知识库为底座，通过飞书 Bot 提供自然语言问答、图片识别、文档阅读、代码审查等服务。

## 目录

- [功能特性](#功能特性)
- [系统架构](#系统架构)
- [快速开始](#快速开始)
- [配置参考](#配置参考)
- [项目结构](#项目结构)
- [API 文档](#api-文档)
- [开发指南](#开发指南)
- [常见问题](#常见问题)

## 功能特性

### 核心问答
- **自然语言提问** — 用户在飞书私聊中以自然语言提问，Agent 检索相关八股内容后由 LLM 生成回答
- **RAG 增强** — 向量 + 关键词 + RRF 融合检索，回答附带引用来源，可追溯、可验证
- **多轮对话** — 支持追问、澄清，按飞书用户维度维护对话上下文
- **记忆压缩** — 对话超过 18 条自动触发增量压缩，生成摘要保留关键信息

### 文件处理
- **文档阅读** — 用户通过飞书发送 `.txt` / `.md` / `.pdf` / `.docx`，解析后可在对话中追问
- **图片识别** — 用户发送截图/照片，多模态模型转文字后走问答管道
- **安全防御** — 三层校验：文件类型白名单 + 大小限制 + MIME 检测，防止伪装攻击和 DoS

### 辅助能力
- **代码审查** — 发送代码片段，委托 CodeReview Agent 进行线程安全、内存泄漏、安全漏洞审查
- **概念讲解** — 深度讲解技术概念，包含 ASCII 示意图 + 代码示例 + 面试考点
- **知识学习** — LLM 自动判断是否将新知识点存入知识库，JSON 持久化，重启后恢复

## 系统架构

```
                 飞书客户端 (桌面/移动)
                      │
              ┌───────┴────────┐
              │  飞书开放平台    │
              │  (WebSocket     │
              │   长连接推送)    │
              └───────┬────────┘
                      │
┌─────────────────────┴───────────────────────────────────┐
│                   Spring Boot 应用层                      │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌─────────────┐           │
│  │ Feishu   │  │  Image   │  │   File      │           │
│  │ Handler  │  │ Handler  │  │  Handler    │           │
│  └────┬─────┘  └────┬─────┘  └──────┬──────┘           │
│       │              │               │                   │
│  ┌────┴──────────────┴───────────────┴─────┐            │
│  │           BaguwenAgentService            │            │
│  │   对话编排 · 记忆压缩 · Failover        │            │
│  └────┬──────────────────┬────────────────┘            │
│       │                  │                               │
│  ┌────┴────┐   ┌─────────┴────────┐                    │
│  │ RAG 引擎 │   │   LLM 调用层      │                    │
│  │ 向量+关键词│   │ DeepSeek ▸ 百炼   │                    │
│  │ +RRF融合 │   │ (Failover 模式)   │                    │
│  └────┬────┘   └─────────┬────────┘                    │
│       │                  │                               │
└───────┼──────────────────┼───────────────────────────────┘
        │                  │
┌───────┴──────┐   ┌───────┴────────┐
│ 内存向量库    │   │  LLM Provider   │
│ 381 条八股   │   │ DeepSeek V4     │
│ JSON 持久化  │   │ 阿里百炼 Qwen   │
└──────────────┘   └────────────────┘
```

### 数据流

```
文档入库:
  MD/PDF/DOCX → Tika 解析 → TextSplitter 切片 → BGE Embedding → 向量库

问答流程:
  用户提问 → 向量检索 + 关键词检索 → RRF 融合 → Top-K 片段
    → 拼入 System Prompt → LLM 生成回答 → 引用标注 → 回复用户

图片处理:
  飞书 image_key → 下载 → Base64 → Qwen-VL-Plus → 文字描述
    → 拼入 prompt → 走正常问答管道

文件处理:
  飞书 file_key → 下载 → Tika 解析 → MIME 校验 → 字数校验
    → 拼入 prompt → 走正常问答管道（不污染知识库）
```

## 快速开始

### 环境依赖

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | 推荐 21 |
| Maven | 3.9+ | |
| MySQL | 8.0+ | 存储对话记录 |
| Redis | 6.0+ | 对话缓存 (TTL 30min) |

### 1. 创建数据库

```sql
CREATE DATABASE IF NOT EXISTS baguwen DEFAULT CHARACTER SET utf8mb4;
```

表结构由 `SchemaMigration` 启动时自动创建，无需手动执行 SQL。

### 2. 配置密钥

编辑 `src/main/resources/application.yml`，将 `xxxxx` 替换为实际密钥：

```yaml
deepseek:
  api-key: ${DEEPSEEK_API_KEY:xxxxx}

bailian:
  api-key: ${BAILIAN_API_KEY:xxxxx}

feishu:
  app-id: xxxxx
  app-secret: xxxxx
```

**密钥获取**：
- DeepSeek：[platform.deepseek.com/api_keys](https://platform.deepseek.com/api_keys)
- 阿里百炼：[bailian.console.aliyun.com](https://bailian.console.aliyun.com/) → 密钥管理
- 飞书：[open.feishu.cn/app](https://open.feishu.cn/app) → 创建应用 → 凭证与基础信息

### 3. 飞书 Bot 配置

1. [飞书开放平台](https://open.feishu.cn/app) → 创建企业自建应用
2. **添加能力** → 开启「机器人」
3. **事件订阅** → 添加 `im.message.receive_v1` 事件
4. **权限管理** → 开启：
   - `im:message` — 获取与发送消息
   - `im:message:readonly` — 读取消息内容
   - `im:resource` — 获取消息中的资源文件
5. **安全设置** → 发布上线（仅需管理员审批，租户内可用）

> 飞书 Bot 使用 WebSocket 长连接接收消息，**无需公网服务器、域名或内网穿透**。

### 4. 启动

```bash
git clone <your-repo-url>
cd baguwen-qa-bot
mvn spring-boot:run
```

启动成功日志：

```
Tomcat started on port 8080
PgVector 连接失败，降级为内存向量库
从预构建文件加载: data\knowledge-base.json (2589 KB)
已从文件加载 381 条知识
知识库就绪
飞书 WebSocket 长连接已启动，等待消息...
connected to wss://msg-frontier.feishu.cn/ws/v2?...
```

### 5. 验证

在飞书中私聊 Bot，发送：

```
什么是Java的类加载机制？
```

收到回复即表示运行正常。

## 配置参考

### application.yml 完整配置

```yaml
server:
  port: 8080                      # 服务端口

spring:
  datasource:                     # MySQL (对话持久化)
    url: jdbc:mysql://localhost:3306/baguwen
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASSWORD:123456}

  data.redis:                     # Redis (对话缓存)
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}

deepseek:                         # 主 LLM
  api-key: ${DEEPSEEK_API_KEY:xxxxx}
  model-name: deepseek-v4-flash
  temperature: 0.7
  max-tokens: 2048

bailian:                          # 备用 LLM + 视觉模型
  api-key: ${BAILIAN_API_KEY:xxxxx}
  model-name: qwen-plus
  temperature: 0.7
  max-tokens: 2048

feishu:                           # 飞书 Bot
  app-id: xxxxx
  app-secret: xxxxx

knowledge:                        # 检索参数
  top-k: 5                        # 返回 Top-K 片段
  min-score: 0.0                  # 最低相似度阈值
```

### 关键参数说明

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `deepseek.model-name` | `deepseek-v4-flash` | DeepSeek 思考模式模型，需 `returnThinking` + `sendThinking` |
| `bailian.model-name` | `qwen-plus` | 备用文本模型 |
| `bailian.vision-model` | `qwen-vl-plus` | 图片识别模型（代码中写死） |
| `knowledge.top-k` | `5` | 检索返回的片段数，每个片段最大 800 字 |
| `MAX_FILE_SIZE` | 50KB | 飞书文件上传上限 |
| `MAX_IMAGE_SIZE` | 5MB | 飞书图片上传上限 |
| `MAX_TEXT_CHARS` | 2000 | 文本/文档解析字数上限 |

### PgVector（可选）

项目默认使用内存向量库 + JSON 文件持久化。如果需要 PgVector：

```bash
# Docker 启动 PgVector
docker run -d --name pgvector \
  -e POSTGRES_DB=baguwen \
  -e POSTGRES_USER=baguwen \
  -e POSTGRES_PASSWORD=xxxxx \
  -p 5432:5432 pgvector/pgvector:pg17
```

应用会自动检测 PgVector 连接，连接成功则使用混合检索（BM25 + 向量 + RRF），失败则降级为内存模式。

## 项目结构

```
src/main/java/com/baguwen/bot/
├── Application.java                 # Spring Boot 启动类
│
├── config/                          # Bean 配置
│   ├── DeepSeekConfig.java          # DeepSeek LLM Bean
│   ├── BailianConfig.java           # 百炼 LLM + 视觉模型 Bean
│   ├── FailoverConfig.java          # FailoverChatModel 装配
│   ├── FailoverChatModel.java       # 主模型失败 → 备用模型
│   ├── RAGConfig.java               # EmbeddingModel / EmbeddingStore / 知识加载
│   ├── MemoryConfig.java            # ChatMemoryProvider Bean
│   ├── MemoryManager.java           # 内存记忆缓存 + TTL 淘汰
│   ├── FeishuConfig.java            # 飞书配置属性
│   └── SchemaMigration.java         # 启动时自动建表/加列
│
├── service/                         # AI 服务层
│   ├── BaguwenAgentService.java     # 服务接口
│   ├── BaguwenAgentServiceImpl.java # 对话编排 + 记忆压缩
│   ├── BaguwenAssistant.java        # @AiService 声明式代理 (DeepSeek)
│   ├── CodeReviewAgent.java         # @AiService 代码审查子 Agent
│   ├── ConceptAgent.java            # @AiService 概念讲解子 Agent
│   ├── AgentTools.java              # @Tool: delegateCodeReview / delegateConceptExplain
│   ├── KnowledgeTools.java          # @Tool: searchKnowledge / saveToKnowledge
│   ├── HybridSearchService.java     # 混合检索 (向量 + 关键词 + RRF)
│   ├── DocumentService.java         # 文档上传解析入库
│   └── ConversationCompressor.java  # LLM 对话摘要压缩
│
├── store/                           # 持久层
│   ├── RedisMysqlChatMemoryStore.java # ChatMemoryStore: Redis + MySQL 双写
│   ├── KnowledgeFileStore.java      # JSON/JSONL 文件知识持久化
│   └── TextIndexStore.java          # 文本索引 + 关键词检索
│
├── feishu/                          # 飞书通信层
│   ├── FeishuWebSocketClient.java   # WebSocket 长连接 + 消息路由
│   └── FeishuMessageSender.java     # 飞书 API: 卡片消息 / 资源下载
│
├── entity/
│   └── ChatMessageEntity.java       # 对话消息实体 (MyBatis-Plus)
│
├── mapper/
│   └── ChatMessageMapper.java       # MyBatis-Plus BaseMapper
│
├── controller/
│   └── KnowledgeController.java     # REST API: 知识库上传 / 统计
│
└── util/
    └── TextSplitter.java            # Markdown 标题切分
```

## API 文档

### 知识库管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/knowledge/stats` | 获取知识库统计信息 |
| `POST` | `/api/knowledge/upload` | 上传文档到知识库 |

#### 上传文档

```bash
curl -X POST http://localhost:8080/api/knowledge/upload \
  -F "file=@docs/Spring面试题.md" \
  -F "category=Spring"
```

响应：
```json
{"ok": true, "filename": "Spring面试题.md", "category": "Spring"}
```

支持的文档格式：`.txt` / `.md` / `.pdf` / `.docx` / `.html`

### 飞书对话指令

| 指令 | 说明 |
|------|------|
| `/reset` | 清空当前用户的对话记忆 |
| 发送图片 | 视觉模型识别图片内容后问答 |
| 发送文件 | 解析文档内容后可在对话中追问 |

## 开发指南

### 分层架构规则

```
feishu/ ──→ service/ ──→ config/ ──→ store/ ──→ entity/mapper/ ──→ controller/
```

- `feishu/` 不直接调 `mapper/`
- `service/` 不处理飞书事件原始对象
- `BaguwenAssistant` 为 langchain4j `@AiService` 声明式代理，禁止手写实现类

### 关键设计决策

| 决策 | 原因 |
|------|------|
| 阻塞模式 | 避免流式模式下的 thinking token、tool call ID 累积等边界 bug |
| `returnThinking` + `sendThinking` | DeepSeek V4 默认启用思考模式，思考内容必须在多轮对话中完整往返 |
| 缓冲式 Failover | 主模型失败时切换到备用模型，避免输出混叠 |
| ChatMemory.set() 原子替换 | langchain4j 1.11.0+ API，压缩时一次 store 更新代替 N 次 |
| JSONL 知识持久化 | O(1) 追加，零依赖，Windows 友好 |

### 添加新的消息类型

1. 在 `FeishuWebSocketClient.handleMessage()` 添加新分支
2. 实现对应的 `processXxx()` 方法
3. 如需 AI 处理，通过 `baguwenAgentService.chat()` 调用

### 添加新的 LLM Provider

1. 创建 `XxxConfig.java`，声明 `ChatModel` Bean
2. 在 `FailoverConfig` 中装配进 `FailoverChatModel`

## 常见问题

### Q: 启动时 PgVector 连接失败？
A: 正常。Windows 上 PgVector 安装困难，项目自动降级为内存向量库，通过 `data/knowledge-base.json` 持久化。知识可正常检索和保存。

### Q: 飞书收不到回复？
A: 检查：① 应用是否已发布上线；② 事件订阅是否配置 `im.message.receive_v1`；③ 权限是否已开启。

### Q: 回复很慢？
A: `deepseek-v4-flash` 默认开启思考模式，每次回复先生成思考 token。可关闭 thinking mode。

### Q: 知识库如何更新？
A: 三种方式：① 管理员通过 REST API 上传文档；② LLM 在对话中自动调用 `saveToKnowledge`；③ 直接编辑 `data/knowledge-base.json`。

### Q: 对话记录存在哪里？
A: Redis (TTL 30min) + MySQL 持久化。重启后 Redis 缓存丢失，从 MySQL 恢复。

### Q: 支持群聊吗？
A: 支持。`im.message.receive_v1` 事件同时覆盖私聊和群聊 @Bot，在群聊中 @机器人即可触发回复。

## License

MIT
