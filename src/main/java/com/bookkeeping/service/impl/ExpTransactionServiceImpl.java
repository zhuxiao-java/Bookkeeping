package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bookkeeping.constant.ExpTransactionType;
import com.bookkeeping.dao.dto.ExpTransactionDTO;
import com.bookkeeping.dao.entity.ExpTransactionEntity;
import com.bookkeeping.dao.mapper.ExpTransactionMapper;
import com.bookkeeping.dao.mapping.ExpTransactionMapping;
import com.bookkeeping.service.ExpTransactionService;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 经验变更流水service实现类
 * @author zhuxiao
 */
@Service
public class ExpTransactionServiceImpl extends IBaseCrudServiceImpl<ExpTransactionDTO, ExpTransactionEntity, ExpTransactionMapper, ExpTransactionMapping> implements ExpTransactionService {

    public ExpTransactionServiceImpl(ExpTransactionMapping mapping) {
        super(mapping);
    }

    @Override
    public void recordExpTransaction(int refId, String description, ExpTransactionType type, int beforeExperience, int experience) {
        ExpTransactionDTO dto = new ExpTransactionDTO();
        dto.setRefId(refId);
        dto.setDescription(description);
        dto.setExpChange(experience);
        dto.setBeforeExperience(beforeExperience);
        dto.setSource(type);
        super.insert(dto);
    }

    @Override
    public int sumTodayExpBySource(ExpTransactionType type) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        QueryWrapper<ExpTransactionEntity> qw = new QueryWrapper<>();
        // f_create_time 由 SelfMetaObjectHandler 以系统默认时区的 LocalDateTime 填充，按当日本地边界过滤可靠
        qw.eq("f_source", type.getValue())
                .ge("f_create_time", today.atStartOfDay())
                .lt("f_create_time", today.plusDays(1).atStartOfDay());
        return super.list(qw).stream().mapToInt(ExpTransactionEntity::getExpChange).sum();
    }
}
