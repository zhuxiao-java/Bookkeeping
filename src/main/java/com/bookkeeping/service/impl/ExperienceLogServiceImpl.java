package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bookkeeping.dao.dto.ExperienceLogDTO;
import com.bookkeeping.dao.entity.ExperienceLogEntity;
import com.bookkeeping.dao.mapper.ExperienceLogMapper;
import com.bookkeeping.dao.mapping.ExperienceLogMapping;
import com.bookkeeping.service.ExperienceLogService;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * @author zhuxiao
 */
@Service
public class ExperienceLogServiceImpl extends IBaseCrudServiceImpl<ExperienceLogDTO, ExperienceLogEntity, ExperienceLogMapper, ExperienceLogMapping> implements ExperienceLogService {

    public ExperienceLogServiceImpl(ExperienceLogMapping mapping) {
        super(mapping);
    }

    @Override
    public List<ExperienceLogDTO> selectAll() {
        QueryWrapper<ExperienceLogEntity> qw = new QueryWrapper<>();
        qw.orderByDesc(Arrays.asList("f_year", "f_month"));
        return mapping.toDtoList(super.list(qw));
    }

    @Override
    public boolean exists(int year, int month) {
        QueryWrapper<ExperienceLogEntity> qw = new QueryWrapper<>();
        qw.eq("f_year", year)
          .eq("f_month", month);
        return super.exists(qw);
    }

    @Override
    public void recordExperienceLog(int year, int month,
                                    BigDecimal budgetAmount, BigDecimal actualAmount, int expChange) {
        ExperienceLogEntity logEntity = new ExperienceLogEntity();
        logEntity.setYear(year);
        logEntity.setMonth(month);
        logEntity.setBudgetAmount(budgetAmount);
        logEntity.setActualAmount(actualAmount);
        logEntity.setDiffAmount(budgetAmount.subtract(actualAmount));
        logEntity.setExpChange(expChange);
        if (!super.save(logEntity)) {
            throw new IllegalStateException("月结日志保存失败");
        }
    }
}
