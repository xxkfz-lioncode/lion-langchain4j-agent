package com.lion.agent.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.SaTokenException;
import com.lion.agent.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理: 将异常统一转换为 Result 结构返回
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 未登录 / 登录已过期 */
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLogin(NotLoginException e) {
        return Result.fail(Result.CODE_UNAUTHORIZED, "未登录或登录已过期, 请重新登录");
    }

    /** 角色无权限(访问了仅管理员可用的接口) */
    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRole(NotRoleException e) {
        return Result.fail(Result.CODE_FORBIDDEN, "无权限访问该资源");
    }

    /** 其他 Sa-Token 异常 */
    @ExceptionHandler(SaTokenException.class)
    public Result<Void> handleSaToken(SaTokenException e) {
        return Result.fail(Result.CODE_UNAUTHORIZED, e.getMessage());
    }

    /** 业务异常 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError == null ? "参数校验失败" : fieldError.getDefaultMessage();
        return Result.fail(Result.CODE_BAD_REQUEST, msg);
    }

    /**
     * 静态资源不存在(浏览器自动请求的 /favicon.ico、未暴露的 /actuator/xxx 等)。
     * 这属于正常的 404, 不是系统故障: 单独处理, 只打 debug 日志, 避免刷 ERROR 堆栈。
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.debug("静态资源不存在: {}", e.getResourcePath());
        return Result.fail(Result.CODE_NOT_FOUND, "资源不存在");
    }

    /** 兜底异常 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(Result.CODE_ERROR, "系统繁忙, 请稍后重试");
    }
}
