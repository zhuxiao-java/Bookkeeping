package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;
/**
 * 等级配置
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_level_config")
public class LevelConfigEntity extends BaseEntity<Integer> {

    @TableField("f_level")
    private int level;

    @TableField("f_name")
    private String name;
    /**
     * 达到该等级所需累计经验值
     */
    @TableField("f_exp_threshold")
    private Integer expThreshold;
    /**
     * 等级图标
     */
    @TableField("f_icon")
    private String icon;
    /**
     * 等级描述
     */
    @TableField("f_description")
    private String description;
}
