package dev.kizuna.meridian.humanoid.fabric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * 招敌派来的人各盯哪名玩家、哪些在撤：来人现身时登记盯人，一场了结时转成撤走，玩家下线或停服时放掉。
 * 大脑选敌和原版改目标都问这里：盯人的只认那名玩家，撤走的谁也不认；被魅惑时听魅惑的。玩家死前也从这里进招敌。
 */
public final class CrisisRaiders {
    private static final Map<UUID, UUID> QUARRY = new HashMap<>();
    private static final Map<UUID, UUID> ESCORTS = new HashMap<>();
    private static final Map<UUID, Retreat> RETREATING = new HashMap<>();

    /**
     * 撤走的来人。
     *
     * @param player 原先盯的玩家
     * @param since 开始撤的服务器 tick
     */
    record Retreat(UUID player, int since) { }

    private CrisisRaiders() {
        // 只有静态登记表，服务端主线程读写。
    }

    static void track(UUID player, List<UUID> raiders) {
        // 一批来人都记到这名玩家名下。
        raiders.forEach(raider -> QUARRY.put(raider, player));
    }

    static List<UUID> raiders(UUID player) {
        // 还在盯这名玩家的来人。
        return QUARRY.entrySet().stream().filter(entry -> entry.getValue().equals(player)).map(Map.Entry::getKey)
                .toList();
    }

    static boolean tracking(UUID player) {
        // 这名玩家名下还有没有盯人的来人。
        return QUARRY.containsValue(player);
    }

    static void retreat(UUID player, int tick) {
        // 一场了结：盯这名玩家的来人都转成撤走，从这一刻起算。
        for (UUID raider : raiders(player)) {
            QUARRY.remove(raider);
            ESCORTS.remove(raider);
            RETREATING.put(raider, new Retreat(player, tick));
        }
    }

    static Map<UUID, Retreat> retreats() {
        // 撤走的来人，撤退那边每步过一遍；拿到的是一份拷贝，过的时候可以放掉。
        return Map.copyOf(RETREATING);
    }

    static void withdraw(Mob guest, UUID owner) {
        int tick = guest.level().getServer().getTickCount();
        RETREATING.put(guest.getUUID(), new Retreat(owner, tick));
    }

    static void dismiss(UUID raider) {
        // 这一个不用再管了（死了、走远消失了、卸载了）。
        QUARRY.remove(raider);
        ESCORTS.remove(raider);
        RETREATING.remove(raider);
    }

    static List<UUID> release(UUID player) {
        // 玩家下线：名下盯人的、在撤的都放掉，交回它们的 UUID 好当场清走。
        List<UUID> released = new ArrayList<>(raiders(player));
        RETREATING.forEach((raider, retreat) -> {
            if (retreat.player().equals(player)) {
                released.add(raider);
            }
        });
        released.forEach(CrisisRaiders::dismiss);
        return released;
    }

    static void clear() {
        // 停服时清空。
        QUARRY.clear();
        ESCORTS.clear();
        RETREATING.clear();
    }

    static boolean hunting(Mob mob) {
        // 是不是招敌派来、还在盯人的。
        return QUARRY.containsKey(mob.getUUID());
    }

    static boolean retreating(Mob mob) {
        // 是不是一场了结后在撤的。
        return RETREATING.containsKey(mob.getUUID());
    }

    static boolean sent(Mob mob) {
        // 盯人的和在撤的都是招敌管着的；打了标签却两头都不在的是没收拾掉的孤儿。
        return hunting(mob) || retreating(mob);
    }

    static Optional<UUID> quarryId(Mob mob) {
        // 盯的那名玩家；不在盯人为空。
        return Optional.ofNullable(QUARRY.get(mob.getUUID()));
    }

    static Optional<UUID> retreatingFrom(Mob mob) {
        // 撤走前盯的那名玩家；不在撤为空。
        return Optional.ofNullable(RETREATING.get(mob.getUUID())).map(Retreat::player);
    }

    static boolean huntingFor(Entity entity, UUID player) {
        // 这个是不是正在盯这名玩家的来人。
        return entity instanceof Mob mob && player.equals(QUARRY.get(mob.getUUID()));
    }

    static Optional<LivingEntity> quarry(Mob mob) {
        // 盯的那名玩家在线、活着、和它在同一个世界、能被打（不是创造或旁观）才算；撤走的、否则这一刻都没有对手。
        UUID player = QUARRY.get(mob.getUUID());
        MinecraftServer server = mob.level().getServer();
        if (player == null || server == null) {
            return Optional.empty();
        }
        ServerPlayer target = server.getPlayerList().getPlayer(player);
        if (target == null || !target.isAlive() || target.level() != mob.level() || target.isCreative()
                || target.isSpectator()) {
            return Optional.empty();
        }
        UUID escort = ESCORTS.get(mob.getUUID());
        if (escort != null && target.level().getEntity(escort) instanceof LivingEntity companion
                && companion.isAlive()) {
            return Optional.of(companion);
        }
        return Optional.of(target);
    }

    /** 本人当前轻场的一名来敌改追同行者，收场和撤队仍归原玩家。 */
    public static boolean threatenEscort(ServerPlayer owner, long ticket, Mob raider, LivingEntity escort) {
        var pending = CrisisEncounters.current(owner).orElseThrow().getPending();
        if (ticket <= 0 || pending.getRoadRequest() != ticket || !pending.getRaidersList().contains(raider.getStringUUID())
                || !huntingFor(raider, owner.getUUID()) || escort.level() != owner.level() || !escort.isAlive()) {
            return false;
        }
        ESCORTS.put(raider.getUUID(), escort.getUUID());
        raider.setTarget(escort);
        return raider.getTarget() == escort;
    }

    public static boolean acceptsTarget(Mob subject, LivingEntity target) {
        // 原版改目标时：不是招敌管着的随它；被魅惑的交给魅惑判断；撤走的只接受清空；盯人的只接受清空或那名玩家。
        if (!sent(subject) || HumanoidCharmController.controlled(subject) || target == null) {
            return true;
        }
        return quarry(subject).filter(target::equals).isPresent();
    }

    public static void playerDying(ServerPlayer player, DamageSource source) {
        // 玩家死前（掉落之前）：死在盯他的来人手里就是输了这一场。
        CrisisFabric.engine().endings().dying(player, source);
    }
}
