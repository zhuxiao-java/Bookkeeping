package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;
import java.time.LocalDate;

/**
 * 登录签到实体
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_check_in")
public class CheckInEntity extends BaseEntity<Integer> {
    /**
     * 签到日期
     */
    @TableField(value = "f_check_date")
    private LocalDate checkDate;
    /**
     * 本次签到获得总经验
     */
    @TableField(value = "f_exp_reward")
    private int expReward;
    /**
     * 基础经验
     */
    @TableField(value = "f_base_exp")
    private int baseExp;
    /**
     * 连续签到额外奖励经验
     */
    @TableField(value = "f_bonus_exp")
    private int bonusExp;
    /**
     * 连续签到天数（含本次）
     */
    @TableField(value = "f_streak_days")
    private int streakDays;
}
