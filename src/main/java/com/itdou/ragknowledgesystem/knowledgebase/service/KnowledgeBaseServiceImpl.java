package com.itdou.ragknowledgesystem.knowledgebase.service;

import com.itdou.ragknowledgesystem.knowledgebase.dto.CreateKnowledgeBaseRequest;
import com.itdou.ragknowledgesystem.knowledgebase.dto.KnowledgeBaseResponse;
import com.itdou.ragknowledgesystem.knowledgebase.dto.UpdateKnowledgeBaseRequest;
import com.itdou.ragknowledgesystem.knowledgebase.entity.KnowledgeBase;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KnowledgeBaseServiceImpl(KnowledgeBaseRepository knowledgeBaseRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    //创建知识库
    @Override
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        KnowledgeBase knowledgeBase = KnowledgeBase.create(request.name(), request.description());
        return toResponse(knowledgeBaseRepository.save(knowledgeBase));
    }


    //查询全部知识库，并按时间倒序排列
    @Override
    @Transactional(readOnly = true)//只读事务，表示该方法只做查询，不需要修改数据库。
    public List<KnowledgeBaseResponse> list() {
        return knowledgeBaseRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    //根据UUID查询单个知识库
    @Override
    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getById(UUID id) {
        return toResponse(findById(id));
    }

    //更新已有知识库
    @Override
    public KnowledgeBaseResponse update(UUID id, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase knowledgeBase = findById(id);
        knowledgeBase.update(request.name(), request.description(), request.status());

        // Dirty checking writes this managed entity at transaction commit.
        return toResponse(knowledgeBase);
    }

    //删除已有知识库
    @Override
    public void delete(UUID id) {
        knowledgeBaseRepository.delete(findById(id));
    }

    private KnowledgeBase findById(UUID id) {
        return knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new KnowledgeBaseNotFoundException(id));//有就返回对象，没有就抛异常
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getStatus(),
                knowledgeBase.getCreatedAt(),
                knowledgeBase.getUpdatedAt()
        );
    }
}
