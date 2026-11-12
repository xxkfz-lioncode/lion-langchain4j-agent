package com.lion.agent.ai.rag.pojo.vo;

import lombok.Data;

/**
 * 文档原文下载 VO(服务层组装, 控制层转 ResponseEntity 输出)
 */
@Data
public class RagFileVO {

    /** 文件名(带扩展名, 下载时作为保存名) */
    private String fileName;

    /** 文件类型(扩展名小写, 决定 Content-Type) */
    private String fileType;

    /** 文件二进制内容 */
    private byte[] data;
}
