package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

/**
 * 标签表
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_tag")
public class TagEntity extends BaseEntity<Integer> {
    /**
     * 标签名称
     */
    @TableField(value = "f_name")
    private String name;
    /**
     * 标签颜色
     */
    @TableField(value = "f_color")
    private String color;
}
