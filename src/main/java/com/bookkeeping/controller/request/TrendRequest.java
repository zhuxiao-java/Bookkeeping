package com.bookkeeping.controller.request;

import com.bookkeeping.constant.Granularity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.request.RangeRequest;

import java.time.LocalDateTime;

/**
 * 时间分桶请求
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class TrendRequest extends RangeRequest<LocalDateTime> {
    private Granularity granularity;
}
