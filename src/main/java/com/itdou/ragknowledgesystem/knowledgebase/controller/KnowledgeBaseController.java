package com.itdou.ragknowledgesystem.knowledgebase.controller;

import com.itdou.ragknowledgesystem.common.response.Result;
import com.itdou.ragknowledgesystem.knowledgebase.dto.CreateKnowledgeBaseRequest;
import com.itdou.ragknowledgesystem.knowledgebase.dto.KnowledgeBaseResponse;
import com.itdou.ragknowledgesystem.knowledgebase.dto.UpdateKnowledgeBaseRequest;
import com.itdou.ragknowledgesystem.knowledgebase.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    // 创建成功返回 201，Location 头指向新资源，body 同样是 Result 信封
    @PostMapping
    public ResponseEntity<Result<KnowledgeBaseResponse>> create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        KnowledgeBaseResponse response = knowledgeBaseService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(Result.success(response));
    }

    @GetMapping
    public Result<List<KnowledgeBaseResponse>> list() {
        return Result.success(knowledgeBaseService.list());
    }

    @GetMapping("/{id}")
    public Result<KnowledgeBaseResponse> getById(@PathVariable UUID id) {
        return Result.success(knowledgeBaseService.getById(id));
    }

    @PutMapping("/{id}")
    public Result<KnowledgeBaseResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateKnowledgeBaseRequest request
    ) {
        return Result.success(knowledgeBaseService.update(id, request));
    }

    // 删除保持 204 No Content：这是接口里不返回信封的例外之一（另一个是删除文档），
    // 前端拦截器需要处理无 body 的情况
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        knowledgeBaseService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
