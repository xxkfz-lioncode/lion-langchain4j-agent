package com.lion.agent.ai.rag.service;

import com.lion.agent.ai.rag.splitter.SplitterConfig;
import com.lion.agent.ai.rag.pojo.vo.RagAnswerVO;
import com.lion.agent.ai.rag.pojo.vo.RagDocumentVO;
import com.lion.agent.ai.rag.pojo.vo.RagFileVO;
import com.lion.agent.ai.rag.pojo.vo.RagImagePageVO;
import com.lion.agent.ai.rag.pojo.vo.RagParseProgressVO;
import com.lion.agent.ai.rag.pojo.vo.RagSegmentPageVO;
import com.lion.agent.ai.rag.pojo.vo.RagSplitterVO;

import java.util.List;

/**
 * RAG 知识库服务接口(基于 langchain4j 官方高层组件的文档问答)
 * <p>
 * 链路分两个阶段(上传与解析解耦, 上传零模型成本):
 * <ol>
 *   <li>上传: 仅保存原始文件 + 落 rag_document 元数据(status=待解析), 不调用任何模型;</li>
 *   <li>解析(用户显式触发, 异步): 校验后置"解析中"并立即返回, 由 RagParseWorker 独立线程池执行
 *       切分 → 分批(≤10 条/批, DashScope 单请求上限)向量化, 进度实时回写; 已解析文档可重新解析;</li>
 *   <li>问答: 直接检索向量库取 Top-N 相关片段(低于 minScore 过滤) → 拼入提示词 → 千问作答,
 *       片段随回答作为引用来源返回。</li>
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
     * 触发解析(异步): 校验 + 置"解析中"状态后立即返回, 切分/向量化由 {@code RagParseWorker}
     * 在独立线程池执行, 进度/结果/失败原因实时回写 rag_document(前端轮询文档列表可见)。
     * <p>
     * 对已解析文档再次调用即<b>重新解析</b>: 先按 docId 清空该文档全部旧向量, 再按新配置重建
     * (换切分策略无需删除文档重传)。解析失败时状态置为"失败"并落库错误原因, 可再次触发重试。
     *
     * @param id          文档 id(rag_document 主键)
     * @param splitConfig 切分方案(方式/块大小/重叠/正则, 字段可为空 = 用所选方式默认值; 传 null 用默认递归切分)
     * @return 触发后的进度快照(状态=解析中, progress=0)
     */
    RagParseProgressVO parseDocument(Long id, SplitterConfig splitConfig);

    /**
     * 可选的文档切分方式列表(前端下拉框数据源, 由 SplitterType 枚举生成, 含默认参数与正则提示)
     */
    List<RagSplitterVO> listSplitters();

    /**
     * 文档列表(按编号倒序, 供前端文件列表展示)
     */
    List<RagDocumentVO> listDocuments();

    /**
     * 文档片段明细(片段抽屉): 从 rag_segment 表按 seq 升序物理分页,
     * 支持按片段原文关键字模糊过滤(检查切分质量用)
     *
     * @param docId  文档 id(rag_document 主键)
     * @param page   页码(1 起)
     * @param size   每页条数(1~100, 超界自动修正)
     * @param keyword 片段内容关键字(可空 = 不过滤)
     * @return 分页片段明细
     */
    RagSegmentPageVO listSegments(Long docId, long page, long size, String keyword);

    /**
     * 文档图片分页(图片资源页): rag_image 表按 id 倒序物理分页, 支持按文档/文件名/描述状态过滤;
     * 每条补齐所属文档名(批量查, 避免 N+1)。
     *
     * @param docId        所属文档 id(可空 = 全部文档)
     * @param keyword      文件名关键字(可空)
     * @param onlyNoCaption 是否仅看未描述的图片(VLM 落地前基本全为 true 的结果)
     */
    RagImagePageVO listImages(Long docId, long page, long size, String keyword, boolean onlyNoCaption);

    /**
     * 读取图片字节(图片资源页预览/下载): 按对象Key 在已注册的存储实现中自动定位(local/minio 均兼容)
     */
    RagFileVO downloadImage(Long id);

    /**
     * 删除单个文档: 先删其全部向量(按 docId 元数据过滤), 再删元数据记录
     *
     * @param id 文档 id
     */
    void deleteDocument(Long id);

    /**
     * 下载文档原文(管理页"查看原文"用): 按 rag_document.storage_type 路由到对应存储读取
     *
     * @param id 文档 id
     * @return 文件内容 + 文件名/类型(控制层组装下载响应)
     */
    RagFileVO downloadDocument(Long id);

    /**
     * 知识库问答: ContentRetriever 检索相关片段 → 组装参考上下文 → 千问生成回答(阻塞式整段返回)
     *
     * @param question 用户问题
     * @return 回答文本 + 引用来源列表(检索层注入, 含文档名/匹配度/片段原文)
     */
    RagAnswerVO answer(String question);

    /** 当前知识库总片段数(数据库中成功文档片段数之和) */
    long size();
}
