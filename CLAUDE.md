# baguwen-qa-bot 项目规约

## 项目架构

- `feishu/` 通信层 → `service/` 业务层 → `config/` 配置层 → `store/` 持久层 → `entity/mapper/` 数据层 → `controller/` API 层
- `BaguwenAssistant` 为 langchain4j `@AiService` 声明式代理，不可手写实现类。
- 跨层规则：`feishu/` 不直接调 `mapper/`，`service/` 不处理飞书事件原始对象。

## langchain4j 约束

| 约束 | 说明 |
|------|------|
| `@AiService` 接口 | 每个方法必须标注 `@SystemMessage` / `@MemoryId` / `@UserMessage` |
| ChatMemory | 由 `MemoryConfig` → `MemoryManager` 统一管理，不可手动 new |
| 流式 | `TokenStream` + 匿名内部类绑定 `onPartialResponse` / `onCompleteResponse` / `onError` |
| 版本 | langchain4j 1.13.1 |

## 飞书 SDK 约束 (lark-oapi 2.5.3)

| 约束 | 说明 |
|------|------|
| 回复消息 | 使用 `ReplyMessageReq`，禁止 `CreateMessageReq` + 手动 `rootId` |
| 流式更新卡片 | `PatchMessageReq`，卡片 JSON 必须含 `"update_multi": true` |
| WebSocket 回调 | 禁止长耗时操作，必须提交到 per-user 单线程队列 |

## 技术栈

| 组件 | 选型 |
|------|------|
| LLM | DeepSeek（`@Primary`）+ 百炼备用（`FailoverChatModel` / `FailoverStreamingChatModel` 缓冲模式） |
| 向量 | BGE-small-zh + InMemoryEmbeddingStore（PgVector 不可用时自动降级） |
| 知识持久化 | `knowledge-base.json`（启动加载）+ `knowledge-learned.jsonl`（saveToKnowledge 追加） |
| 记忆 | Redis 缓存（TTL 30min）+ MySQL 持久化 + MessageWindowChatMemory(20) |
| 压缩 | 增量，≥18 条触发，`ChatMemory.set()` 原子替换 |
| 检索 | vector + keyword + RRF 融合，关键词侧 log1p 归一化 |
| ORM | MyBatis-Plus |

## 关键组件

| 组件 | 位置 | 职责 |
|------|------|------|
| `TextIndexStore` | `store/` | 文本索引 + 关键词检索 |
| `KnowledgeFileStore` | `store/` | JSON/JSONL 文件知识持久化 |
| `SchemaMigration` | `config/` | 启动时自动建表/加列，首次迁移清旧数据 |

## 启动前必备

- MySQL `baguwen` 库已建（`SchemaMigration` 自动建表）
- Redis 默认 `localhost:6379`
- 密钥在 `application.yml` 默认值中，提交前替换为 `xxxxx`
