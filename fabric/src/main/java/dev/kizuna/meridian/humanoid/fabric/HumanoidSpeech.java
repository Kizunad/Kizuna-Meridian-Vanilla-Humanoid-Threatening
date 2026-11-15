package dev.kizuna.meridian.humanoid.fabric;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 某个生人对某名玩家说一句话：招敌的传言、记仇的人认出玩家。Threatening 只发事件、不依赖谁来听；
 * Taunts 听了去发聊天和头顶气泡。话是写好的翻译文本，听的人自己按语言显示。
 */
public final class HumanoidSpeech {
    /** 服务端主线程上发，说话的生人此刻活着、和玩家在同一维度。 */
    public static final Event<Spoke> SPOKE = EventFactory.createArrayBacked(Spoke.class,
            listeners -> (speaker, addressee, line) -> {
                for (Spoke listener : listeners) {
                    listener.spoke(speaker, addressee, line);
                }
            });

    private HumanoidSpeech() {
        // 只放事件。
    }

    /** 说话的生人、对着说的玩家、那一句。 */
    @FunctionalInterface
    public interface Spoke {
        void spoke(LivingEntity speaker, ServerPlayer addressee, Component line);
    }
}
