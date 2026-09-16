package com.itdou.ragknowledgesystem.document.repository;

import java.util.UUID;

/**
 * 向量检索的查询投影。
 *
 * 之所以用接口投影而不是直接返回实体：检索结果多了一个相似度分数，实体上没有这个字段。
 * 别名必须和 getter 一一对应，所以 SQL 里用带引号的驼峰别名，避免 PostgreSQL 把未加引号的
 * 标识符折叠成小写后匹配不上。
 */
public interface ChunkSearchProjection {

    UUID getChunkId();

    UUID getDocumentId();

    int getChunkIndex();

    String getContent();

    Integer getPageNumberStart();

    Integer getPageNumberEnd();

    int getTokenCount();

    String getOriginalFileName();

    // 余弦相似度，1 - 余弦距离，越接近 1 越相关
    double getScore();
}
