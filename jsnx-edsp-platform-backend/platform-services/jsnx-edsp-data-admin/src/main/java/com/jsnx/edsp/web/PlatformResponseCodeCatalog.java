package com.jsnx.edsp.web;

import java.util.List;

final class PlatformResponseCodeCatalog {
    private PlatformResponseCodeCatalog() {}

    static final List<PlatformResponseCodeController.Code> CODES = List.of(
            code("100", "请求成功，有返回", "HIT", "SUCCESS", "RESPONSE_SUCCESS"),
            code("3", "请求成功，返回挡板", "HIT", "SUCCESS", "DB_RESPONSE_SUCCESS"),
            code("2", "请求成功，返回缓存", "HIT", "SUCCESS", "CASHE_RESPONSE_SUCCESS"),
            code("1", "请求成功，无返回", "HIT", "SUCCESS", "NO_RESPONSE_SUCCESS；适用于不要求返回业务数据的操作"),
            code("-1", "请求参数必填项缺失", "ERROR", "REQUEST", "NO_PARAMS_FAIL"),
            code("-2", "机构号未填写", "ERROR", "REQUEST", "NO_ORGCODE_FAIL"),
            code("-3", "系统号未填写", "ERROR", "AUTH", "NO_APIKEY_FAIL"),
            code("-4", "系统号未授权", "ERROR", "AUTH", "NO_POWER_FAIL"),
            code("-1001", "连接 GoldenDB 数据库失败", "ERROR", "DEPENDENCY", "GOLDENDB_FAIL"),
            code("-1002", "连接 HBase 数据库失败", "ERROR", "DEPENDENCY", "HBASE_FAIL"),
            code("-1003", "连接 Redis 失败", "ERROR", "DEPENDENCY", "REDIS_FAIL"),
            code("-10001", "第三方网络异常", "ERROR", "UPSTREAM", "INTER_ERROR_FAIL"),
            code("-10002", "第三方响应超时", "ERROR", "UPSTREAM", "INTER_TIMEOUT_FAIL"),
            code("-10003", "第三方异常", "ERROR", "UPSTREAM", "INTER_THIRD_FAIL"),
            code("-10004", "未知异常", "ERROR", "PLATFORM", "UNKOWN_ERROR_FAIL"),
            code("-10005", "解密失败", "ERROR", "SECURITY", "DECRYPT_ERROR_FAIL"),
            code("-10006", "解析响应失败", "ERROR", "UPSTREAM", "JX_RESP_ERROR_FAIL"),
            code("-10007", "没有系统权限", "ERROR", "AUTH", "XT_RESP_ERROR_FAIL"),
            code("-10008", "没有接口权限", "ERROR", "AUTH", "JK_RESP_ERROR_FAIL"),
            code("-10009", "配额不足", "ERROR", "LIMIT", "PEBZ_RESP_ERROR_FAIL"),
            code("-10010", "企业暂未授权", "ERROR", "AUTH", "NO_AUTH_RESP_ERROR_FAIL"),
            code("-10011", "企业不存在", "MISS", "NO_DATA", "NO_CORP_RESP_ERROR_FAIL；仅在正常查询无记录时使用"),
            code("-10012", "未知 URL", "ERROR", "REQUEST", "NO_URL_RESP_ERROR_FAIL"),
            code("-10013", "重复授权", "ERROR", "AUTH", "CFSQ_RESP_ERROR_FAIL"),
            code("-10014", "授权文件下载失败", "ERROR", "FILE", "AUTH_FILE_DOWNLOAD_FAIL"),
            code("-10015", "第三方返回处理失败", "ERROR", "UPSTREAM", "THIRD_FAIL"),
            code("-10016", "授权标识无效", "ERROR", "AUTH", "CONTRACTID_INVALID"),
            code("-10017", "授权书文件无效", "ERROR", "FILE", "AUTH_FILE_INVALID"),
            code("-10018", "授权书文件超过 10 MB", "ERROR", "FILE", "AUTH_FILE_DATA_LIMIT_EXCEEDED"),
            code("-10019", "授权书格式无效", "ERROR", "FILE", "AUTH_FILE_FORMAT_INVALID"),
            code("-10020", "授权上报结果不存在", "MISS", "NO_DATA", "AUTH_FILE_RESULT_NOT_EXIST；仅在正常查询无记录时使用"),
            code("-10021", "授权文件内容有误", "ERROR", "FILE", "AUTH_FILE_CONTENT_FAIL"),
            code("-10022", "权限错误", "ERROR", "AUTH", "THIRD_POWER_ERROR_FAIL"),
            code("ACCESS_RATE_LIMITED_FAIL", "访问次数受限", "ERROR", "LIMIT", "生产原码 -10023；与文件访问失败重码"),
            code("ACCESS_FILE_FAIL", "文件访问失败", "ERROR", "FILE", "生产原码 -10023；与访问次数受限重码"),
            code("NO_QUERY_AUTH_FAIL", "无查询授权信息", "ERROR", "AUTH", "生产原码 -10024；与文件重新加载中重码"),
            code("FILE_RELOADING_FAIL", "数据文件重新加载中", "ERROR", "FILE", "生产原码 -10024；与无查询授权信息重码")
    );

    private static PlatformResponseCodeController.Code code(String value, String name, String status,
                                                              String category, String description) {
        return new PlatformResponseCodeController.Code(value, name, status, category, description, true);
    }
}
