package com.bookkeeping.service;

/**
 * 陪伴宠物。心情只读取签到和记账，不发放经验，也不改账本。
 */
public interface PetService {
    PetView current();

    PetView adopt(String name, String species);

    PetView rename(String name);

    /** 按今天结算一次。同一天重复调用不改变心情。 */
    void settleToday();

    /** 今天第一次记账时提升一点心情。未领养时什么都不做。 */
    void touchRecord();

    /**
     * 对外视图。未领养时 adopted 为 false，其余字段为空。
     */
    record PetView(boolean adopted,
                   String name,
                   String species,
                   String speciesLabel,
                   int mood,
                   String moodKey,
                   String moodLabel,
                   int stage,
                   int streakDays,
                   boolean checkedInToday,
                   boolean recordedToday,
                   String adoptedOn) {
        public static PetView none() {
            return new PetView(false, null, null, null, 0, null, null, 0, 0, false, false, null);
        }
    }
}
