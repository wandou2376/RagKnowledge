package com.itdou.ragknowledgesystem.document.service;

import com.itdou.ragknowledgesystem.document.dto.DocumentResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface DocumentService {

    DocumentResponse upload(UUID knowledgeBaseId, MultipartFile file);

    List<DocumentResponse> listByKnowledgeBaseId(UUID knowledgeBaseId);

    DocumentResponse getById(UUID documentId);

    void delete(UUID documentId);

    /*
     * 删除某个知识库下的全部文档记录及其物理文件，返回清理的文档数。
     * 知识库删除前要调用它：数据库的行虽然可以由外键级联清理，
     * 但磁盘上的文件不在数据库里，只能由应用自己删，否则文件会永远留在磁盘上。
     */
    int deleteAllUnderKnowledgeBase(UUID knowledgeBaseId);
}
