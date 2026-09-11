package com.lion.agent.config;

import com.lion.agent.pojo.entity.User;
import com.lion.agent.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 密码升级组件: 启动时把历史遗留的明文密码自动升级为 BCrypt 密文
 * <p>
 * 兼容早期 init.sql 以明文插入账号的情况(如 admin/123456),
 * 登录接口后续一律按 BCrypt 校验, 无需手动改库。
 */
@Slf4j
@Component
public class PasswordMigrationRunner implements ApplicationRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public PasswordMigrationRunner(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<User> users = userMapper.selectList(null);
        int upgraded = 0;
        for (User user : users) {
            String password = user.getPassword();
            // BCrypt 密文以 $2 开头, 其余视为历史明文, 一次性加密
            if (password != null && !password.startsWith("$2")) {
                user.setPassword(passwordEncoder.encode(password));
                userMapper.updateById(user);
                upgraded++;
            }
        }
        if (upgraded > 0) {
            log.info("密码升级完成: {} 个历史明文账号已加密为 BCrypt", upgraded);
        }
    }
}
