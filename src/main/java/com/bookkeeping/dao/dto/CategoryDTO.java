package com.bookkeeping.dao.dto;

import com.bookkeeping.constant.Archived;
import com.bookkeeping.constant.CategoryType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;

/**
 * 收支分类dto
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class CategoryDTO extends BaseDTO<Integer> {
    private String name;
    private Integer parentId;
    private String icon;
    private String color;
    private CategoryType type;
    private Integer sortOrder;
    private Archived archived;
}
