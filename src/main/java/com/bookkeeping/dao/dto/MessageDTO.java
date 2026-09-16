package com.bookkeeping.dao.dto;

import com.bookkeeping.constant.MessageBizType;
import com.bookkeeping.constant.MessageStatus;
import com.bookkeeping.constant.MessageType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.model.dto.BaseDTO;

/**
 * 消息DTO
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class MessageDTO extends BaseDTO<Integer> {
    private String title;
    private String content;
    private MessageType type;
    private MessageBizType bizType;
    private String cardImage;
    private Integer bizId;
    private MessageStatus status;
}
