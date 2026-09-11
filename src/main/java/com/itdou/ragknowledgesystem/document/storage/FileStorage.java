package com.itdou.ragknowledgesystem.document.storage;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorage {

    String storePdf(MultipartFile file);

    //删除文件
    void delete(String storageKey);
}
