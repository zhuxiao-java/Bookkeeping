package com.bookkeeping.dao.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;

/**
 * 标签dto
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class TagDTO extends BaseDTO<Integer> {

    private String name;

    private String color;

    /** 标签分组：scene-普通场景标签 / brand-品牌标签 */
    private String group;
}
