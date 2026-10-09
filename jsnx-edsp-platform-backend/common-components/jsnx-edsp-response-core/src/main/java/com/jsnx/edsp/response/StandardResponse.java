package com.jsnx.edsp.response;

public record StandardResponse<T>(String code, String message, String resultStatus, String traceId, T data) {
    public static <T> StandardResponse<T> success(String traceId, T data) {
        return new StandardResponse<>("EXT-SUCCESS", "查询成功", "HIT", traceId, data);
    }

    public static <T> StandardResponse<T> failure(String traceId, String code, String message) {
        return new StandardResponse<>(code, message, "ERROR", traceId, null);
    }
}
