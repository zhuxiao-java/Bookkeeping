package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bookkeeping.dao.dto.LevelConfigDTO;
import com.bookkeeping.dao.entity.LevelConfigEntity;
import com.bookkeeping.dao.mapper.LevelConfigMapper;
import com.bookkeeping.dao.mapping.LevelConfigMapping;
import com.bookkeeping.service.LevelConfigService;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 等级配置实现类
 *
 * @author zhuxiao
 */
@Service
public class LevelConfigServiceImpl extends IBaseCrudServiceImpl<LevelConfigDTO, LevelConfigEntity, LevelConfigMapper, LevelConfigMapping> implements LevelConfigService {


    public LevelConfigServiceImpl(LevelConfigMapping mapping) {
        super(mapping);
    }

    @Override
    public LevelHolder selectLevelHolder(int level) {
        int nextLevel = level + 1;
        QueryWrapper<LevelConfigEntity> qw = new QueryWrapper<>();
        qw.in("f_level", level, nextLevel);
        List<LevelConfigEntity> list = super.list(qw);
        LevelConfigEntity current = list.stream().filter(item -> item.getLevel() == level)
                .findFirst().orElseThrow(() -> new IllegalStateException("缺少等级配置：" + level));
        LevelConfigEntity next = list.stream().filter(item -> item.getLevel() == nextLevel)
                .findFirst().orElse(null);
        return new LevelHolder(mapping.toDto(current), mapping.toDto(next));
    }

    @Override
    public LevelConfigDTO promotionLevel(Integer newExperience) {
        int experience = Math.max(MIN_LEVEL_EXP_THRESHOLD,
                Math.min(Objects.requireNonNullElse(newExperience, 0), MAX_LEVEL_EXP_THRESHOLD));
        QueryWrapper<LevelConfigEntity> qw = new QueryWrapper<>();
        // 兼容存量 SQLite 的 TEXT 门槛列，必须按数值比较。
        qw.apply("CAST(f_exp_threshold AS INTEGER) BETWEEN {0} AND {1}", MIN_LEVEL_EXP_THRESHOLD, experience);
        qw.orderByDesc("f_level");
        qw.last("limit 1");
        return mapping.toDto(getOne(qw));
    }

    @Override
    public List<LevelConfigDTO> selectAll() {
        return mapping.toDtoList(list(new QueryWrapper<LevelConfigEntity>().orderByAsc("f_level")));
    }

    @Override
    public LevelConfigDTO selectMaxLevel() {
        QueryWrapper<LevelConfigEntity> qw = new QueryWrapper<>();
        qw.orderByDesc("f_level");
        qw.last("limit 1");
        return mapping.toDto(getOne(qw));
    }
}
