package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

import java.time.LocalDate;

/**
 * 宠物心情流水。同一天的结算和记账互动各保留一条。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_pet_log")
public class PetLogEntity extends BaseEntity<Integer> {
    @TableField("f_event_date")
    private LocalDate eventDate;
    /** settle：按天结算；record：当天第一次记账 */
    @TableField("f_event_type")
    private String eventType;
    @TableField("f_mood_before")
    private int moodBefore;
    @TableField("f_mood_after")
    private int moodAfter;
}
