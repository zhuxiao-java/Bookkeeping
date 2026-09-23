package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 月报 AI 解读配置实体：单用户桌面应用仅一行（f_id=1）。
 * <p>
 * 存放 OpenAI 兼容接口的 baseUrl/模型名/密钥，以及用于单次确认失效判断的配置版本。
 * 明文密钥仅存于本地库，任何回显接口都不返回该字段。表无 f_create_time 列，故不继承 BaseEntity。
 *
 * @author zhuxiao
 */
@Data
@TableName("t_ai_config")
public class AiConfigEntity {
    /**
     * 固定主键（单行配置），应用侧写死为 1
     */
    @TableId(value = "f_id", type = IdType.INPUT)
    private Integer id;
    /**
     * OpenAI 兼容接口根地址，例如 https://api.openai.com/v1
     */
    @TableField("f_base_url")
    private String baseUrl;
    /**
     * 模型名，例如 gpt-4o-mini
     */
    @TableField("f_model")
    private String model;
    /**
     * 接口密钥（明文，仅本地保存，不回显）
     */
    @lombok.ToString.Exclude
    @TableField("f_api_key")
    private String apiKey;
    /**
     * 是否在发送给模型时包含分类名称；false 时脱敏为「分类-{id}」
     */
    @TableField("f_include_names")
    private boolean includeNames;
    /**
     * 配置版本，关键字段变更时重新生成，用于失效当前请求与发送确认
     */
    @TableField("f_config_version")
    private String configVersion;
    /**
     * 最后更新时间（ISO 字符串），对应 t_ai_config.f_update_time（TEXT）
     */
    @TableField("f_update_time")
    private String updateTime;
}
