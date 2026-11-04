package com.lion.agent.common;

import lombok.Data;

/**
 * 统一接口返回结构
 */
@Data
public class R<T> {

    public static final int CODE_OK = 200;
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_UNAUTHORIZED = 401;
    public static final int CODE_FORBIDDEN = 403;
    public static final int CODE_NOT_FOUND = 404;
    public static final int CODE_ERROR = 500;

    private int code;
    private String msg;
    private T data;

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(CODE_OK, "操作成功", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(CODE_OK, "操作成功", data);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }
}
