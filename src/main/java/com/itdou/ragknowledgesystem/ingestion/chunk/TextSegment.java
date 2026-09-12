package com.itdou.ragknowledgesystem.ingestion.chunk;

/**
 * 分块的最小单位：一行清洗后的文本，带上它所在的页码和 token 数。
 *
 * 用「行」而不是「段落」作为最小单位，是因为 PDFBox 提取出来的文本经常整页没有空行，
 * 按空行切段落会把一整页当成一个段落，反而不好控制块大小。
 */
record TextSegment(int pageNumber, String text, int tokenCount) {
}
