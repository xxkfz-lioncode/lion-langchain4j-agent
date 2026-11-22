package com.lion.agent.model.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.model.ModelKeyCipher;
import com.lion.agent.model.ModelRegistry;
import com.lion.agent.model.dto.ModelConfigDTO;
import com.lion.agent.model.dto.ModelSiteUpdateDTO;
import com.lion.agent.model.entity.ModelConfigEntity;
import com.lion.agent.model.mapper.ModelConfigMapper;
import com.lion.agent.model.service.ModelConfigService;
import com.lion.agent.model.vo.ModelConfigVO;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型配置管理实现。
 * 安全红线: 明文 key 只在 create/update 加密与 testConnect 构建临时模型时短暂存在,
 * entity 存密文, VO 输出前经 ModelConfigVO.mask 脱敏;
 * 所有写操作后调用 ModelRegistry.invalidate, 保证"改了即生效"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigServiceImpl implements ModelConfigService {

    /** 支持的用途集合(一期实际接入 chat) */
    private static final Set<String> PURPOSES = Set.of("chat", "embedding", "rerank", "image");

    private final ModelConfigMapper mapper;
    private final ModelKeyCipher cipher;
    private final ModelRegistry registry;

    @Override
    public List<ModelConfigVO> list(String purpose) {
        LambdaQueryWrapper<ModelConfigEntity> qw = new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(StringUtils.hasText(purpose), ModelConfigEntity::getPurpose, purpose)
                .orderByDesc(ModelConfigEntity::getIsDefault)
                .orderByDesc(ModelConfigEntity::getId);
        return mapper.selectList(qw).stream().map(this::toVo).toList();
    }

    @Override
    public Long create(ModelConfigDTO dto) {
        validate(dto, null);
        String keyCipher;
        if (StringUtils.hasText(dto.getApiKey())) {
            keyCipher = cipher.encrypt(dto.getApiKey().trim());
        } else if (dto.getCopyKeyFrom() != null) {
            // 复制新增: 直接复用源配置的密文(明文不解密、不回显), 用户只改名称/模型名即可快速新增
            ModelConfigEntity source = requireById(dto.getCopyKeyFrom());
            keyCipher = source.getApiKeyCipher();
        } else {
            throw new BusinessException("新建模型必须填写 API Key");
        }
        ModelConfigEntity e = new ModelConfigEntity();
        applyDto(e, dto);
        e.setApiKeyCipher(keyCipher);
        e.setEnabled(1);
        e.setIsDefault(0);
        e.setStatus("untested");
        e.setCreateTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        mapper.insert(e);
        return e.getId();
    }

    @Override
    public void update(Long id, ModelConfigDTO dto) {
        validate(dto, id);
        ModelConfigEntity e = requireById(id);
        applyDto(e, dto);
        // apiKey 留空 = 不修改原密钥(密钥不回显, 留空即保留)
        if (StringUtils.hasText(dto.getApiKey())) {
            e.setApiKeyCipher(cipher.encrypt(dto.getApiKey().trim()));
        }
        // 配置变更后旧的测试结果失效, 回到未测试(保存后前端会自动重新测一次)
        e.setStatus("untested");
        e.setUpdateTime(LocalDateTime.now());
        mapper.updateById(e);
        registry.invalidate(id);
    }

    @Override
    public void delete(Long id) {
        ModelConfigEntity e = requireById(id);
        if (Integer.valueOf(1).equals(e.getIsDefault())) {
            throw new BusinessException("默认模型不能删除, 请先把默认设给其他模型");
        }
        mapper.deleteById(id);
        registry.invalidate(id);
    }

    @Override
    public void toggleEnabled(Long id, boolean enabled) {
        ModelConfigEntity e = requireById(id);
        e.setEnabled(enabled ? 1 : 0);
        e.setUpdateTime(LocalDateTime.now());
        mapper.updateById(e);
        registry.invalidate(id);
    }

    @Override
    public void setDefault(Long id) {
        ModelConfigEntity e = requireById(id);
        if (!Integer.valueOf(1).equals(e.getEnabled())) {
            throw new BusinessException("停用状态的模型不能设为默认, 请先启用");
        }
        // 同用途下先清旧默认再设新默认, 保证"每个 purpose 至多一条默认"
        ModelConfigEntity clear = new ModelConfigEntity();
        clear.setIsDefault(0);
        mapper.update(clear, new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getPurpose, e.getPurpose())
                .eq(ModelConfigEntity::getIsDefault, 1));
        e.setIsDefault(1);
        e.setUpdateTime(LocalDateTime.now());
        mapper.updateById(e);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateSite(ModelSiteUpdateDTO dto) {
        String oldUrl = dto.getBaseUrl().trim();
        String newUrl = dto.getNewBaseUrl().trim();
        List<ModelConfigEntity> rows = mapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getBaseUrl, oldUrl));
        if (rows.isEmpty()) {
            throw new BusinessException("站点不存在: " + oldUrl);
        }
        // 改地址时不能撞上另一个已存在的站点(否则两个站点会合并, 违反主从语义)
        if (!newUrl.equals(oldUrl)) {
            Long dup = mapper.selectCount(new LambdaQueryWrapper<ModelConfigEntity>()
                    .eq(ModelConfigEntity::getBaseUrl, newUrl));
            if (dup != null && dup > 0) {
                throw new BusinessException("目标站点地址已存在, 请直接在该站点下新建模型");
            }
        }
        String keyCipher = StringUtils.hasText(dto.getApiKey()) ? cipher.encrypt(dto.getApiKey().trim()) : null;
        for (ModelConfigEntity e : rows) {
            e.setBaseUrl(newUrl);
            if (keyCipher != null) {
                e.setApiKeyCipher(keyCipher);
            }
            e.setStatus("untested");
            e.setUpdateTime(LocalDateTime.now());
            mapper.updateById(e);
            registry.invalidate(e.getId());
        }
        return rows.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteSite(String baseUrl) {
        List<ModelConfigEntity> rows = mapper.selectList(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getBaseUrl, baseUrl));
        if (rows.isEmpty()) {
            throw new BusinessException("站点不存在: " + baseUrl);
        }
        // 删站是明确意图, 允许连同默认模型一起删(业务回退 yml 系统默认), 前端确认框已做提示
        for (ModelConfigEntity e : rows) {
            mapper.deleteById(e.getId());
            registry.invalidate(e.getId());
        }
        return rows.size();
    }
    // ==================== 连通性测试 ====================

    @Override
    public Map<String, Object> testConnect(ModelConfigDTO dto) {
        if (!StringUtils.hasText(dto.getBaseUrl()) || !StringUtils.hasText(dto.getModelName())) {
            throw new BusinessException("测试连通需要站点地址与模型名");
        }
        Map<String, Object> result = new HashMap<>();
        long start = System.currentTimeMillis();
        try {
            // 临时实例(不进缓存、不落库); 15s 超时 + 不重试, 快速给出结论
            ChatModel4Test model = new ChatModel4Test(dto);
            String reply = model.chat("ping");
            long elapsed = System.currentTimeMillis() - start;
            result.put("ok", true);
            result.put("elapsedMs", elapsed);
            result.put("reply", reply == null || reply.isBlank() ? "(空响应)"
                    : reply.substring(0, Math.min(reply.length(), 100)));
            result.put("error", "");
        } catch (Exception e) {
            result.put("ok", false);
            result.put("elapsedMs", System.currentTimeMillis() - start);
            result.put("reply", "");
            result.put("error", e.getMessage());
        }
        // 已落库配置的测试(列表快速测试/编辑态测试/保存后自动测试): 把结果持久化到 status,
        // 前端刷新后状态标签由"未测试"变为"已连通/失败"; 草稿测试(新建态无 id)不落库
        if (dto.getId() != null) {
            ModelConfigEntity db = mapper.selectById(dto.getId());
            if (db != null) {
                db.setStatus(Boolean.TRUE.equals(result.get("ok")) ? "ok" : "fail");
                db.setUpdateTime(LocalDateTime.now());
                mapper.updateById(db);
            }
        }
        return result;
    }

    /** 测试用 key: 表单新填的优先; 编辑态留空则用库里已存密文解出 */
    private String resolveKeyForTest(ModelConfigDTO dto) {
        if (StringUtils.hasText(dto.getApiKey())) {
            return dto.getApiKey().trim();
        }
        if (dto.getId() != null) {
            ModelConfigEntity db = mapper.selectById(dto.getId());
            if (db != null && StringUtils.hasText(db.getApiKeyCipher())) {
                return cipher.decrypt(db.getApiKeyCipher());
            }
        }
        throw new BusinessException("请填写 API Key");
    }

    // ==================== 内部 ====================

    /** 名称唯一性 + 用途合法性 */
    private void validate(ModelConfigDTO dto, Long selfId) {
        if (dto.getPurpose() == null || !PURPOSES.contains(dto.getPurpose())) {
            throw new BusinessException("用途不合法");
        }
        Long count = mapper.selectCount(new LambdaQueryWrapper<ModelConfigEntity>()
                .eq(ModelConfigEntity::getName, dto.getName().trim())
                .ne(selfId != null, ModelConfigEntity::getId, selfId));
        if (count != null && count > 0) {
            throw new BusinessException("模型名称已存在: " + dto.getName());
        }
    }

    private ModelConfigEntity requireById(Long id) {
        ModelConfigEntity e = mapper.selectById(id);
        if (e == null) {
            throw new BusinessException("模型配置不存在: id=" + id);
        }
        return e;
    }

    /** DTO → entity(不含 key 密文/默认/启停, 由调用方按场景单独处理) */
    private void applyDto(ModelConfigEntity e, ModelConfigDTO dto) {
        e.setName(dto.getName().trim());
        e.setProvider(dto.getProvider());
        e.setBaseUrl(dto.getBaseUrl().trim());
        e.setModelName(dto.getModelName().trim());
        e.setPurpose(dto.getPurpose());
        e.setTemperature(dto.getTemperature());
        e.setTimeoutMs(dto.getTimeoutMs());
        e.setRemark(dto.getRemark());
    }

    /** entity → VO: key 只出脱敏形态 */
    private ModelConfigVO toVo(ModelConfigEntity e) {
        ModelConfigVO vo = new ModelConfigVO();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setProvider(e.getProvider());
        vo.setBaseUrl(e.getBaseUrl());
        vo.setApiKeyMasked(ModelConfigVO.mask(safeDecrypt(e.getApiKeyCipher())));
        vo.setModelName(e.getModelName());
        vo.setPurpose(e.getPurpose());
        vo.setTemperature(e.getTemperature());
        vo.setTimeoutMs(e.getTimeoutMs());
        vo.setEnabled(e.getEnabled());
        vo.setIsDefault(e.getIsDefault());
        vo.setStatus(e.getStatus());
        vo.setRemark(e.getRemark());
        vo.setCreateTime(e.getCreateTime());
        vo.setUpdateTime(e.getUpdateTime());
        return vo;
    }

    /** 密文解密失败(如更换了加密密钥)不阻断列表, 给占位符 */
    private String safeDecrypt(String cipherText) {
        try {
            return cipher.decrypt(cipherText);
        } catch (Exception e) {
            log.warn("模型密钥解密失败(可能更换了加密密钥): {}", e.getMessage());
            return "";
        }
    }

    /** 连通性测试专用: 临时模型实例, 不进缓存不落库; 15s 超时 + 不重试快速给结论 */
    private class ChatModel4Test {
        private final OpenAiChatModel delegate;
        ChatModel4Test(ModelConfigDTO dto) {
            delegate = OpenAiChatModel.builder()
                    .baseUrl(dto.getBaseUrl().trim())
                    .apiKey(resolveKeyForTest(dto))
                    .modelName(dto.getModelName().trim())
                    .timeout(Duration.ofSeconds(15))
                    .maxRetries(0)
                    .build();
        }
        String chat(String msg) { return delegate.chat(msg); }
    }
}