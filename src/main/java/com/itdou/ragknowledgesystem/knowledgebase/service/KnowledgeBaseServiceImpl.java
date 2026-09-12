package com.itdou.ragknowledgesystem.knowledgebase.service;

import com.itdou.ragknowledgesystem.knowledgebase.dto.CreateKnowledgeBaseRequest;
import com.itdou.ragknowledgesystem.knowledgebase.dto.KnowledgeBaseResponse;
import com.itdou.ragknowledgesystem.knowledgebase.dto.UpdateKnowledgeBaseRequest;
import com.itdou.ragknowledgesystem.knowledgebase.entity.KnowledgeBase;
import com.itdou.ragknowledgesystem.knowledgebase.exception.KnowledgeBaseNotFoundException;
import com.itdou.ragknowledgesystem.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
@Slf4j
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KnowledgeBaseServiceImpl(KnowledgeBaseRepository knowledgeBaseRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    //创建知识库
    @Override
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        log.info("创建知识库 name={}", request.name());

        KnowledgeBase knowledgeBase = KnowledgeBase.create(request.name(), request.description());
        KnowledgeBaseResponse response = toResponse(knowledgeBaseRepository.save(knowledgeBase));

        log.info("知识库创建完成 id={} name={} status={}", response.id(), response.name(), response.status());
        return response;
    }

    //查询全部知识库，并按时间倒序排列
    @Override
    @Transactional(readOnly = true)//只读事务，表示该方法只做查询，不需要修改数据库。
    public List<KnowledgeBaseResponse> list() {
        log.debug("查询知识库列表");

        List<KnowledgeBaseResponse> knowledgeBases = knowledgeBaseRepository
                .findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(this::toResponse)
                .toList();

        log.info("查询知识库列表完成 共 {} 条", knowledgeBases.size());
        return knowledgeBases;
    }

    //根据UUID查询单个知识库
    @Override
    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getById(UUID id) {
        log.debug("查询知识库详情 id={}", id);

        return toResponse(findById(id));
    }

    //更新已有知识库
    @Override
    public KnowledgeBaseResponse update(UUID id, UpdateKnowledgeBaseRequest request) {
        log.info("更新知识库 id={} name={} status={}", id, request.name(), request.status());

        KnowledgeBase knowledgeBase = findById(id);
        knowledgeBase.update(request.name(), request.description(), request.status());

        // 托管实体的改动由脏检查在事务提交时写库，这里不需要显式 save
        KnowledgeBaseResponse response = toResponse(knowledgeBase);
        log.info("知识库更新完成 id={}", response.id());
        return response;
    }

    //删除已有知识库
    @Override
    public void delete(UUID id) {
        log.info("删除知识库 id={}", id);

        knowledgeBaseRepository.delete(findById(id));

        // 知识库下的文档和分块由数据库外键 ON DELETE CASCADE 一并清理
        log.info("知识库删除完成 id={}", id);
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
