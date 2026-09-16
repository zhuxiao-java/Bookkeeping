package com.bookkeeping.dao.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;
/**
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class UserLevelDTO extends BaseDTO<Integer> {

    private int level;

    private int experience;

    private int totalEarned;

    private int totalSpent;

    private String birthday;
}
