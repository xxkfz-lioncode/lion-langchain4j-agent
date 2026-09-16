package com.lion.agent.structured.extractor;

import com.lion.agent.structured.StructuredOutputService;
import com.lion.agent.structured.StructuredOutputStrategy;
import com.lion.agent.structured.StructuredTarget;

/**
 * 方式层的统一契约: 一种"方式"一个实现类。
 * <p>
 * 之所以按方式拆成多个实现而不是写在一个类里, 是为了让三种方式的差异一眼可见
 * (看实现类就知道该方式到底给模型发了什么请求), 同时让
 * {@link StructuredOutputService} 可以按策略路由、按顺序自动降级。
 * <p>
 * 新增一种方式(例如某个厂商的私有结构化协议)只需在本包再写一个实现类并注册为 Bean,
 * 门面会自动收集, 无需修改任何已有代码。
 */
public interface StructuredOutputExtractor {

    /** 本实现对应的方式 */
    StructuredOutputStrategy strategy();

    /**
     * 把自由文本抽取成目标类型的对象。
     *
     * @param input  待抽取的原始文本(自然语言)
     * @param target 目标类型描述(类型 + schema + 字段说明)
     * @return 反序列化后的对象
     * @throws com.lion.agent.common.exception.BusinessException 模型调用失败或输出无法解析为目标类型
     */
    <T> T extract(String input, StructuredTarget<T> target);
}