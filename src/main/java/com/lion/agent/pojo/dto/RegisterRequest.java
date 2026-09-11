package com.lion.agent.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度需在 3-32 之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度不能少于 6 位")
    private String password;

    @Size(max = 32, message = "昵称最长 32 位")
    private String nickname;
}
