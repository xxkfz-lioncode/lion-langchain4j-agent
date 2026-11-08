package com.lion.agent.text2sql.pipeline;

/**
 * 步骤元信息(用于向外部推送步骤清单)。
 *
 * @param id    步骤唯一标识
 * @param title 展示标题
 */
public record StepMeta(String id, String title) {
}
