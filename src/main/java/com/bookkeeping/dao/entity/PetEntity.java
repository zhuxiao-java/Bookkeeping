package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

import java.time.LocalDate;

/**
 * 陪伴宠物。全库只有一行，由 f_slot = 1 保证。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_pet")
public class PetEntity extends BaseEntity<Integer> {
    @TableField("f_slot")
    private int slot;
    @TableField("f_name")
    private String name;
    /** cat / dog / bird */
    @TableField("f_species")
    private String species;
    /** 0–100，只由签到和记账推动 */
    @TableField("f_mood")
    private int mood;
    @TableField("f_adopted_on")
    private LocalDate adoptedOn;
    /** 最近一次按天结算心情的日期 */
    @TableField("f_settled_on")
    private LocalDate settledOn;
}
