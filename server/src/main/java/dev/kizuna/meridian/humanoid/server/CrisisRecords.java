package dev.kizuna.meridian.humanoid.server;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Feud;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;

import java.util.HashSet;
import java.util.Set;

/** 玩家的招敌记录存在他唯一身体的附属进度里，不另建玩家账本；这里只管编解码和形状核对。 */
public final class CrisisRecords {
    public static final String PROGRESS_KEY = "meridian_humanoid:crisis";
    public static final int SCHEMA_VERSION = 1;

    private CrisisRecords() {
        // 不在进程里另存一份。
    }

    public static CrisisRecord empty() {
        // 新玩家的记录：在线 0 tick、没有风声、还没读过身体。
        return CrisisRecord.newBuilder().setSchemaVersion(SCHEMA_VERSION).build();
    }

    public static CrisisRecord read(BodySnapshot body) {
        // 没有这一项是新玩家；有但解不开、版本不对、内容不合规都是存档损坏，直接报错。
        Any stored = body.getAddonProgressMap().get(PROGRESS_KEY);
        if (stored == null) {
            return empty();
        }
        CrisisRecord record;
        try {
            record = stored.unpack(CrisisRecord.class);
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException(HumanoidErrors.CRISIS_RECORD + ": 解不开", invalid);
        }
        validate(record);
        return record;
    }

    public static void write(BodyActivity.Builder activity, CrisisRecord record) {
        // 整份覆盖写入；写之前同样核对，坏记录不落盘。
        validate(record);
        activity.putProgress(PROGRESS_KEY, Any.pack(record));
    }

    static void validate(CrisisRecord record) {
        // 版本、时刻和风声的范围；仇家身份不重复，排着的那一场指向的仇家必须在。
        if (record.getSchemaVersion() != SCHEMA_VERSION) {
            throw invalid("版本 " + record.getSchemaVersion() + " 不认识");
        }
        if (record.getOnlineTicks() < 0 || record.getSettledCount() < 0 || record.getOpenedMeridians() < 0) {
            throw invalid("计数不能为负");
        }
        if (!Double.isFinite(record.getHeat()) || record.getHeat() < 0) {
            throw invalid("风声必须是不为负的有限数值");
        }
        Set<String> feuds = new HashSet<>();
        for (Feud feud : record.getFeudsList()) {
            if (feud.getKey().isEmpty() || !feuds.add(feud.getKey()) || feud.getStage() < 0) {
                throw invalid("仇家 " + feud.getKey() + " 重复、为空或拨次为负");
            }
        }
        if (record.hasPending()) {
            validate(record.getPending(), feuds);
        }
    }

    private static void validate(Pending pending, Set<String> feuds) {
        // 排着的那一场：有队伍表，征兆时刻从早到晚且都不晚于来人，已给次数不超过总数。
        if (pending.getParty().isEmpty() || (!pending.getFeud().isEmpty() && !feuds.contains(pending.getFeud()))) {
            throw invalid("排着的一场没有队伍表或指向不存在的仇家");
        }
        long previous = pending.getScheduledAt();
        for (long at : pending.getOmenAtList()) {
            if (at < previous || at > pending.getStrikeAt()) {
                throw invalid("征兆时刻必须从早到晚且不晚于来人");
            }
            previous = at;
        }
        if (pending.getOmensGiven() < 0 || pending.getOmensGiven() > pending.getOmenAtCount()) {
            throw invalid("已给征兆次数越界");
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        // 统一加上记录名前缀。
        return HumanoidErrors.invalid(HumanoidErrors.CRISIS_RECORD + ": " + detail);
    }
}
