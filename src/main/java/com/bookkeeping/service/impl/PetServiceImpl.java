package com.bookkeeping.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bookkeeping.constant.BookkeepingResp;
import com.bookkeeping.dao.dto.PetDTO;
import com.bookkeeping.dao.entity.CheckInEntity;
import com.bookkeeping.dao.entity.PetEntity;
import com.bookkeeping.dao.entity.PetLogEntity;
import com.bookkeeping.dao.mapper.CheckInMapper;
import com.bookkeeping.dao.mapper.PetLogMapper;
import com.bookkeeping.dao.mapper.PetMapper;
import com.bookkeeping.dao.mapping.PetMapping;
import com.bookkeeping.exception.BusinessException;
import com.bookkeeping.service.PetService;
import jakarta.annotation.Resource;
import org.sf.service.impl.IBaseCrudServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * 陪伴宠物心情。
 * 打开应用时按离开的天数下降，当天签到则回升；当天第一次记账再加一点。
 * 心情降到 0 后停住，宠物不会消失，也不改等级和账本。
 */
@Service
public class PetServiceImpl extends IBaseCrudServiceImpl<PetDTO, PetEntity, PetMapper, PetMapping> implements PetService {
    static final int INITIAL_MOOD = 60;
    static final int CHECK_IN_BONUS = 8;
    static final int RECORD_BONUS = 6;
    static final int DECAY_PER_MISSED_DAY = 12;
    private static final int MOOD_MAX = 100;
    private static final Set<String> SPECIES = Set.of("cat", "dog", "bird");
    private static final String SETTLE = "settle";
    private static final String RECORD = "record";

    @Resource
    private CheckInMapper checkInMapper;
    @Resource
    private PetLogMapper petLogMapper;
    /** 测试可替换，生产使用系统时区。 */
    private Clock clock = Clock.systemDefaultZone();

    public PetServiceImpl(PetMapping mapping) {
        super(mapping);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PetView current() {
        settle(today());
        return view(find());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PetView adopt(String name, String species) {
        if (find() != null) {
            throw new BusinessException(BookkeepingResp.PET_EXISTS);
        }
        LocalDate today = today();
        PetDTO dto = new PetDTO();
        dto.setSlot(1);
        dto.setName(normalizeName(name));
        dto.setSpecies(normalizeSpecies(species));
        dto.setMood(INITIAL_MOOD);
        dto.setAdoptedOn(today);
        dto.setSettledOn(today);
        if (!super.insert(dto)) {
            throw new IllegalStateException("领养失败");
        }
        return view(find());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PetView rename(String name) {
        PetEntity pet = find();
        if (pet == null) {
            throw new BusinessException(BookkeepingResp.PET_NOT_ADOPTED);
        }
        pet.setName(normalizeName(name));
        if (getBaseMapper().updateById(pet) < 1) {
            throw new IllegalStateException("改名失败");
        }
        return view(pet);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleToday() {
        settle(today());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void touchRecord() {
        LocalDate today = today();
        if (find() == null) {
            return;
        }
        settle(today);
        PetEntity pet = find();
        if (pet == null || logExists(today, RECORD)) {
            return;
        }
        int before = pet.getMood();
        int mood = Math.min(MOOD_MAX, before + RECORD_BONUS);
        pet.setMood(mood);
        if (getBaseMapper().updateById(pet) < 1) {
            throw new IllegalStateException("宠物心情更新失败");
        }
        writeLog(today, RECORD, before, mood);
    }

    @Override
    protected void savePreCheck(PetEntity entity) {
        if (find() != null) {
            throw new BusinessException(BookkeepingResp.PET_EXISTS);
        }
        entity.setSlot(1);
        entity.setName(normalizeName(entity.getName()));
        entity.setSpecies(normalizeSpecies(entity.getSpecies()));
    }

    private void settle(LocalDate today) {
        PetEntity pet = find();
        if (pet == null || today.equals(pet.getSettledOn())) {
            return;
        }
        int before = pet.getMood();
        int mood = before;
        if (pet.getSettledOn() != null) {
            long missed = ChronoUnit.DAYS.between(pet.getSettledOn(), today) - 1;
            if (missed > 0) {
                mood = (int) Math.max(0, mood - missed * DECAY_PER_MISSED_DAY);
            }
        }
        if (checkInOn(today) != null) {
            mood = Math.min(MOOD_MAX, mood + CHECK_IN_BONUS);
        }
        pet.setMood(mood);
        pet.setSettledOn(today);
        if (getBaseMapper().updateById(pet) < 1) {
            throw new IllegalStateException("宠物心情结算失败");
        }
        writeLog(today, SETTLE, before, mood);
    }

    private PetView view(PetEntity pet) {
        if (pet == null) {
            return PetView.none();
        }
        LocalDate today = today();
        int streak = streak(today);
        return new PetView(true, pet.getName(), pet.getSpecies(), speciesLabel(pet.getSpecies()),
                pet.getMood(), moodKey(pet.getMood()), moodLabel(pet.getMood()), stage(streak), streak,
                checkInOn(today) != null, logExists(today, RECORD),
                pet.getAdoptedOn() == null ? null : pet.getAdoptedOn().toString());
    }

    private PetEntity find() {
        List<PetEntity> rows = getBaseMapper().selectList(new QueryWrapper<>());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean logExists(LocalDate date, String type) {
        QueryWrapper<PetLogEntity> qw = new QueryWrapper<>();
        qw.eq("f_event_date", date).eq("f_event_type", type);
        Long count = petLogMapper.selectCount(qw);
        return count != null && count > 0;
    }

    private void writeLog(LocalDate date, String type, int before, int after) {
        PetLogEntity log = new PetLogEntity();
        log.setEventDate(date);
        log.setEventType(type);
        log.setMoodBefore(before);
        log.setMoodAfter(after);
        petLogMapper.insert(log);
    }

    private CheckInEntity checkInOn(LocalDate date) {
        QueryWrapper<CheckInEntity> qw = new QueryWrapper<>();
        qw.eq("f_check_date", date);
        List<CheckInEntity> rows = checkInMapper.selectList(qw);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int streak(LocalDate today) {
        CheckInEntity row = checkInOn(today);
        if (row == null) {
            row = checkInOn(today.minusDays(1));
        }
        return row == null ? 0 : row.getStreakDays();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    static String moodKey(int mood) {
        if (mood >= 70) return "energetic";
        if (mood >= 35) return "calm";
        return "sleepy";
    }

    static String moodLabel(int mood) {
        return switch (moodKey(mood)) {
            case "energetic" -> "精神";
            case "calm" -> "平常";
            default -> "困倦";
        };
    }

    static int stage(int streak) {
        if (streak >= 30) return 3;
        if (streak >= 7) return 2;
        return 1;
    }

    private static String speciesLabel(String species) {
        return switch (species) {
            case "cat" -> "猫";
            case "dog" -> "狗";
            case "bird" -> "鸟";
            default -> species;
        };
    }

    private static String normalizeName(String name) {
        if (name == null) {
            throw new BusinessException(BookkeepingResp.PET_INVALID);
        }
        String trimmed = name.trim();
        int count = trimmed.codePointCount(0, trimmed.length());
        if (count < 1 || count > 12 || trimmed.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new BusinessException(BookkeepingResp.PET_INVALID);
        }
        return trimmed;
    }

    private static String normalizeSpecies(String species) {
        if (species == null || !SPECIES.contains(species)) {
            throw new BusinessException(BookkeepingResp.PET_INVALID);
        }
        return species;
    }
}
