package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

/**
 * 用户等级
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_user_level")
public class UserLevelEntity extends BaseEntity<Integer> {
    /**
     * 当前等级
     */
    @TableField(value = "f_level")
    private int level;
    /**
     * 当前累计经验值
     */
    @TableField(value = "f_experience")
    private Integer experience;
    /**
     * 累计获得经验值
     */
    @TableField(value = "f_total_earned")
    private Integer totalEarned;
    /**
     * 累计扣除经验
     */
    @TableField(value = "f_total_spent")
    private Integer totalSpent;
    /**
     * 生日（yyyy-MM-dd 或 MM-dd），用于生日贺卡
     */
    @TableField(value = "f_birthday")
    private String birthday;
}
