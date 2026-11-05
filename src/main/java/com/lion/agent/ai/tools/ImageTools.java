package com.lion.agent.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.community.model.dashscope.WanxImageModel;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * 图像生成工具(供大模型 function calling 调用)。
 * <p>
 * 底层用 langchain4j-community-dashscope 的 {@link WanxImageModel} 走 DashScope 原生的
 * 通义万相异步文生图接口(提交任务 -> 轮询 -> 返回临时图片 URL, SDK 内部已完成轮询)。
 * 注意: DashScope 的 OpenAI 兼容模式<b>不支持</b> {@code /images/generations}(请求会 404),
 * 因此画图不能复用对话模型的 open-ai 端点, 必须走本模块。
 * <p>
 * 配置(直接 {@code @Value} 读取 application.yml 的 {@code lion.image.*}, 不单独建配置类):
 * <ul>
 *   <li>{@code lion.image.api-key} —— 密钥, 默认取 .env 的 {@code QWEN_API_KEY};</li>
 *   <li>{@code lion.image.model-name} —— 万相模型名, 默认 {@code wanx2.1-t2i-turbo};</li>
 *   <li>{@code lion.image.save-dir} —— 图片保存目录, 默认 {@code upload/images}。</li>
 * </ul>
 * <p>
 * 返回值处理(不同模型返回形态不同, 两种都要兼容):
 * <ul>
 *   <li>返回 base64 编码数据 —— 直接解码写文件;</li>
 *   <li>返回临时 URL —— 下载字节流后写文件。</li>
 * </ul>
 * <p>
 * 实现约定(与 {@link WeatherTools} 一致): 本类<b>不加</b> AOP 注解, 避免 CGLIB 代理
 * 导致 LangChain4j 扫描不到 @Tool 方法; 失败时不抛异常而是返回错误文案,
 * 由大模型把失败原因组织成回复告诉用户。
 */
@Slf4j
@Component
public class ImageTools {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path saveDir;
    private final WanxImageModel imageModel;

    public ImageTools(
            @Value("${lion.image.api-key:}") String apiKey,
            @Value("${lion.image.model-name:wanx2.1-t2i-turbo}") String modelName,
            @Value("${lion.image.save-dir:upload/images}") String saveDir) {
        this.saveDir = Path.of(saveDir);
        // build() 只做参数封装, 不发起网络请求, 构造期构建即可;
        // generate() 内部由 dashscope-sdk 提交异步任务并轮询结果(默认 1024*1024)
        this.imageModel = WanxImageModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    /**
     * 根据文字描述生成一张图片, 保存到本地并返回文件路径。
     *
     * @param description 图片内容的文字描述(中文/英文均可)
     * @return 保存路径, 或失败原因文案(供大模型组织回复)
     */
    @Tool("根据文字描述生成一张图片, 保存到本地并返回图片文件路径; 当用户要求画图/生成图片/画一张...时调用")
    public String generateImage(@P("图片内容的文字描述, 描述越具体生成效果越好") String description) {
        if (description == null || description.isBlank()) {
            return "未提供图片描述, 请先告诉我您想生成什么内容。";
        }
        try {
            Response<Image> response = imageModel.generate(description.trim());
            byte[] bytes = extractBytes(response.content());
            if (bytes == null) {
                return "图像模型未返回图片数据, 生成失败。";
            }

            Files.createDirectories(saveDir);
            Path file = saveDir.resolve("image-" + LocalDateTime.now().format(FILE_TIME) + ".png");
            Files.write(file, bytes);

            log.info("[图像生成] 描述: {} -> 已保存: {}", description, file.toAbsolutePath());
            return "图片已生成并保存到: " + file.toAbsolutePath();
        } catch (Exception e) {
            log.error("[图像生成] 失败, 描述: {}", description, e);
            return "图片生成失败: " + e.getMessage();
        }
    }

    /**
     * 从模型返回的 {@link Image} 中取出图片字节:
     * 优先取 base64 数据, 否则从 URL 下载。
     */
    private byte[] extractBytes(Image image) throws Exception {
        String base64 = image.base64Data();
        if (base64 != null && !base64.isBlank()) {
            return Base64.getDecoder().decode(base64);
        }
        if (image.url() != null) {
            try (var in = image.url().toURL().openStream()) {
                return in.readAllBytes();
            }
        }
        return null;
    }
}
