package com.bookkeeping.dao.dto;

import com.bookkeeping.constant.ExpTransactionType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;

/**
 * 经验变更流水dto
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ExpTransactionDTO extends BaseDTO<Integer> {
    private ExpTransactionType source;
    private Integer refId;
    private int beforeExperience;
    private int expChange;
    private String description;
}
