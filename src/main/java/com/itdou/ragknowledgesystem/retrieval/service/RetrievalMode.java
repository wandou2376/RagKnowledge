package com.itdou.ragknowledgesystem.retrieval.service;

/**
 * 检索模式。
 *
 * 保留单通道模式不只是为了调试，更是为了让评测能分别量出每个通道的贡献——
 * 只有知道向量和关键词各自单独的效果，才能判断融合到底有没有用。
 */
public enum RetrievalMode {

    // 只用向量召回（语义相似）
    VECTOR,

    // 只用关键词召回（字面命中，对专有名词、编号、错误码更有效）
    KEYWORD,

    // 两路召回后按 RRF 融合，默认模式
    HYBRID
}
