package com.bookkeeping.service;

import com.bookkeeping.dao.dto.CheckInDTO;
import org.sf.service.IBaseCrudService;

/**
 * 签到Service
 * @author zx
 */
public interface CheckInService extends IBaseCrudService<CheckInDTO> {

    void dailyCheckIn();
}
