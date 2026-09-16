package com.bookkeeping.dao.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bookkeeping.constant.MessageBizType;
import com.bookkeeping.constant.MessageStatus;
import com.bookkeeping.constant.MessageType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.sf.dao.entity.BaseEntity;

/**
 * 消息实体
 * @author zhuxiao
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("t_message")
public class MessageEntity extends BaseEntity<Integer> {
    /**
     * 消息标题
     */
    @TableField("f_title")
    private String title;
    /**
     * 消息内容
     */
    @TableField("f_content")
    private String content;
    /**
     * 消息类型
     */
    @TableField("f_type")
    private MessageType type;
    /**
     * 关联业务类型
     */
    @TableField("f_biz_type")
    private MessageBizType bizType;
    /**
     * 贺卡图片
     */
    @TableField("f_card_image")
    private String cardImage;
    /**
     * 关联业务ID
     */
    @TableField("f_biz_id")
    private Integer bizId;
    /**
     * 是否已读
     */
    @TableField("f_status")
    private MessageStatus status;
}
