package com.bookkeeping.controller;

import com.bookkeeping.dao.dto.MessageDTO;
import com.bookkeeping.service.MessageService;
import org.sf.model.response.BaseResponse;
import org.sf.model.response.DataResponse;
import org.sf.web.controller.IBaseController;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 消息controller
 * @author zhuxiao
 */
@RestController
@RequestMapping("message")
public class MessageController extends IBaseController<MessageDTO, MessageService> {

    public MessageController(MessageService service) {
        super(service);
    }

    /**
     * 未读消息总条数
     * @return DataResponse
     */
    @GetMapping("unreadCount")
    public DataResponse<Long> unreadCount() {
        return DataResponse.of(service.unreadCount());
    }

    /**
     * 单条标记已读（幂等：已读再调仍返回成功）
     * @param id 消息id
     * @return BaseResponse
     */
    @PostMapping("read")
    public BaseResponse markMessage(@RequestParam("id") Integer id) {
        service.markMessageRead(id);
        return BaseResponse.success();
    }

    /**
     * 根据消息id列表设置为已读
     * @param idList  id列表
     * @return BaseResponse
     */
    @PostMapping("readAll")
    public BaseResponse markAllMessageRead(@RequestParam("ids") List<Integer> idList) {
        service.markAllMessageRead(idList);
        return BaseResponse.success();
    }

    /**
     * 全部已读（NEW-12）：服务端直接将所有未读置为已读，覆盖真实全部未读（不限于前端已加载的）。
     * @return DataResponse 实际标记条数
     */
    @PostMapping("markAllRead")
    public DataResponse<Integer> markAllRead() {
        return DataResponse.of(service.markAllRead());
    }

    @PostMapping("clearRead")
    public BaseResponse clearRead() {
        service.clearRead();
        return BaseResponse.success();
    }
}
