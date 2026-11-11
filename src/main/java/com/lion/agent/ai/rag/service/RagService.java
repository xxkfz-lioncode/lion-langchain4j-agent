package com.lion.agent.ai.rag.service;

import com.lion.agent.ai.rag.splitter.SplitterConfig;
import com.lion.agent.ai.rag.pojo.vo.RagDocumentVO;
import com.lion.agent.ai.rag.pojo.vo.RagSplitterVO;
import com.lion.agent.ai.rag.pojo.vo.RagUploadVO;

import java.util.List;

/**
 * RAG 知识库服务接口(基于 langchain4j 官方高层组件的文档问答)
 * <p>
 * 链路分两个阶段(上传与解析解耦, 上传零模型成本):
 * <ol>
 *   <li>上传: 仅保存原始文件 + 落 rag_document 元数据(status=待解析), 不调用任何模型;</li>
 *   <li>解析(用户显式触发): 加载已存文件 → 切分 → 分批(≤10 条/批, DashScope text-embedding-v3
 *       单请求上限)向量化写入向量库 → 回填 status=已解析与片段数; 已解析文档可重新解析(先清旧向量再重建);</li>
 *   <li>问答: EmbeddingStoreContentRetriever 检索 Top-N 相关片段(低于 minScore 过滤) → 拼入提示词 → 千问作答。</li>
 * </ol>
 * 实现: {@link com.lion.agent.ai.rag.service.impl.RagServiceImpl}。
 * 向量化模型 = DashScope text-embedding-v3; 向量库 = Milvus(MilvusEmbeddingStore, starter 自动装配)。
 */
public interface RagService {

    /**
     * 上传文档(仅保存, 不解析): 原始文件保存到 upload 目录(文件名加 docId 前缀防重名) →
     * 落 rag_document 元数据(status=待解析, 片段数 0, 切分方式留空)。
     * 切分方式在解析时由前端指定, 上传阶段零模型调用(不产生 embedding 费用)。
     *
     * @param fileName 原始文件名(带扩展名)
     * @param fileSize 文件大小(字节)
     * @param data     文件二进制内容
     * @return 新建的文档记录(状态=待解析)
     */
    RagDocumentVO uploadDocument(String fileName, long fileSize, byte[] data);

    /**
     * 解析文档入库(用户显式触发): 读取上传阶段已保存的原始文件 → 按指定方式分片
     * (每段挂 docId/fileName 元数据) → 分批向量化写入向量库 → 回填状态=已解析/片段数/切分方案。
     * <p>
     * 对已解析文档再次调用即<b>重新解析</b>: 先按 docId 清空该文档全部旧向量, 再按新配置重建
     * (换切分策略无需删除文档重传)。解析失败时文档回到"待解析"状态可重试(部分写入的向量会被清理)。
     *
     * @param id          文档 id(rag_document 主键)
     * @param splitConfig 切分方案(方式/块大小/重叠/正则, 字段可为空 = 用所选方式默认值; 传 null 用默认递归切分)
     * @return 解析结果(含本次入库片段数 / 当前总片段数 / 本次切分方式)
     */
    RagUploadVO parseDocument(Long id, SplitterConfig splitConfig);

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
