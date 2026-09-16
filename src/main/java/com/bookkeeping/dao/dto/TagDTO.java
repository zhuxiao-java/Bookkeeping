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
}
