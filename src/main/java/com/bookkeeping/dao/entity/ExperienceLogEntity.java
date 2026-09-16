package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

import java.math.BigDecimal;

/**
 * 经验值日志
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_experience_log")
public class ExperienceLogEntity extends BaseEntity<Integer> {

    @TableField(value = "f_year")
    private int year;

    @TableField(value = "f_month")
    private int month;
    /**
     * 当月预算金额
     */
    @TableField("f_budget_amount")
    private BigDecimal budgetAmount;
    /**
     * 当月实际支出金额
     */
    @TableField("f_actual_amount")
    private BigDecimal actualAmount;
    /**
     *
     */
    @TableField("f_diff_amount")
    private BigDecimal diffAmount;
    /**
     * 经验变动
     */
    @TableField("f_exp_change")
    private int expChange;
}
