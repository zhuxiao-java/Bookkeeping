package com.bookkeeping.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bookkeeping.dao.entity.AiConfigEntity;

/**
 * 月报 AI 配置 mapper。单行表以固定主键 id=1 读写（selectById/updateById/insert）。
 *
 * @author zhuxiao
 */
public interface AiConfigMapper extends BaseMapper<AiConfigEntity> {
}
