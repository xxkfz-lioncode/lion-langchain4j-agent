package com.lion.agent.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑用户请求(用户名 / 昵称 / 头像)
 */
@Data
public class UserUpdateRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度需在 3-32 之间")
    private String username;

    @Size(max = 32, message = "昵称最长 32 位")
    private String nickname;

    @Size(max = 255, message = "头像地址最长 255 位")
    private String avatar;
}
