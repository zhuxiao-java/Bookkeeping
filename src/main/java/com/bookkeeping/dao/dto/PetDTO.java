package com.bookkeeping.dao.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;

import java.time.LocalDate;

/**
 * 陪伴宠物。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class PetDTO extends BaseDTO<Integer> {
    private int slot = 1;
    private String name;
    private String species;
    private int mood;
    private LocalDate adoptedOn;
    private LocalDate settledOn;
}
