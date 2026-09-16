package com.bookkeeping.dao.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;
/**
 * level等级dto
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class LevelConfigDTO extends BaseDTO<Integer> {

    private int level;

    private String name;

    private Integer expThreshold;

    private String icon;

    private String description;

}
