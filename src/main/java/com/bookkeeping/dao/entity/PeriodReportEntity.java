package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 周报与年报快照；月报继续使用原表，避免迁移历史数据。 */
@Data
@TableName("t_period_report")
public class PeriodReportEntity {
    @TableId(value = "f_id", type = IdType.AUTO)
    private Long id;
    @TableField("f_period_type")
    private String periodType;
    @TableField("f_period_key")
    private String periodKey;
    @TableField("f_version")
    private Integer version;
    @TableField("f_source_hash")
    private String sourceHash;
    @TableField("f_snapshot")
    private String snapshot;
    @TableField("f_ai_result")
    private String aiResult;
    @TableField("f_generated_at")
    private String generatedAt;
    @TableField("f_message_id")
    private Long messageId;
}
