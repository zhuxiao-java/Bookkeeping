package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * AI 月报实体：原子保存统计依据、来源指纹和已验证的 AI 结果，不含账户名称/备注等隐私字段。
 * <p>
 * 该表无 create/update 时间列，故不继承框架 BaseEntity（其会自动填充 f_create_time/f_update_time），
 * 改用纯 MyBatis-Plus 注解映射，避免向不存在的列写入导致 SQL 失败。
 *
 * @author zhuxiao
 */
@Data
@TableName("t_monthly_report")
public class MonthlyReportEntity {
    /**
     * 自增主键，对应 t_monthly_report.f_id（AUTOINCREMENT）
     */
    @TableId(value = "f_id", type = IdType.AUTO)
    private Integer id;
    /**
     * 归属月份，格式 yyyy-MM，唯一
     */
    @TableField("f_month")
    private String month;
    /**
     * 快照版本号，每次重新生成自增
     */
    @TableField("f_version")
    private Integer version;
    /**
     * 来源数据指纹（SHA-256），用于判断快照是否已过期
     */
    @TableField("f_source_hash")
    private String sourceHash;
    /**
     * 统计快照 JSON（Snapshot 序列化结果）
     */
    @TableField("f_snapshot")
    private String snapshot;
    /** 已验证的 AI 解读 JSON；仅旧版纯本地快照允许为空。 */
    @TableField("f_ai_result")
    private String aiResult;
    /**
     * 生成时间（ISO 字符串）
     */
    @TableField("f_generated_at")
    private String generatedAt;
    /**
     * 关联的消息 ID，用于把月报生成/解读结果同步到消息中心
     */
    @TableField("f_message_id")
    private Long messageId;
}
