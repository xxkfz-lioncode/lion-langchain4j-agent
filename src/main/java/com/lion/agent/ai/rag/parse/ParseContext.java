package com.lion.agent.ai.rag.parse;

import lombok.Builder;
import lombok.Data;

/**
 * 元素解析上下文: 原始字节流 + 所属文档信息。
 * 图片等需要落盘的元素(docId 防重名命名/激活存储路由)依赖文档级信息, 故接口签名带上下文而非裸 byte[]。
 */
@Data
@Builder
public class ParseContext {

    /** 存储类型标识(pdf/md/docx/xlsx/txt, 与 RagUtils.typeOf 一致) */
    private String fileType;

    /** 原始文件字节流 */
    private byte[] data;

    /** 所属文档 id(rag_document.id, 图片对象Key防重名前缀) */
    private Long docId;

    /** 原始文件名(图片命名基础: "3_部署手册_img1.png") */
    private String fileName;
}
