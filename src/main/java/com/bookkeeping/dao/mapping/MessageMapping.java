package com.bookkeeping.dao.mapping;

import com.bookkeeping.dao.dto.MessageDTO;
import com.bookkeeping.dao.entity.MessageEntity;
import org.mapstruct.Mapper;
import org.sf.service.mapping.IBaseMapping;

/**
 * 消息映射
 * @author zx
 */
@Mapper(componentModel = "spring")
public interface MessageMapping extends IBaseMapping<MessageEntity, MessageDTO> {
}
