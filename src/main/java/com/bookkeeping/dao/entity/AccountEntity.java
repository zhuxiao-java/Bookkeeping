package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bookkeeping.constant.AccountType;
import com.bookkeeping.constant.Archived;
import com.bookkeeping.constant.Currency;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

import java.math.BigDecimal;

/**
 * 账户表实体
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_account")
public class AccountEntity extends BaseEntity<Integer> {
    /**
     * 账户名称
     */
    @TableField(value = "f_name")
    private String name;
    /**
     * 账户类型
     */
    @TableField(value = "f_type")
    private AccountType type;
    /**
     * 初始金额
     */
    @TableField(value = "f_initial_balance")
    private BigDecimal initialBalance;
    /**
     * 当前余额
     */
    @TableField(value = "f_current_balance")
    private BigDecimal currentBalance;
    /**
     * 币种
     */
    @TableField(value = "f_currency")
    private Currency currency;

    /**
     * 是否归档
     */
    @TableField(value = "f_is_archived")
    private Archived archived;
}
