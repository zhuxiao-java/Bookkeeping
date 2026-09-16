package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bookkeeping.constant.ExpTransactionType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

/**
 * 经验变更流水
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_exp_transaction")
public class ExpTransactionEntity extends BaseEntity<Integer> {
    /**
     * 来源
     */
    @TableField(value = "f_source")
    private ExpTransactionType source;
    /**
     * 来源表id
     */
    @TableField(value = "f_ref_id")
    private Integer refId;
    /**
     * 变更前的经验
     */
    @TableField(value = "f_before_experience")
    private int beforeExperience;
    /**
     * 经验变动值
     */
    @TableField(value = "f_exp_change")
    private int expChange;
    /**
     * 描述
     */
    @TableField(value = "f_description")
    private String description;

}

