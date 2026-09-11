package com.itdou.ragknowledgesystem.knowledgebase.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_base", schema = "rag")
public class KnowledgeBase {

    @Id//标记主键字段。
    @GeneratedValue//表示主键值由 JPA/Hibernate 自动生成。
    @UuidGenerator
    private UUID id;//使用 Hibernate 的 UUID 生成器生成主键。

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)//将枚举按字符串保存，例如保存 ACTIVE，而不是数字序号。
    @Column(nullable = false, length = 20)
    private KnowledgeBaseStatus status;

    // 当前用户认证模块还未完成，因此 createdBy 暂时允许为空。
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeBase() {
    }

    //创建新的数据库
    public static KnowledgeBase create(String name, String description) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.name = name;
        knowledgeBase.description = description;
        knowledgeBase.status = KnowledgeBaseStatus.ACTIVE;
        return knowledgeBase;
    }

    //修改已有直属库
    public void update(String name, String description, KnowledgeBaseStatus status) {
        this.name = name;
        this.description = description;
        this.status = status;
    }

    @PrePersist//插入数据前自动执行，这里用于初始化创建时间和更新时间。
    void initializeTimestamps() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate//更新数据前自动执行，这里用于刷新更新时间。
    void updateTimestamp() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public KnowledgeBaseStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
