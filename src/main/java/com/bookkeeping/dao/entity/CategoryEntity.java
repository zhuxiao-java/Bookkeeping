package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bookkeeping.constant.Archived;
import com.bookkeeping.constant.CategoryType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

/**
 * 收支分类
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_category")
public class CategoryEntity extends BaseEntity<Integer> {
    /**
     * 分类名称
     */
    @TableField(value = "f_name")
    private String name;
    /**
     * 父收支分类
     */
    @TableField(value = "f_parent_id")
    private Integer parentId;
    /**
     * 图标
     */
    @TableField(value = "f_icon")
    private String icon;
    /**
     * 颜色
     */
    @TableField(value = "f_color")
    private String color;
    /**
     * 分类类型
     */
    @TableField(value = "f_type")
    private CategoryType type;
    /**
     * 排序序号
     */
    @TableField(value = "f_sort_order")
    private Integer sortOrder;
    /**
     * 是否归档
     */
    @TableField(value = "f_is_archived")
    private Archived archived;
}
