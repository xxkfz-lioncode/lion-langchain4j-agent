package com.lion.agent.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * 全局 Jackson 时间序列化格式。
 * <p>
 * 背景: java.time 类型默认按 ISO-8601 序列化, LocalDateTime 会输出
 * {@code 2026-11-09T20:05:16}, 前端所有表格的时间列都会带一个突兀的 "T"。
 * <p>
 * 这里统一改为 {@code yyyy-MM-dd HH:mm:ss} / {@code yyyy-MM-dd},
 * 所有接口的响应体一次性生效, 前端无需逐个格式化。
 * <p>
 * <b>注意</b>: 只定制序列化(出参), 不动反序列化(入参) —— jsr310 默认的反序列化器
 * 能同时接受 ISO 与多种宽松格式, 收紧成单一 pattern 反而可能弄坏前端传入的时间参数。
 */
@Configuration
public class JacksonFormatConfig {

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer javaTimeFormatCustomizer() {
        return builder -> builder
                .serializers(
                        new LocalDateTimeSerializer(DATE_TIME_FMT),
                        new LocalDateSerializer(DATE_FMT))
                .deserializers(
                        new LocalDateDeserializer(DATE_FMT));
    }
}
