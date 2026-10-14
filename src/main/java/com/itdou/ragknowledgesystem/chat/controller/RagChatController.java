package com.itdou.ragknowledgesystem.chat.controller;

import com.itdou.ragknowledgesystem.chat.dto.ChatMessageResponse;
import com.itdou.ragknowledgesystem.chat.dto.ChatRequest;
import com.itdou.ragknowledgesystem.chat.dto.ChatResponse;
import com.itdou.ragknowledgesystem.chat.service.RagChatService;
import com.itdou.ragknowledgesystem.common.response.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 知识库问答接口。挂在知识库路径下，与 /search 保持一致的作用域。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class RagChatController {

    private final RagChatService ragChatService;

    public RagChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @PostMapping("/{knowledgeBaseId}/chat")
    public Result<ChatResponse> chat(
            @PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody ChatRequest request
    ) {
        return Result.success(ragChatService.ask(knowledgeBaseId, request));
    }

    // 查看某个会话已经记住的历史消息，前端刷新页面后可以用它恢复对话
    @GetMapping("/{knowledgeBaseId}/conversations/{conversationId}")
    public Result<List<ChatMessageResponse>> history(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable String conversationId
    ) {
        return Result.success(ragChatService.history(knowledgeBaseId, conversationId));
    }
}
