package com.bookkeeping.service;

import com.bookkeeping.constant.MessageBizType;
import com.bookkeeping.constant.MessageType;
import com.bookkeeping.dao.dto.MessageDTO;
import org.sf.service.IBaseCrudService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息服务
 *
 * @author
 */
public interface MessageService extends IBaseCrudService<MessageDTO> {

    Long unreadCount();

    void markMessageRead(Integer id);

    void markAllMessageRead(List<Integer> idList);

    /**
     * 全部已读（NEW-12）：服务端直接将所有未读置为已读，返回实际标记条数。
     * 与 {@link #markAllMessageRead(List)} 不同，不依赖前端传入的 id，覆盖真实全部未读。
     */
    int markAllRead();

    void clearRead();

    void pushMessage(String title, String content, Integer refId, MessageType messageType, MessageBizType messageBizType);

    /**
     * 推送带贺卡封面的消息（cardImage 为前端可直接用作 img src 的根相对路径，如 /greeting/birthday.svg）。
     */
    void pushMessage(String title, String content, Integer refId, MessageType messageType, MessageBizType messageBizType, String cardImage);

    boolean exists(MessageType messageType, LocalDateTime start, LocalDateTime end);

    /**
     * 按类型 + 标题 + 时间区间判重（用于同一天同类节日/生日贺卡只推一次）。
     */
    boolean exists(MessageType messageType, String title, LocalDateTime start, LocalDateTime end);

    /**
     * 按类型 + 业务类型 + 业务id + 时间区间判重（用于同一预算同月超支提醒只推一次）。
     */
    boolean existsBiz(MessageType messageType, MessageBizType bizType, Integer bizId, LocalDateTime start, LocalDateTime end);
}
