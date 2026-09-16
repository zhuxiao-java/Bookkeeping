package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bookkeeping.constant.MessageBizType;
import com.bookkeeping.constant.MessageStatus;
import com.bookkeeping.constant.MessageType;
import com.bookkeeping.dao.dto.MessageDTO;
import com.bookkeeping.dao.entity.MessageEntity;
import com.bookkeeping.dao.mapper.MessageMapper;
import com.bookkeeping.dao.mapping.MessageMapping;
import com.bookkeeping.service.MessageService;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息服务实现类
 * @author zhuxiao
 */
@Service
public class MessageServiceImpl extends IBaseCrudServiceImpl<MessageDTO, MessageEntity, MessageMapper, MessageMapping> implements MessageService {

    public MessageServiceImpl(MessageMapping mapping) {
        super(mapping);
    }

    @Override
    public Long unreadCount() {
        QueryWrapper<MessageEntity> qw = new QueryWrapper<>();
        qw.eq("f_status", MessageStatus.UN_READ.getValue());
        return super.count(qw);
    }

    @Override
    public void markMessageRead(Integer id) {
        MessageDTO dto = new MessageDTO();
        dto.setId(id);
        dto.setStatus(MessageStatus.READ);
        super.update(dto);
    }

    @Override
    public void markAllMessageRead(List<Integer> idList) {
        for (Integer messageId : idList) {
            markMessageRead(messageId);
        }
    }

    @Override
    public int markAllRead() {
        return baseMapper.markAllRead();
    }

    @Override
    public void clearRead() {
        baseMapper.clearRead();
    }

    @Override
    public void pushMessage(String title, String content, Integer refId, MessageType messageType, MessageBizType messageBizType) {
        pushMessage(title, content, refId, messageType, messageBizType, null);
    }

    @Override
    public void pushMessage(String title, String content, Integer refId, MessageType messageType, MessageBizType messageBizType, String cardImage) {
        MessageDTO dto = new MessageDTO();
        dto.setStatus(MessageStatus.UN_READ);
        dto.setTitle(title);
        dto.setContent(content);
        dto.setBizId(refId);
        dto.setBizType(messageBizType);
        dto.setType(messageType);
        dto.setCardImage(cardImage);
        super.insert(dto);
    }

    @Override
    public boolean exists(MessageType messageType, LocalDateTime start, LocalDateTime end) {
        QueryWrapper<MessageEntity> qw = new QueryWrapper<>();
        qw.eq("f_type", messageType.getValue())
                .gt("f_create_time", start)
                .lt("f_create_time", end);
        return super.exists(qw);
    }

    @Override
    public boolean exists(MessageType messageType, String title, LocalDateTime start, LocalDateTime end) {
        QueryWrapper<MessageEntity> qw = new QueryWrapper<>();
        qw.eq("f_type", messageType.getValue())
                .eq("f_title", title)
                .gt("f_create_time", start)
                .lt("f_create_time", end);
        return super.exists(qw);
    }

    @Override
    public boolean existsBiz(MessageType messageType, MessageBizType bizType, Integer bizId, LocalDateTime start, LocalDateTime end) {
        QueryWrapper<MessageEntity> qw = new QueryWrapper<>();
        qw.eq("f_type", messageType.getValue())
                .eq("f_biz_type", bizType.getValue())
                .eq("f_biz_id", bizId)
                .gt("f_create_time", start)
                .lt("f_create_time", end);
        return super.exists(qw);
    }
}
