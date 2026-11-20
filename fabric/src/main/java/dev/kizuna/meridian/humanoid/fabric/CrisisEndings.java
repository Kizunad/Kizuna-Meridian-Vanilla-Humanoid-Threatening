package dev.kizuna.meridian.humanoid.fabric;

import com.google.protobuf.ByteString;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.server.CrisisArrival;
import dev.kizuna.meridian.humanoid.server.CrisisFeuds;
import dev.kizuna.meridian.humanoid.server.CrisisHeat;
import dev.kizuna.meridian.humanoid.server.CrisisOutcome;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.humanoid.server.HumanoidTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 来了人以后怎么收场：头目死了算赢，甩开了、撑过了期限、下线了算逃，死在来人手里算输；
 * 撤走途中的记仇的人被打死掉回它身上的东西；当着别的生人杀修炼的生人涨风声。
 */
final class CrisisEndings {
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid/crisis");
    private final CrisisRules rules;
    private final CrisisEngine engine;

    /** 来人和玩家之间这一眼：离最近的来人多远，有没有来人在逃脱距离内看得见玩家。 */
    private record Sight(double nearest, boolean seen) { }

    CrisisEndings(CrisisRules rules, CrisisEngine engine) {
        // 记录都在引擎里，这里只管收场。
        this.rules = rules;
        this.engine = engine;
    }

    CrisisRecord watch(ServerPlayer player, CrisisRecord record) {
        // 来了人以后每步看一眼：被看见就重新计时；甩开了、撑过了期限就算逃，来人撤走。
        // 记录里来了人、却一个也没登记的（崩服后重进），出身对过以后同样按逃了结。
        if (!arrived(record)) {
            return record;
        }
        if (!CrisisRaiders.tracking(player.getUUID())) {
            return engine.originKnown(player) ? escape(player, record) : record;
        }
        Sight sight = sight(player);
        CrisisRecord watched = sight.seen() ? CrisisOutcome.seen(record) : record;
        if (!CrisisOutcome.escaped(watched.getPending(), watched.getOnlineTicks(), sight.nearest(), rules.outcome())) {
            return watched;
        }
        return escape(player, watched);
    }

    private Sight sight(ServerPlayer player) {
        // 只看这名玩家所在世界里加载着的来人；一个都不在身边时离得无限远。
        double nearest = Double.POSITIVE_INFINITY;
        boolean seen = false;
        for (UUID id : CrisisRaiders.raiders(player.getUUID())) {
            if (player.level().getEntity(id) instanceof Mob raider && raider.isAlive()) {
                double distance = raider.distanceTo(player);
                nearest = Math.min(nearest, distance);
                seen |= distance <= rules.outcome().escapeDistance() && raider.hasLineOfSight(player);
            }
        }
        return new Sight(nearest, seen);
    }

    private CrisisRecord escape(ServerPlayer player, CrisisRecord record) {
        // 逃了：了结、头目记仇，来人撤走。
        LOGGER.info("玩家 {} 甩开了来人（队伍表 {}）", player.getScoreboardName(), record.getPending().getParty());
        CrisisRaiders.retreat(player.getUUID(), player.level().getServer().getTickCount());
        return settled(player, record, CrisisOutcome.Settlement.escape());
    }

    CrisisRecord leave(ServerPlayer player, CrisisRecord record) {
        // 下线：来了人的那一场算逃；来人由下线那边当场清走，不用撤。
        if (!arrived(record)) {
            return record;
        }
        LOGGER.info("玩家 {} 下线，来人那一场按逃了结", player.getScoreboardName());
        return settled(player, record, CrisisOutcome.Settlement.escape());
    }

    private CrisisRecord settled(ServerPlayer player, CrisisRecord record, CrisisOutcome.Settlement settlement) {
        // 种子按了结前的场数算。
        return CrisisOutcome.settle(record, settlement, engine.feudEntries(player), rules,
                CrisisEngine.seed(player, record));
    }

    void died(LivingEntity entity, DamageSource source) {
        // 死的是来人：是头目就赢了，是撤走途中的记仇的人就掉回它身上的东西，都放掉不再管；
        // 不是来人的修炼的生人当着别的生人被玩家杀了：风声涨。来人本就是冲玩家来的，杀它们不另涨。
        if (entity instanceof Mob mob && CrisisRaiders.sent(mob)) {
            leaderDied(mob);
            grudgeCaught(mob);
            return;
        }
        witnessed(entity, source);
    }

    private void leaderDied(Mob mob) {
        // 盯人的头目死了：赢。必掉的那卷和记仇的人身上的东西掉在它倒下的地方，随从撤走。
        Optional<ServerPlayer> player = online(mob, CrisisRaiders.quarryId(mob));
        Optional<CrisisRecord> record = player.flatMap(engine::record).filter(CrisisEndings::arrived)
                .filter(present -> present.getPending().getLeader().equals(mob.getUUID().toString()));
        if (record.isEmpty()) {
            return;
        }
        ServerLevel level = (ServerLevel) mob.level();
        List<ItemStack> drops = new ArrayList<>();
        spoils(player.get(), record.get()).flatMap(CrisisVolumes::scroll).ifPresent(drops::add);
        if (record.get().getPending().getGrudgeLeads()) {
            drops.addAll(CrisisSpoils.carried(level, record.get().getGrudge().getCarriedList()));
        }
        CrisisSpoils.drop(level, mob, drops);
        LOGGER.info("玩家 {} 打赢了一场（队伍表 {}），掉了 {} 件", player.get().getScoreboardName(),
                record.get().getPending().getParty(), drops.size());
        engine.update(player.get(), present -> settled(player.get(), present, CrisisOutcome.Settlement.win()));
        CrisisRaiders.dismiss(mob.getUUID());
        CrisisRaiders.retreat(player.get().getUUID(), level.getServer().getTickCount());
        won(player.get(), record.get());
    }

