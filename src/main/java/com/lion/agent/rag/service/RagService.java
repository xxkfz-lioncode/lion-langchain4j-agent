package com.lion.agent.rag.service;

import com.lion.agent.rag.splitter.SplitterConfig;
import com.lion.agent.rag.vo.RagDocumentVO;
import com.lion.agent.rag.vo.RagSplitterVO;
import com.lion.agent.rag.vo.RagUploadVO;

import java.util.List;

/**
 * RAG 知识库服务接口(基于 langchain4j 官方高层组件的文档问答)
 * <p>
 * 链路(与官方模板一致):
 * 1. 加载文档: FileSystemDocumentLoader + 解析器(PDF 用 ApachePdfBoxDocumentParser, 纯文本直接读取);
 * 2. 切分: 支持前端自选方式(递归/段落/句子/按行/按词/字符/正则, 见
 *    {@link com.lion.agent.rag.splitter.SplitterType}), 块大小/重叠/正则可覆盖, 未传用该方式默认值;
 * 3. 摄入: 分片后分批(≤10 条/批, DashScope text-embedding-v3 单请求上限)向量化并写入向量库,
 *    每个片段携带 docId/fileName 元数据, 支持按文件区分/删除;
 * 4. 问答: EmbeddingStoreContentRetriever 检索 Top-N 相关片段(低于 minScore 过滤) → 拼入提示词 → 千问作答。
 * <p>
 * 实现: {@link com.lion.agent.rag.service.impl.RagServiceImpl}。
 * 向量化模型 = DashScope text-embedding-v3; 向量库 = Milvus(MilvusEmbeddingStore, starter 自动装配)。
 */
public interface RagService {

    /**
     * 上传文件入库(页面用, 追加式): 原始文件保存到 upload 目录(文件名加 docId 前缀防重名) →
     * 落 rag_document 元数据(含保存路径与切分方案, 拿 docId) → 按指定方式分片(每段挂 docId/fileName 元数据)
     * → 分批向量化写入向量库 → 回填片段数; 任一步失败回滚(删记录 / 清向量 / 删文件)。
     * <ul>
     *     <li>.pdf → 临时落盘后走 ApachePdfBoxDocumentParser 解析;</li>
     *     <li>.txt / .md / .markdown 及其他文本 → UTF-8 直接读取。</li>
     * </ul>
     *
     * @param fileName    原始文件名(带扩展名)
     * @param fileSize    文件大小(字节)
     * @param data        文件二进制内容
     * @param splitConfig 切分方案(方式/块大小/重叠/正则, 字段可为空 = 用所选方式默认值; 传 null 用默认递归切分)
     * @return 上传结果(含文档 id / 片段数 / 当前总片段数 / 本次切分方式)
     */
    RagUploadVO ingestUpload(String fileName, long fileSize, byte[] data, SplitterConfig splitConfig)
            throws Exception;

    /**
     * 可选的文档切分方式列表(前端下拉框数据源, 由 SplitterType 枚举生成, 含默认参数与正则提示)
     */
    List<RagSplitterVO> listSplitters();

    /**
     * 文档列表(按上传时间倒序, 供前端文件列表展示)
     */
    List<RagDocumentVO> listDocuments();

    /**
     * 删除单个文档: 先删其全部向量(按 docId 元数据过滤), 再删元数据记录
     *
     * @param id 文档 id
     */
    void deleteDocument(Long id);

    /**
     * 知识库问答: ContentRetriever 检索相关片段 → 组装参考上下文 → 千问生成回答(阻塞式整段返回)
     *
     * @param question 用户问题
     * @return 基于知识库内容的回答
     */
    String answer(String question);

    /** 当前知识库总片段数(数据库中成功文档片段数之和) */
    long size();
}
