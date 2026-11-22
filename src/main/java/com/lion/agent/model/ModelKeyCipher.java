package com.lion.agent.model;

import cn.hutool.crypto.Mode;
import cn.hutool.crypto.Padding;
import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * API Key 加解密器(AES-256-CBC): 模型密钥以密文落库, 明文只存在于
 * ① 前端表单提交瞬间 ② 构建模型实例发给供应商的 HTTP 头里, 其余环节(存储/日志/接口响应)均为密文或脱敏。
 * <p>
 * 密钥来源: {@code lion.model-config.key-secret}(生产必须用环境变量 MODEL_KEY_SECRET 覆盖);
 * 通过 SHA-256 派生 32 字节 AES key + 取前 16 字节作 IV, 保证任意长度的 secret 都可用。
 * <p>
 * 安全说明: IV 由 secret 派生(固定), 同明文加密结果相同——教学/内网可接受;
 * 更高要求可升级为"每条记录随机 IV 随密文同存"的方案, 解密逻辑向后兼容。
 */
@Slf4j
@Component
public class ModelKeyCipher {

    private static final String DEV_FALLBACK_SECRET = "lion-dev-model-secret-2026";

    private final AES aes;

    public ModelKeyCipher(@Value("${lion.model-config.key-secret:}") String secret) {
        String effective = (secret == null || secret.isBlank()) ? DEV_FALLBACK_SECRET : secret;
        if (secret == null || secret.isBlank()) {
            log.warn("lion.model-config.key-secret 未配置, 使用开发默认密钥 —— 生产环境必须通过环境变量 MODEL_KEY_SECRET 覆盖!");
        }
        // sha256(String) 返回 64 位 hex 字符串; 必须 decodeHex 成 32 字节二进制才是合法的 AES-256 密钥
        // (直接 getBytes() 会得到 64 字节 ASCII, 触发 InvalidAlgorithmParameterException: Key length not 128/192/256 bits)
        byte[] key = HexUtil.decodeHex(SecureUtil.sha256(effective));
        this.aes = new AES(Mode.CBC, Padding.PKCS5Padding, key, Arrays.copyOf(key, 16));
    }

    /** 明文 → 密文(Base64); 空值原样返回 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return plain;
        }
        return aes.encryptBase64(plain);
    }

    /** 密文 → 明文; 空值原样返回 */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return cipherText;
        }
        return aes.decryptStr(cipherText);
    }
}
