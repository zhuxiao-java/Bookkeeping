package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

import java.math.BigDecimal;

/**
 * 预算表实体
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_budget")
public class BudgetEntity extends BaseEntity<Integer> {
    /**
     * 分类ID
     */
    @TableField(value = "f_category_id")
    private Integer categoryId;
    /**
     * 预算金额
     */
    @TableField(value = "f_amount")
    private BigDecimal amount;
    /**
     * 所在月
     */
    @TableField(value = "f_month")
    private int month;
    /**
     * 所在年
     */
    @TableField(value = "f_year")
    private int year;
}
