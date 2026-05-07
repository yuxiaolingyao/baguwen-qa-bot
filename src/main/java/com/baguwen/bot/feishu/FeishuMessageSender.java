package com.baguwen.bot.feishu;

import com.baguwen.bot.config.FeishuConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class FeishuMessageSender {

    private static final Logger log = LoggerFactory.getLogger(FeishuMessageSender.class);

    private final FeishuConfig feishuConfig;
    private final Client client;
    private final ObjectMapper mapper;

    public FeishuMessageSender(FeishuConfig feishuConfig, ObjectMapper mapper) {
        this.feishuConfig = feishuConfig;
        this.client = Client.newBuilder(feishuConfig.getAppId(), feishuConfig.getAppSecret()).build();
        this.mapper = mapper;
    }

    /**
     * 发送文本消息（非流式，保留备用）
     */
    public void replyText(String receiveIdType, String receiveId, String text) throws Exception {
        ObjectNode contentJson = mapper.createObjectNode();
        contentJson.put("text", text);

        CreateMessageReq req = CreateMessageReq.newBuilder()
                .receiveIdType(receiveIdType)
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(receiveId)
                        .msgType("text")
                        .content(mapper.writeValueAsString(contentJson))
                        .build())
                .build();

        client.im().v1().message().create(req);
    }

    /**
     * 回复卡片占位消息，自动继承消息树
     * @param targetMessageId 被回复的用户消息 ID
     * @return 发送成功的消息 ID
     */
    public String replyCardPlaceholder(String targetMessageId) throws Exception {
        String cardJson = buildCardJson("📝 思考中...");

        ReplyMessageReq req = ReplyMessageReq.newBuilder()
                .messageId(targetMessageId)
                .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                        .msgType("interactive")
                        .content(cardJson)
                        .build())
                .build();

        ReplyMessageResp resp = client.im().v1().message().reply(req);
        return resp.getData().getMessageId();
    }

    /**
     * 更新卡片消息内容（流式 token 写入）
     * @param messageId 要更新的消息 ID
     * @param text      新的文本内容
     */
    public void patchCard(String messageId, String text) throws Exception {
        String cardJson = buildCardJson(text);

        PatchMessageReq req = PatchMessageReq.newBuilder()
                .messageId(messageId)
                .patchMessageReqBody(PatchMessageReqBody.newBuilder()
                        .content(cardJson)
                        .build())
                .build();

        PatchMessageResp resp = client.im().v1().message().patch(req);
        if (!resp.success()) {
            log.warn("更新卡片失败 [{}]: code={}, msg={}", messageId, resp.getCode(), resp.getMsg());
        }
    }

    /**
     * 下载飞书图片，返回字节数组。
     */
    public byte[] downloadImage(String messageId, String imageKey) throws Exception {
        return downloadResource(messageId, imageKey, "image");
    }

    /**
     * 下载飞书文件，返回字节数组。
     */
    public byte[] downloadFile(String messageId, String fileKey) throws Exception {
        return downloadResource(messageId, fileKey, "file");
    }

    private byte[] downloadResource(String messageId, String fileKey, String type) throws Exception {
        GetMessageResourceReq req = GetMessageResourceReq.newBuilder()
                .messageId(messageId)
                .fileKey(fileKey)
                .type(type)
                .build();
        GetMessageResourceResp resp = client.im().v1().messageResource().get(req);
        return resp.getRawResponse().getBody();
    }

    private String buildCardJson(String text) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.putObject("config").put("update_multi", true);
            ArrayNode elements = root.putArray("elements");
            ObjectNode element = elements.addObject();
            element.put("tag", "markdown");
            element.put("content", text);
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("构建卡片JSON失败", e);
        }
    }
}
