package com.lion.agent.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * .env 文件加载器
 * <p>
 * 在 Spring Boot 上下文刷新前, 将项目根目录 .env 中的 KEY=VALUE 解析为系统属性,
 * 供 application.yml 中 ${KEY} 占位符引用。
 * 优先级: 操作系统真实环境变量 &gt; .env 文件(仅当系统属性/环境变量均未定义时才写入)。
 */
public final class EnvFileLoader {

    private EnvFileLoader() {
    }

    public static void load() {
        Path dotEnv = findDotEnv();
        if (dotEnv == null) {
            System.out.println("[EnvFileLoader] 未找到 .env 文件, 使用默认配置启动(请复制 .env.example 为 .env)。");
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(dotEnv, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseAndSet(line);
            }
            System.out.println("[EnvFileLoader] 已加载环境变量文件: " + dotEnv.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[EnvFileLoader] 读取 .env 失败: " + e.getMessage());
        }
    }

    private static void parseAndSet(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        int idx = line.indexOf('=');
        if (idx <= 0) {
            return;
        }
        String key = line.substring(0, idx).trim();
        String value = line.substring(idx + 1).trim();
        // 去掉可能存在的成对引号
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1);
        }
        if (key.isEmpty()) {
            return;
        }
        // 让操作系统环境变量优先于 .env
        if (System.getProperty(key) == null && System.getenv(key) == null) {
            System.setProperty(key, value);
        }
    }

    /**
     * 从当前工作目录向上查找 .env(兼容 IDE 以模块目录为工作目录的情况)
     */
    private static Path findDotEnv() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = current.resolve(".env");
            if (Files.exists(candidate)) {
                return candidate;
            }
            if (current.getParent() == null) {
                break;
            }
            current = current.getParent();
        }
        return null;
    }
}
