package com.bookkeeping.controller.request;

import com.bookkeeping.constant.TransactionType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.request.RangeRequest;

import java.time.LocalDateTime;

/**
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class TransactionCategoryRequest extends RangeRequest<LocalDateTime> {

    private TransactionType type;

    private String groupBy;
}
