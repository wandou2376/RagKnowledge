package com.itdou.ragknowledgesystem.chat.controller;

import com.itdou.ragknowledgesystem.chat.dto.ChatRequest;
import com.itdou.ragknowledgesystem.chat.dto.ChatResponse;
import com.itdou.ragknowledgesystem.chat.service.RagChatService;
import com.itdou.ragknowledgesystem.common.response.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
