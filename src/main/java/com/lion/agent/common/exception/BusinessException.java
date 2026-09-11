package com.lion.agent.common.exception;

import com.lion.agent.common.Result;

/**
 * 业务异常
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(Result.CODE_BAD_REQUEST, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
