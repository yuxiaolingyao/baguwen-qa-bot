package com.baguwen.bot.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baguwen.bot.entity.ChatMessageEntity;
import com.baguwen.bot.mapper.ChatMessageMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class RedisMysqlChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisMysqlChatMemoryStore.class);

    private static final String REDIS_PREFIX = "chat:memory:";
    private static final Duration REDIS_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final ChatMessageMapper mapper;
    private final ObjectMapper objectMapper;

    public RedisMysqlChatMemoryStore(StringRedisTemplate redis, ChatMessageMapper mapper,
                                      ObjectMapper objectMapper) {
        this.redis = redis;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    // Redis 热缓存 (TTL 30min) → MySQL 冷持久化。更新时先写 MySQL 再写 Redis，读取时反向。
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = REDIS_PREFIX + memoryId;

        String json = redis.opsForValue().get(key);
        if (json != null) {
            return deserializeMessages(json);
        }

        log.debug("Redis 未命中，从 MySQL 加载 [{}]", memoryId);
        List<ChatMessage> messages = loadFromMysql(memoryId);

        if (!messages.isEmpty()) {
            redis.opsForValue().set(key, serializeMessages(messages), REDIS_TTL);
        }

        return messages;
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = REDIS_PREFIX + memoryId;

        saveToMysql(memoryId, messages);

        if (messages.isEmpty()) {
            redis.delete(key);
        } else {
            redis.opsForValue().set(key, serializeMessages(messages), REDIS_TTL);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(REDIS_PREFIX + memoryId);
        mapper.delete(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getMemoryId, memoryId.toString()));
    }

    private List<ChatMessage> loadFromMysql(Object memoryId) {
        List<ChatMessageEntity> entities = mapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getMemoryId, memoryId.toString())
                        .orderByAsc(ChatMessageEntity::getSeq));

        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMessageEntity entity : entities) {
            if ("AI".equals(entity.getRole())) {
                messages.add(restoreAiMessage(entity.getContent(), entity.getToolData()));
            } else if ("SYSTEM".equals(entity.getRole())) {
                messages.add(new SystemMessage(entity.getContent()));
            } else if ("TOOL".equals(entity.getRole())) {
                String[] toolMeta = parseToolMeta(entity.getToolData());
                String toolId = toolMeta != null ? toolMeta[0] : null;
                String toolName = toolMeta != null ? toolMeta[1] : null;
                messages.add(new ToolExecutionResultMessage(toolId, toolName, entity.getContent()));
            } else {
                messages.add(new UserMessage(entity.getContent()));
            }
        }
        return messages;
    }

    private void saveToMysql(Object memoryId, List<ChatMessage> messages) {
        mapper.delete(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getMemoryId, memoryId.toString()));

        if (messages.isEmpty()) {
            return;
        }

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            ChatMessageEntity entity = new ChatMessageEntity();
            entity.setMemoryId(memoryId.toString());
            entity.setSeq(i);
            if (msg instanceof AiMessage) {
                AiMessage aiMsg = (AiMessage) msg;
                entity.setRole("AI");
                // text() 在纯工具调用时为 null，MySQL NOT NULL 列需要空字符串兜底
                entity.setContent(aiMsg.text() != null ? aiMsg.text() : "");
                // tool_data 存储 toolExecutionRequests + reasoningContent (DeepSeek 思考模式必需)
                String toolData = serializeAiMeta(aiMsg);
                if (toolData != null) {
                    entity.setToolData(toolData);
                }
            } else if (msg instanceof UserMessage) {
                entity.setRole("USER");
                entity.setContent(((UserMessage) msg).singleText());
            } else if (msg instanceof SystemMessage) {
                entity.setRole("SYSTEM");
                entity.setContent(((SystemMessage) msg).text());
            } else if (msg instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage toolMsg = (ToolExecutionResultMessage) msg;
                entity.setRole("TOOL");
                entity.setContent(toolMsg.text());
                entity.setToolData(serializeToolMeta(toolMsg.id(), toolMsg.toolName()));
            }
            if (entity.getRole() != null) {
                mapper.insert(entity);
            } else {
                log.warn("未知消息类型，跳过持久化: class={}", msg.getClass().getSimpleName());
            }
        }
    }

    private String serializeMessages(List<ChatMessage> messages) {
        ArrayNode array = objectMapper.createArrayNode();
        for (ChatMessage msg : messages) {
            ObjectNode node = objectMapper.createObjectNode();
            if (msg instanceof AiMessage) {
                AiMessage aiMsg = (AiMessage) msg;
                node.put("type", "AI");
                node.put("text", aiMsg.text() != null ? aiMsg.text() : "");
                if (aiMsg.hasToolExecutionRequests()) {
                    node.put("toolRequests", serializeToolRequestsNode(aiMsg.toolExecutionRequests()));
                }
                String thinking = aiMsg.thinking();
                if (thinking != null && !thinking.isEmpty()) {
                    node.put("reasoningContent", thinking);
                }
            } else if (msg instanceof UserMessage) {
                node.put("type", "USER");
                node.put("text", ((UserMessage) msg).singleText());
            } else if (msg instanceof SystemMessage) {
                node.put("type", "SYSTEM");
                node.put("text", ((SystemMessage) msg).text());
            } else if (msg instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage toolMsg = (ToolExecutionResultMessage) msg;
                node.put("type", "TOOL");
                node.put("text", toolMsg.text());
                node.put("toolId", toolMsg.id());
                node.put("toolName", toolMsg.toolName());
            }
            array.add(node);
        }
        try {
            return objectMapper.writeValueAsString(array);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // ─── 工具元数据序列化辅助 ──────────────────────────────

    private static String serializeToolMeta(String toolId, String toolName) {
        if (toolId == null && toolName == null) return null;
        return "{\"toolId\":\"" + (toolId != null ? toolId : "") + "\","
                + "\"toolName\":\"" + (toolName != null ? toolName : "") + "\"}";
    }

    private static String[] parseToolMeta(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonNode node = new ObjectMapper().readTree(json);
            String id = node.has("toolId") ? node.get("toolId").asText() : null;
            String name = node.has("toolName") ? node.get("toolName").asText() : null;
            if (id != null && id.isEmpty()) id = null;
            if (name != null && name.isEmpty()) name = null;
            return new String[]{id, name};
        } catch (Exception e) {
            return null;
        }
    }

    private String serializeAiMeta(AiMessage aiMsg) {
        List<ToolExecutionRequest> requests = aiMsg.hasToolExecutionRequests()
                ? aiMsg.toolExecutionRequests() : null;
        String thinking = aiMsg.thinking();
        if (requests == null && (thinking == null || thinking.isEmpty())) {
            return null;
        }
        try {
            ObjectNode root = objectMapper.createObjectNode();
            if (requests != null) {
                ArrayNode arr = root.putArray("toolRequests");
                for (ToolExecutionRequest req : requests) {
                    ObjectNode n = arr.addObject();
                    n.put("id", req.id());
                    n.put("name", req.name());
                    n.put("arguments", req.arguments());
                }
            }
            if (thinking != null && !thinking.isEmpty()) {
                root.put("reasoningContent", thinking);
            }
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private AiMessage restoreAiMessage(String text, String toolData) {
        List<ToolExecutionRequest> toolRequests = null;
        String reasoning = null;
        if (toolData != null && !toolData.isEmpty()) {
            try {
                JsonNode root = objectMapper.readTree(toolData);
                JsonNode trNode = root.get("toolRequests");
                if (trNode != null && trNode.isArray()) {
                    toolRequests = new ArrayList<>();
                    for (JsonNode n : trNode) {
                        toolRequests.add(ToolExecutionRequest.builder()
                                .id(n.get("id").asText())
                                .name(n.get("name").asText())
                                .arguments(n.get("arguments").asText())
                                .build());
                    }
                }
                JsonNode rcNode = root.get("reasoningContent");
                if (rcNode != null && !rcNode.isNull()) {
                    reasoning = rcNode.asText();
                }
            } catch (Exception e) {
                log.debug("解析 tool_data 失败: {}", e.getMessage());
            }
        }
        return buildAiMessage(text, toolRequests, reasoning);
    }

    // AiMessage 有三个字段: text, toolExecutionRequests, thinking。
    // thinking 只能通过 builder 设置，因此有 reasoning 时必须重建。
    private static AiMessage buildAiMessage(String text, List<ToolExecutionRequest> toolRequests,
                                            String reasoning) {
        AiMessage msg;
        if (toolRequests != null && !toolRequests.isEmpty()) {
            msg = AiMessage.from(text != null ? text : "", toolRequests);
        } else {
            msg = new AiMessage(text != null ? text : "");
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            msg = AiMessage.builder()
                    .text(msg.text())
                    .thinking(reasoning)
                    .toolExecutionRequests(msg.toolExecutionRequests())
                    .build();
        }
        return msg;
    }

    private ArrayNode serializeToolRequestsNode(List<ToolExecutionRequest> requests) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (ToolExecutionRequest req : requests) {
            ObjectNode n = arr.addObject();
            n.put("id", req.id());
            n.put("name", req.name());
            n.put("arguments", req.arguments());
        }
        return arr;
    }

    private List<ChatMessage> deserializeMessages(String json) {
        List<ChatMessage> messages = new ArrayList<>();
        try {
            ArrayNode array = (ArrayNode) objectMapper.readTree(json);
            for (JsonNode node : array) {
                String type = node.get("type").asText();
                String text = node.get("text").asText();
                if ("AI".equals(type)) {
                    List<ToolExecutionRequest> toolRequests = null;
                    JsonNode trNode = node.get("toolRequests");
                    if (trNode != null && trNode.isArray()) {
                        toolRequests = new ArrayList<>();
                        for (JsonNode tr : trNode) {
                            toolRequests.add(ToolExecutionRequest.builder()
                                    .id(tr.get("id").asText())
                                    .name(tr.get("name").asText())
                                    .arguments(tr.get("arguments").asText())
                                    .build());
                        }
                    }
                    String reasoning = null;
                    JsonNode rcNode = node.get("reasoningContent");
                    if (rcNode != null && !rcNode.isNull()) {
                        reasoning = rcNode.asText();
                    }
                    messages.add(buildAiMessage(text, toolRequests, reasoning));
                } else if ("SYSTEM".equals(type)) {
                    messages.add(new SystemMessage(text));
                } else if ("TOOL".equals(type)) {
                    JsonNode tidNode = node.get("toolId");
                    JsonNode tnNode = node.get("toolName");
                    String toolId = tidNode != null && !tidNode.isNull() ? tidNode.asText() : null;
                    String toolName = tnNode != null && !tnNode.isNull() ? tnNode.asText() : null;
                    messages.add(new ToolExecutionResultMessage(toolId, toolName, text));
                } else {
                    messages.add(new UserMessage(text));
                }
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return messages;
    }
}
