package dev.kizuna.meridian.humanoid.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;

/**
 * 进度触发器 {@code meridian_humanoid_host:crisis}：招敌引擎里几件事发生时触发。
 *
 * <p>条件写 {@code "event": "omen" | "repelled" | "grudge_slain" | "feud_ended"}，分别是
 * 给了一回征兆、打赢一场来犯（头目死了）、杀掉记仇的人、出身的仇家打完最后一拨。</p>
 */
public final class CrisisTrigger extends SimpleCriterionTrigger<CrisisTrigger.Instance> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_humanoid_host", "crisis");
    static final CrisisTrigger TRIGGER = new CrisisTrigger();

    private CrisisTrigger() {
        // 全局只有一个实例，由注册表持有。
    }

    static void register() {
        // 触发器类型在注册表冻结前登记，进度文件按 ID 引用。
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, ID, TRIGGER);
    }

    @Override
    public Codec<Instance> codec() {
        // 进度文件里的条件格式。
        return Instance.CODEC;
    }

    static void fire(ServerPlayer player, Event event) {
        // 事情落定以后调用；原版只检查这名玩家还没完成的进度。
        TRIGGER.trigger(player, instance -> instance.event() == event);
    }

    /** 招敌引擎里会触发进度的几件事。 */
    public enum Event implements StringRepresentable {
        OMEN("omen"),
        REPELLED("repelled"),
        GRUDGE_SLAIN("grudge_slain"),
        FEUD_ENDED("feud_ended");

        static final Codec<Event> CODEC = StringRepresentable.fromEnum(Event::values);
        private final String name;

        Event(String name) {
            // 进度文件里写的名字。
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            // 进度文件里写的名字。
            return name;
        }
    }

    /** 一条进度条件：可选的玩家谓词和哪件事。 */
    public record Instance(Optional<ContextAwarePredicate> player, Event event)
            implements SimpleCriterionTrigger.SimpleInstance {
        // event 必须写；player 省略就是任意玩家。
        static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                Event.CODEC.fieldOf("event").forGetter(Instance::event)).apply(instance, Instance::new));
    }
}
