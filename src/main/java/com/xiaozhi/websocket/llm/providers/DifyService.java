package com.xiaozhi.websocket.llm.providers;

import com.xiaozhi.entity.SysMessage;
import com.xiaozhi.websocket.llm.api.AbstractLlmService;
import com.xiaozhi.websocket.llm.api.StreamResponseListener;
import com.xiaozhi.websocket.llm.memory.ModelContext;
import io.github.imfangs.dify.client.DifyChatClient;
import io.github.imfangs.dify.client.DifyClientFactory;
import io.github.imfangs.dify.client.callback.ChatflowStreamCallback;
import io.github.imfangs.dify.client.enums.ResponseMode;
import io.github.imfangs.dify.client.event.*;
import io.github.imfangs.dify.client.model.chat.ChatMessage;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class DifyService extends AbstractLlmService {
    private DifyChatClient chatClient;

    /**
     * 构造函数
     *
     * @param endpoint  API端点
     * @param appId
     * @param apiKey    API密钥
     * @param apiSecret
     * @param model     模型名称
     */
    public DifyService(String endpoint, String appId, String apiKey, String apiSecret, String model) {
        super(endpoint, appId, apiKey, apiSecret, model);

        chatClient = DifyClientFactory.createChatClient(endpoint, apiKey);
    }

    @Override
    public String chat(List<Map<String, Object>> messages) throws IOException {
        return "";
    }

    @Override
    protected void chatStream(List<Map<String, Object>> messages, StreamResponseListener streamListener, ModelContext modelContext) throws IOException {
        if (messages == null || messages.isEmpty()) {
            throw new IOException("消息列表不能为空");
        }

        // 通知开始
        streamListener.onStart();

        // 创建唯一的用户ID
        String userId = "user_xz_" + modelContext.getDeviceId().replace(":", "");
        Map<String, Object> map = messages.get(messages.size() - 1);
        ChatMessage message = ChatMessage.builder()
                .user(userId)
                .query(map.get("content").toString())
                .responseMode(ResponseMode.STREAMING)
                .build();

        StringBuilder fullResponse = new StringBuilder();
        // 发送流式消息
        chatClient.sendChatMessageStream(message, new ChatflowStreamCallback() {
            @Override
            public void onMessage(MessageEvent event) {
                fullResponse.append(event.getAnswer());
                streamListener.onToken(event.getAnswer());
            }

            @Override
            public void onAgentMessage(AgentMessageEvent event) {
                fullResponse.append(event.getAnswer());
                streamListener.onToken(event.getAnswer());
            }

            @Override
            public void onMessageReplace(MessageReplaceEvent event) {
                logger.info("onMessageReplace: {}", event.getAnswer());
            }

            @Override
            public void onTTSMessage(TtsMessageEvent event) {
                logger.info("onTTSMessage");
            }

            @Override
            public void onMessageEnd(MessageEndEvent event) {
                // 通知完成
                streamListener.onComplete(fullResponse.toString(), messages, DifyService.this, SysMessage.MESSAGE_TYPE_NORMAL);
            }

            @Override
            public void onError(ErrorEvent event) {
                logger.error("错误: {}", event.getMessage());
            }

            @Override
            public void onException(Throwable throwable) {
                logger.error("异常: {}", throwable.getMessage());
                streamListener.onError(new IOException(throwable));
            }
            
            @Override
            public void onWorkflowStarted(WorkflowStartedEvent event) {
                logger.debug("工作流开始: {}", event);
            }
            
            @Override
            public void onNodeStarted(NodeStartedEvent event) {
                logger.debug("节点开始: {}", event);
            }
            
            @Override
            public void onNodeFinished(NodeFinishedEvent event) {
                logger.debug("节点完成: {}", event);
            }
            
            @Override
            public void onWorkflowFinished(WorkflowFinishedEvent event) {
                logger.debug("工作流完成: {}", event);
            }
            
            // 实现其他必要的回调方法
            @Override
            public void onMessageFile(MessageFileEvent event) {
                logger.debug("消息文件: {}", event);
            }
            
            @Override
            public void onTTSMessageEnd(TtsMessageEndEvent event) {
                logger.debug("TTS消息结束: {}", event);
            }
            
            @Override
            public void onAgentThought(AgentThoughtEvent event) {
                logger.debug("代理思考: {}", event);
            }
            
            @Override
            public void onPing(PingEvent event) {
                // 心跳事件，通常不需要特别处理
            }
        });
    }

    @Override
    public String getProviderName() {
        return "dify";
    }
}