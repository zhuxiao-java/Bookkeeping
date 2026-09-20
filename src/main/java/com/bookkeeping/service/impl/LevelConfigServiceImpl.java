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
        if (Objects.equals(list.size(), 2)) {
            return new LevelHolder(mapping.toDto(list.get(0)), mapping.toDto(list.get(1)));
        } else {
            return new LevelHolder(mapping.toDto(list.get(0)));
        }
    }

    @Override
    public LevelConfigDTO promotionLevel(Integer newExperience) {
        int experience = Math.min(newExperience, MAX_LEVEL_EXP_THRESHOLD);
        QueryWrapper<LevelConfigEntity> qw = new QueryWrapper<>();
        qw.between("f_exp_threshold", MIN_LEVEL_EXP_THRESHOLD, experience);
        qw.orderByDesc("f_level");
        qw.last("limit 1");
        return mapping.toDto(getOne(qw));
    }

    @Override
    public LevelConfigDTO selectMaxLevel() {
        QueryWrapper<LevelConfigEntity> qw = new QueryWrapper<>();
        qw.orderByDesc("f_level");
        qw.last("limit 1");
        return mapping.toDto(getOne(qw));
    }
}