    private void won(ServerPlayer player, CrisisRecord before) {
        // 打赢一场的进度：「不速之客」；带队的是记仇的人加「冤家路窄」；仇家这一拨打完了最后一拨加「了断」。
        CrisisTrigger.fire(player, CrisisTrigger.Event.REPELLED);
        if (before.getPending().getGrudgeLeads()) {
            CrisisTrigger.fire(player, CrisisTrigger.Event.GRUDGE_SLAIN);
        }
        String feud = before.getPending().getFeud();
        boolean ended = !feud.isEmpty() && engine.record(player)
                .filter(after -> CrisisFeuds.ended(before, after, feud)).isPresent();
        if (ended) {
            CrisisTrigger.fire(player, CrisisTrigger.Event.FEUD_ENDED);
        }
    }

    private Optional<String> spoils(ServerPlayer player, CrisisRecord record) {
        // 仇家最后一拨的 foe_volume，不然头目会的、有卷轴的稀有卷里按这一场的种子挑一卷。
        Pending pending = record.getPending();
        long seed = CrisisArrival.seed(CrisisEngine.seed(player, record), pending);
        return CrisisOutcome.spoils(pending, CrisisArrival.foeVolume(pending, engine.feudEntries(player)),
                volume -> CrisisVolumes.rare(volume, rules) && CrisisVolumes.scroll(volume).isPresent(), seed);
    }

    private void grudgeCaught(Mob mob) {
        // 撤走途中的记仇的人被追上打死：身上的东西掉回来，这份仇了断。
        Optional<ServerPlayer> player = online(mob, CrisisRaiders.retreatingFrom(mob));
        Optional<CrisisRecord> record = player.flatMap(engine::record).filter(CrisisRecord::hasGrudge)
                .filter(present -> present.getGrudge().getEntity().equals(mob.getUUID().toString()));
        CrisisRaiders.dismiss(mob.getUUID());
        if (record.isEmpty()) {
            return;
        }
        ServerLevel level = (ServerLevel) mob.level();
        CrisisSpoils.drop(level, mob, CrisisSpoils.carried(level, record.get().getGrudge().getCarriedList()));
        LOGGER.info("玩家 {} 追上打死了撤走的记仇的人", player.get().getScoreboardName());
        engine.update(player.get(), present -> present.toBuilder().clearGrudge().build());
        CrisisTrigger.fire(player.get(), CrisisTrigger.Event.GRUDGE_SLAIN);
    }

    private void witnessed(LivingEntity victim, DamageSource source) {
        // 死的是修炼的生人、凶手是记录接上了的玩家，凶手 witness_radius 内另有活着的生人看得见他：风声涨一次。
        if (!(killer(source) instanceof ServerPlayer player) || engine.record(player).isEmpty()
                || !cultivator(victim)) {
            return;
        }
        double radius = rules.heat().rise().witnessRadius();
        boolean seen = !player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(radius),
                other -> other != victim && other.isAlive() && HumanoidSpeciesHooks.isLivingHumanoid(other)
                        && other.distanceTo(player) <= radius && other.hasLineOfSight(player)).isEmpty();
        if (seen) {
            engine.update(player, record -> CrisisHeat.raise(record, rules.heat().rise().witnessedKill()));
        }
    }

    private static boolean cultivator(LivingEntity entity) {
        // 档位看它实际的身体：不是生人、没有身体、凡人都不算。
        if (!HumanoidSpeciesHooks.isLivingHumanoid(entity)) {
            return false;
        }
        HumanoidBodyState body = ((HumanoidEntityAccess) entity).meridian$humanoidBody();
        return body != null && HumanoidTier.of(body.snapshot()) != HumanoidTier.MORTAL;
    }

    void dying(ServerPlayer player, DamageSource source) {
        // 死在盯他的来人手里（恼鬼、尖牙算它们的主人，最后打他的也算）：输。中档从身上拿走一件，记在头目身上；
        // 来人撤走，不守尸。
        Optional<CrisisRecord> record = engine.record(player).filter(CrisisEndings::arrived);
        boolean byRaider = Stream.of(source.getEntity(), killer(source), player.getKillCredit())
                .anyMatch(entity -> CrisisRaiders.huntingFor(entity, player.getUUID()));
        if (record.isEmpty() || !byRaider) {
            return;
        }
        ByteString taken = rules.outcome().lossPenalty() == CrisisRules.LossPenalty.MEDIUM ? CrisisSpoils.take(player)
                : ByteString.EMPTY;
        LOGGER.info("玩家 {} 死在来人手里（队伍表 {}），夺走 {} 件", player.getScoreboardName(),
                record.get().getPending().getParty(), taken.isEmpty() ? 0 : 1);
        engine.update(player, present -> settled(player, present, CrisisOutcome.Settlement.loss(taken)));
        CrisisRaiders.retreat(player.getUUID(), player.level().getServer().getTickCount());
    }

    private static Optional<ServerPlayer> online(Mob mob, Optional<UUID> player) {
        // 这名玩家此刻在线才收场；记录只给在线的玩家留着。
        return player.map(id -> mob.level().getServer().getPlayerList().getPlayer(id));
    }

    private static Entity killer(DamageSource source) {
        // 唤出来的（恼鬼、尖牙）算主人；其余就是伤害记在谁头上。
        Entity entity = source.getEntity();
        if (entity instanceof TraceableEntity traceable && traceable.getOwner() != null) {
            return traceable.getOwner();
        }
        return entity;
    }

    private static boolean arrived(CrisisRecord record) {
        // 排着的那一场已经来了人。
        return record.hasPending() && record.getPending().getArrived();
    }
}
