package com.lion.agent.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置密码请求
 */
@Data
public class UserPasswordRequest {

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度不能少于 6 位")
    private String password;
}
