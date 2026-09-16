package com.bookkeeping.exception;

import org.sf.common.code.RespInfo;
import org.sf.common.exception.BaseException;

/**
 * 业务异常
 * @author zhuxiao
 */
public class BusinessException extends BaseException {

    public BusinessException(RespInfo respInfo, Throwable throwable) {
        super(respInfo, throwable);
    }

    public BusinessException(RespInfo respInfo) {
        this(respInfo, null);
    }

    public BusinessException(String code, String message) {
        super(new RespInfo() {
            @Override
            public String getCode() {
                return code;
            }

            @Override
            public String getMsg() {
                return message;
            }
        });
    }
}
