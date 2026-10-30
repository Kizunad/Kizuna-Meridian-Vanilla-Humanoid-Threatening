package dev.kizuna.meridian.humanoid.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 出生档位：凡人是游炁境界下一条经脉都未开，游炁与真炁对应两档经脉变体。 */
public enum HumanoidTier {
    MORTAL(HumanoidVariant.WANDERING_QI),
    WANDERING_QI(HumanoidVariant.WANDERING_QI),
    TRUE_QI(HumanoidVariant.TRUE_QI);

    private final HumanoidVariant variant;

    HumanoidTier(HumanoidVariant variant) {
        // 档位决定生成时使用的境界与装填区间。
        this.variant = variant;
    }

    public HumanoidVariant variant() {
        // 凡人沿用游炁的境界和装填区间，只是不开脉。
        return variant;
    }

    public String id() {
        // 配置与命令使用小写名，如 wandering_qi。
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<HumanoidTier> fromId(String id) {
        // 只接受小写名，未知名保持为空，由调用方报告配置错误。
        return Arrays.stream(values()).filter(tier -> tier.id().equals(id)).findFirst();
    }

    public static HumanoidTier of(HumanoidVariant variant) {
        // 自然抽档与刷怪蛋只产生两档变体。
        Objects.requireNonNull(variant, "variant");
        if (variant == HumanoidVariant.TRUE_QI) {
            return TRUE_QI;
        }
        return WANDERING_QI;
    }

    public static HumanoidTier of(BodyMessages.BodySnapshot body) {
        // 从快照读回档位：真炁境界为真炁；游炁境界下有已开经脉为游炁，一条都没有为凡人。
        Objects.requireNonNull(body, "body");
        if (body.getRealm() == BodyMessages.Realm.TRUE_QI) {
            return TRUE_QI;
        }
        boolean anyOpened = body.getMeridianStatesList().stream()
                .anyMatch(meridian -> meridian.getOpening().getStage() == MeridianOpeningStage.OPENED);
        if (anyOpened) {
            return WANDERING_QI;
        }
        return MORTAL;
    }
}
