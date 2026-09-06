package com.lion.agent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用/禁用用户请求
 */
@Data
public class UserStatusRequest {

    /** 状态: 1 启用 / 0 禁用 */
    @NotNull(message = "状态不能为空")
    private Integer status;
}
