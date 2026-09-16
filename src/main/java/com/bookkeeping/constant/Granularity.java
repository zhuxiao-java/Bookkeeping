package com.bookkeeping.constant;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

@AllArgsConstructor
public enum Granularity {
    MONTH("month") {
        @Override
        public String granularityKey(LocalDateTime time) {
            return time.getYear() + "-" + time.getMonth().getValue();
        }
    },
    YEAR("year") {
        @Override
        public String granularityKey(LocalDateTime time) {
            return time.getYear() + "";
        }
    },
    DAY("day") {
        @Override
        public String granularityKey(LocalDateTime time) {
            return time.getYear() + "-" + time.getMonth().getValue() + "-" + time.getDayOfMonth();
        }
    },
    WEEK("week") {
        @Override
        public String granularityKey(LocalDateTime time) {
            return time.getYear() + "-" + time.getMonth().getValue() + "-" + time.getDayOfWeek().getValue();
        }
    };

    private final String val;

    public abstract String granularityKey(LocalDateTime time);

    @JsonCreator
    public static Granularity fromValue(String value) {
        for (Granularity glr : Granularity.values()) {
            if (Objects.equals(glr.val, value)) {
                return glr;
            }
        }
        throw new IllegalArgumentException(value + " is not a valid Granularity");
    }
}
