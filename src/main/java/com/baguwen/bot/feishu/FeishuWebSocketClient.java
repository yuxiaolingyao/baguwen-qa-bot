package com.baguwen.bot.feishu;

import com.baguwen.bot.config.FeishuConfig;
import com.baguwen.bot.service.BaguwenAgentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class FeishuWebSocketClient implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebSocketClient.class);

    private final FeishuConfig config;
    private final BaguwenAgentService baguwenAgentService;
    private final FeishuMessageSender sender;
    private final ChatModel visionModel;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, ExecutorService> userExecutors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastAccessTimes = new ConcurrentHashMap<>();
    private static final long EXECUTOR_IDLE_TTL_MS = 30 * 60 * 1000;
    // 三层防御: 飞书消息自带 file_size → 下载后实际 byte 长度 → Tika MIME 白名单检测
    private static final int MAX_IMAGE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_FILE_SIZE = 50 * 1024;
    private static final int MAX_TEXT_CHARS = 2000;
    private static final Set<String> ALLOWED_MIME = Set.of(
            "text/plain", "text/markdown", "text/x-markdown",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword"
    );

    public FeishuWebSocketClient(FeishuConfig config, BaguwenAgentService baguwenAgentService,
                                 FeishuMessageSender sender,
                                 @Qualifier("bailianVisionChatModel") ChatModel visionModel,
                                 ObjectMapper objectMapper) {
        this.config = config;
        this.baguwenAgentService = baguwenAgentService;
        this.sender = sender;
        this.visionModel = visionModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        String appId = config.getAppId();
        String appSecret = config.getAppSecret();

        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            log.warn("飞书 App ID / App Secret 未配置，跳过 WebSocket 长连接启动");
            return;
        }

        EventDispatcher handler = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        handleMessage(event);
                    }
                })
                .build();

        new Client.Builder(appId, appSecret)
                .eventHandler(handler)
                .build()
                .start();

        log.info("飞书 WebSocket 长连接已启动，等待消息...");

        Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "feishu-executor-cleanup");
                t.setDaemon(true);
                return t;
            }
        }).scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                userExecutors.entrySet().removeIf(new java.util.function.Predicate<ConcurrentHashMap.Entry<String, ExecutorService>>() {
                    @Override
                    public boolean test(ConcurrentHashMap.Entry<String, ExecutorService> entry) {
                        ExecutorService executor = entry.getValue();
                        if (executor.isShutdown() || executor.isTerminated()) {
                            log.debug("清理已终止的 executor [{}]", entry.getKey());
                            lastAccessTimes.remove(entry.getKey());
                            return true;
                        }
                        Long lastAccess = lastAccessTimes.get(entry.getKey());
                        if (lastAccess != null && now - lastAccess > EXECUTOR_IDLE_TTL_MS) {
                            log.info("淘汰空闲 executor [{}]，空闲超过 30 分钟", entry.getKey());
                            executor.shutdownNow();
                            lastAccessTimes.remove(entry.getKey());
                            return true;
                        }
                        return false;
                    }
                });
            }
        }, 1, 1, TimeUnit.HOURS);
    }

    private void handleMessage(P2MessageReceiveV1 event) {
        try {
            String messageType = event.getEvent().getMessage().getMessageType();
            String senderId = event.getEvent().getSender().getSenderId().getOpenId();
            lastAccessTimes.put(senderId, System.currentTimeMillis());
            String messageId = event.getEvent().getMessage().getMessageId();
            String contentJson = event.getEvent().getMessage().getContent();
            JsonNode node = objectMapper.readTree(contentJson);

            if ("text".equals(messageType)) {
                String userMessage = node.get("text").asText();
                if (userMessage.length() > MAX_TEXT_CHARS) {
                    log.info("消息过长拒绝 [{}]: {} chars", senderId, userMessage.length());
                    final String msg = userMessage;
                    getExecutor(senderId).submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                String placeholderId = sender.replyCardPlaceholder(messageId);
                                sender.patchCard(placeholderId, "\\u26a0\\ufe0f 消息过长 (" + msg.length()
                                        + " 字)，限制 " + MAX_TEXT_CHARS + " 字，请精简后重试");
                            } catch (Exception ignored) { }
                        }
                    });
                    return;
                }
                final String msg = userMessage;
                log.info("收到消息 [{}]: {}", senderId, msg);
                getExecutor(senderId).submit(new Runnable() {
                    @Override
                    public void run() {
                        processAsync(senderId, messageId, msg);
                    }
                });
            } else if ("image".equals(messageType)) {
                String imageKey = node.get("image_key").asText();
                log.info("收到图片 [{}]: {}", senderId, imageKey);
                getExecutor(senderId).submit(new Runnable() {
                    @Override
                    public void run() {
                        processImage(senderId, messageId, imageKey);
                    }
                });
            } else if ("file".equals(messageType)) {
                String fileKey = node.get("file_key").asText();
                String fileName = node.has("file_name") ? node.get("file_name").asText() : "unknown";
                long fileSize = node.has("file_size") ? node.get("file_size").asLong() : 0;
                log.info("收到文件 [{}]: {} ({} bytes)", senderId, fileName, fileSize);
                getExecutor(senderId).submit(new Runnable() {
                    @Override
                    public void run() {
                        processFile(senderId, messageId, fileKey, fileName, fileSize);
                    }
                });
            }

        } catch (Exception e) {
            log.error("处理消息失败", e);
        }
    }

    // per-user 单线程 executor，保证同用户消息按序处理。空闲 30 分钟自动淘汰。
    private ExecutorService getExecutor(String senderId) {
        return userExecutors.computeIfAbsent(senderId,
                new java.util.function.Function<String, ExecutorService>() {
                    @Override
                    public ExecutorService apply(String id) {
                        return Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "feishu-user-" + id.substring(0, 8));
                                t.setDaemon(true);
                                return t;
                            }
                        });
                    }
                });
    }

    private void processAsync(String senderId, String messageId, String userMessage) {
        try {
            if ("/reset".equals(userMessage.trim())) {
                baguwenAgentService.clearMemory(senderId);
                String resetId = sender.replyCardPlaceholder(messageId);
                sender.patchCard(resetId, "\\u2705 已清空对话记忆，现在可以开启新会话了。");
                log.info("用户 [{}] 手动清空记忆", senderId);
                return;
            }

            String placeholderId = sender.replyCardPlaceholder(messageId);
            log.debug("占位卡片已发送 [{}]: msgId={}", senderId, placeholderId);
            streamAndUpdate(senderId, userMessage, placeholderId);
        } catch (Exception e) {
            log.error("异步回复失败 [{}]", senderId, e);
        }
    }

    private void processImage(String senderId, String messageId, String imageKey) {
        String placeholderId = null;
        try {
            placeholderId = sender.replyCardPlaceholder(messageId);
            sender.patchCard(placeholderId, "🖼️ 正在识别图片...");

            byte[] imageBytes = sender.downloadImage(messageId, imageKey);
            if (imageBytes.length > MAX_IMAGE_SIZE) {
                sender.patchCard(placeholderId, "\\u26a0\\ufe0f 图片过大 (最大 5MB)，请压缩后重试");
                return;
            }
            ChatRequest visionReq = ChatRequest.builder()
                    .messages(List.of(UserMessage.from(
                            ImageContent.from(Image.builder().base64Data(Base64.getEncoder().encodeToString(imageBytes)).mimeType("image/jpeg").build()),
                            TextContent.from("请详细描述这张图片的内容。如果包含文字，请完整提取。如果包含代码或题目，请完整输出。只输出描述，不要输出额外的问候语。")
                    )))
                    .build();
            ChatResponse visionResp = visionModel.chat(visionReq);
            String description = visionResp.aiMessage().text();

            String enhancedMessage = "[用户发送了一张图片，内容如下]\n"
                    + description + "\n\n请根据图片内容回答用户的问题。";
            streamAndUpdate(senderId, enhancedMessage, placeholderId);
        } catch (Exception e) {
            log.error("图片处理失败 [{}]", senderId, e);
            if (placeholderId != null) {
                try {
                    sender.patchCard(placeholderId, "\\u26a0\\ufe0f 图片处理失败，请稍后重试");
                } catch (Exception ex) {
                    log.error("错误卡片发送失败", ex);
                }
            }
        }
    }

    private void processFile(String senderId, String messageId, String fileKey,
                              String fileName, long fileSize) {
        String placeholderId = null;
        try {
            placeholderId = sender.replyCardPlaceholder(messageId);

            if (fileSize > MAX_FILE_SIZE) {
                sender.patchCard(placeholderId, "\\u26a0\\ufe0f 文件过大 (最大 50KB)，请压缩后重试");
                return;
            }

            sender.patchCard(placeholderId, "📄 正在解析文件...");
            byte[] raw = sender.downloadFile(messageId, fileKey);
            if (raw.length > MAX_FILE_SIZE) {
                sender.patchCard(placeholderId, "\\u26a0\\ufe0f 文件过大 (最大 50KB)，请压缩后重试");
                return;
            }

            Tika tika = new Tika();
            String detectedMime = tika.detect(raw, fileName);
            if (!ALLOWED_MIME.contains(detectedMime)) {
                log.warn("拒绝文件 [{}]: 检测 MIME={}, 文件名={}", senderId, detectedMime, fileName);
                sender.patchCard(placeholderId, "\\u26a0\\ufe0f 不支持的文件类型: " + detectedMime);
                return;
            }

            String extractedText = tika.parseToString(new ByteArrayInputStream(raw));
            if (extractedText.length() > MAX_TEXT_CHARS) {
                sender.patchCard(placeholderId, "\\u26a0\\ufe0f 文档过长 (" + extractedText.length()
                        + " 字)，限制 " + MAX_TEXT_CHARS + " 字，请精简后重试");
                return;
            }

            // 文件仅用于当前对话，不写入知识库。知识库只由管理员 REST API 和 LLM saveToKnowledge 维护。
            String prompt = "[用户上传了文件: " + fileName + "，内容如下]\n" + extractedText
                    + "\n\n请阅读以上文件内容，简要介绍文件内容，并等待用户提问。";
            streamAndUpdate(senderId, prompt, placeholderId);
            log.info("文件处理完成 [{}]: {} ({} bytes, {} chars, {})", senderId, fileName, raw.length,
                    extractedText.length(), detectedMime);
        } catch (Exception e) {
            log.error("文件处理失败 [{}]", senderId, e);
            if (placeholderId != null) {
                try {
                    sender.patchCard(placeholderId, "\\u26a0\\ufe0f 文件处理失败，请稍后重试");
                } catch (Exception ex) {
                    log.error("错误卡片发送失败", ex);
                }
            }
        }
    }

    private void streamAndUpdate(String senderId, String userMessage, String placeholderId) {
        try {
            String reply = baguwenAgentService.chat(senderId, userMessage);
            StringBuilder result = new StringBuilder(reply);
            result.append("\n\n💡 还有其他问题吗？");
            if (baguwenAgentService.isMemoryNearFull(senderId)) {
                result.append("\n\n⚠️ 对话轮次较多，发送 /reset 可开启新会话。");
            }
            sender.patchCard(placeholderId, result.toString());
            log.info("回复完成 [{}]", senderId);
        } catch (Exception e) {
            log.error("调用失败 [{}]", senderId, e);
            try {
                sender.patchCard(placeholderId, "\\u26a0\\ufe0f 处理请求失败，请稍后重试");
            } catch (Exception ex) {
                log.error("错误卡片发送失败 [{}]", senderId, ex);
            }
        }
    }
}
