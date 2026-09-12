package com.itdou.ragknowledgesystem.document.storage;

import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

public interface FileStorage {

    String storePdf(MultipartFile file);

    //删除文件
    void delete(String storageKey);

    /*
     * 返回已存文件的本地路径，供解析器读取。
     * 阶段 3 的实现是本地磁盘存储，PDFBox 直接读文件比读 InputStream 更省内存；
     * 将来换成 MinIO 等对象存储时，这里需要改成流式下载或临时文件。
     */
    Path loadPath(String storageKey);
}
