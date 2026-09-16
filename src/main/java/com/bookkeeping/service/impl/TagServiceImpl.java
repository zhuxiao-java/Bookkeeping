package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.dao.dto.TagDTO;
import com.bookkeeping.dao.entity.TagEntity;
import com.bookkeeping.dao.mapper.TagMapper;
import com.bookkeeping.dao.mapping.TagMapping;
import com.bookkeeping.exception.BusinessException;
import com.bookkeeping.service.TagService;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 标签Service实现类
 * @author zx
 */
@Service
public class TagServiceImpl extends IBaseCrudServiceImpl<TagDTO, TagEntity, TagMapper, TagMapping> implements TagService {

    public TagServiceImpl(TagMapping mapping) {
        super(mapping);
    }

    @Override
    protected void savePreCheck(TagEntity entity) {
        QueryWrapper<TagEntity> qw = new QueryWrapper<>();
        qw.eq("f_name", entity.getName());
        if (super.exists(qw)) {
            throw new BusinessException(BookkeepingResp.TAG_EXISTS);
        }
    }
}
