package com.bookkeeping.constant;

import com.bookkeeping.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;

@AllArgsConstructor
@Getter
public enum ImageType {
    /**
     * 头像
     */
    AVATAR(1, "avatar", "avatar"),
    /**
     * 背景图片
     */
    BACKGROUND_IMAGE(2, "backgroundImage", "bg"),
    /**
     * 贺卡图片
     */
    GREETING_CARD(3, "greetingCard", "card")
    ;


    private final int status;

    private final String dir;

    private final String  name;

    public static ImageType of(int status) {
        for (ImageType type : values()) {
            if (Objects.equals(type.getStatus(), status)) {
                return type;
            }
        }
        throw new BusinessException(BookkeepingResp.IMAGE_TYPE_INVALID);
    }
}
