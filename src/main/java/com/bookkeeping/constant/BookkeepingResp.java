package com.bookkeeping.constant;

import lombok.AllArgsConstructor;
import org.sf.common.code.RespInfo;

@AllArgsConstructor
public enum BookkeepingResp implements RespInfo {

    ACCOUNT_EXISTS("B001", "账户名称已存在"),

    CATEGORY_EXISTS("B002", "此收支分类已存在"),

    BUDGET_EXISTS("B003", "总预算已存在"),

    BUDGET_CATEGORY_EXISTS("B004", "此收支分类预算已存在"),

    TAG_EXISTS("B005", "此标签已存在"),

    CHECK_IN_EXISTS("B006", "此日期已进行签到"),

    IMAGE_DIR_INIT_FAIL("B007", "图片文件夹初始化失败"),

    IMAGE_UPLOAD_SUCCESS("B008", "图片上传成功"),

    IMAGE_TYPE_INVALID("B009", "图片类型无效"),

    IMAGE_UPLOAD_FAIL("B0010", "图片上传失败"),

    IMAGE_DOWNLOAD_FAIL("B0011", "图片下载失败"),

    MONTHLY_BUDGET_NOT_EXISTS("B0012", "请先创建总预算,再创建分类预算"),

    CLASSIFICATION_BUDGET_EXCEED("B0013", "分类预算金额不能超过总预算,%s"),

    BACKUP_SNAPSHOT_FAIL("B0014", "创建数据库快照失败"),

    RESTORE_FILE_INVALID("B0015", "恢复文件无效，请上传本应用导出的 .db 备份"),

    RESTORE_FAIL("B0016", "备份恢复失败"),

    CSV_IMPORT_FAIL("B0017", "CSV 导入失败"),

    AI_CONFIG_INVALID("B0018", "AI 接口配置无效，请检查接口地址与模型名"),

    AI_CONFIG_CHANGED("B0019", "配置已变更，请重新确认后再试"),

    AI_NOT_CONFIGURED("B0020", "请先完成 AI 接口地址、模型与密钥配置"),

    AI_CONSENT_REQUIRED("B0021", "请先预览并确认发送统计数据后再生成 AI 月报"),

    AI_STALE("B0022", "账单或报告已变化，请重新预览并确认"),

    MONTH_INVALID("B0024", "只能生成已结束月份的月报，格式应为 yyyy-MM"),

    REPORT_NOT_FOUND("B0025", "月报不存在")
    ;


    private final String code;

    private final String msg;

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
