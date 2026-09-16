package com.bookkeeping.dao.mapper;

import com.bookkeeping.dao.entity.MessageEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.sf.dao.mapper.IBaseMapper;

/**
 * 消息mapper
 * @author zx
 */
public interface MessageMapper extends IBaseMapper<MessageEntity> {

    @Delete("delete from t_message where f_status = 1")
    void clearRead();

    /**
     * 全部已读（NEW-12）：直接将所有未读置为已读，返回实际更新条数。
     * 不依赖前端已加载的 id 列表，覆盖真实全部未读。
     */
    @Update("update t_message set f_status = 1 where f_status = 0")
    int markAllRead();
}
