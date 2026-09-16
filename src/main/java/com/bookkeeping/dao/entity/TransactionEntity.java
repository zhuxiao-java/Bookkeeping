package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bookkeeping.constant.TransactionType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 核心交易流水
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_transaction")
public class TransactionEntity extends BaseEntity<Integer> {
    /**
     * 交易类型
     */
    @TableField(value = "f_type")
    private TransactionType type;
    /**
     * 手续费
     */
    @TableField(value = "f_fee")
    private BigDecimal fee;
    /**
     * 交易金额
     */
    @TableField(value = "f_amount")
    private BigDecimal amount;
    /**
     * 账户id
     */
    @TableField(value = "f_account_id")
    private Integer accountId;
    /**
     * 目标账户id
     */
    @TableField(value = "f_to_account_id")
    private Integer toAccountId;
    /**
     * 收支分类ID
     */
    @TableField(value = "f_category_id")
    private Integer categoryId;
    /**
     * 交易时间
     */
    @TableField(value = "f_date")
    private LocalDateTime transactionDate;
    /**
     * 备注信息
     */
    @TableField(value = "f_note")
    private String note;
    /**
     * 标签ID列表
     */
    @TableField(value = "f_tags")
    private String tags;
}
