package com.itdou.ragknowledgesystem.common.response;

/**
 * 统一响应信封。
 *
 * HTTP 状态码依然表达成败（201 / 204 / 400 / 404 / 409 / 422），code 只是给前端做业务分支判断用，
 * 两者同时保留是刻意的：网关、监控、curl -f 依赖状态码，前端弹提示依赖 code 和 message。
 */
public record Result<T>(String code, String message, T data) {

    // 成功时的固定 code，前端可以直接用它判断业务是否成功
    public static final String SUCCESS_CODE = "OK";
    /*
     * 成功消息固定用英文 success：这是程序判断的常态，不需要给人读。
     * 中文留给异常消息——那些是要展示给用户看的，见 GlobalExceptionHandler。
     */
    private static final String SUCCESS_MESSAGE = "success";

    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    // 用于没有返回数据的成功场景，与 204 区分开：这里仍然是 200
    public static <T> Result<T> success() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> Result<T> failure(String code, String message) {
        return new Result<>(code, message, null);
    }
}
