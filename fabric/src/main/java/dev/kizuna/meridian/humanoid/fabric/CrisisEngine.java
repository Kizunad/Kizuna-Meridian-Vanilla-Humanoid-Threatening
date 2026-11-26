package dev.kizuna.meridian.humanoid.fabric;

import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.CrisisRecord;
import dev.kizuna.meridian.humanoid.protocol.pb.CrisisMessages.Pending;
import dev.kizuna.meridian.humanoid.server.CrisisArrival;
import dev.kizuna.meridian.humanoid.server.CrisisFeuds;
import dev.kizuna.meridian.humanoid.server.CrisisHeat;
import dev.kizuna.meridian.humanoid.server.CrisisRecords;
import dev.kizuna.meridian.humanoid.server.CrisisRules;
import dev.kizuna.meridian.humanoid.server.CrisisSchedule;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.origin.fabric.Origin;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.technique.server.TechniqueService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.npc.villager.Villager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每名在线玩家一份招敌记录，在线时间、风声和排场都在这里往前走。
 * 记录平时只在内存里改，每分钟、出事时和下线时写回玩家身体；进服后身体读得到时才接上。
 */
final class CrisisEngine {
    static final int STEP_TICKS = 20;
    private static final int OBSERVE_TICKS = 100;
    private static final int FLUSH_TICKS = 1200;
    private static final long ROAD_REST = 2 * CrisisRules.DAY_TICKS;
    private static final Logger LOGGER = LoggerFactory.getLogger("meridian_humanoid/crisis");
    private final CrisisRules rules;
    private final CrisisBodies bodies;
    private final Map<UUID, CrisisRecord> records = new HashMap<>();
    private final Set<UUID> broken = new HashSet<>();
    private final Map<UUID, List<CrisisFeuds.Entry>> feuds = new HashMap<>();
    private final Map<UUID, Set<String>> knownVolumes = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final CrisisArrivals arrivals;
    private final CrisisEndings endings;
    private final CrisisOmens omens;

    /** 调试指令 strike 的结果。 */
    enum Strike { ARRIVED, NOT_ATTACHED, ALREADY_ARRIVED, OTHER_PARTY, UNKNOWN_PARTY, NO_SPOT }

    /** 调试指令 omen 的结果。 */
    enum Omen {
        RUMOR, NOTE, NOT_ATTACHED, ALREADY_ARRIVED, OTHER_PARTY, UNKNOWN_PARTY, ALL_GIVEN, NO_VILLAGER, NO_SPOT
    }

    CrisisEngine(CrisisRules rules, CrisisBodies bodies) {
        // 规则在启动时定死，身体宿主在构造时取齐。
        this.rules = rules;
        this.bodies = bodies;
        this.arrivals = new CrisisArrivals(rules);
        this.endings = new CrisisEndings(rules, this);
        this.omens = new CrisisOmens(rules);
    }

    CrisisEndings endings() {
        // 死亡事件和玩家死前的 mixin 从这里进收场。
        return endings;
    }

    CrisisRules rules() {
        // 指令和后续各步共用同一份规则。
        return rules;
    }

    void tick(MinecraftServer server) {
        // 每 20 tick 给每名在线玩家记一步在线时间；每 100 tick 看一次身体；每 1200 tick 写回一次。
        int serverTick = server.getTickCount();
        if (serverTick % STEP_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!records.containsKey(player.getUUID())) {
                load(player);
                continue;
            }
            boolean eventful = step(player, serverTick % OBSERVE_TICKS == 0);
            if (eventful || serverTick % FLUSH_TICKS == 0) {
                flush(player);
            }
        }
    }

    private boolean step(ServerPlayer player, boolean observe) {
        // 在线时间往前走、风声该落就落；到了看身体的时候比一比境界和开脉；出身的仇家补上、到了境界开始计日；
        // 来了人的看是不是逃掉了；仇家到了日子或风声够了就排一场；到了征兆的时刻给征兆；到点了就来人；
        // 记仇的人带队的走近了先说话。返回是否出了事要马上写回。
        CrisisRecord before = records.get(player.getUUID());
        CrisisRecord record = CrisisHeat.advance(before, STEP_TICKS, rules.heat().decay());
        if (observe) {
            record = observe(player, record);
        }
        record = endings.watch(player, feuds(player, record));
        Optional<CrisisSchedule.Raid> raid = nextRaid(player, record);
        if (raid.isPresent()) {
            record = schedule(player, record, raid.get());
        }
        CrisisRecord omened = omens.due(player, record);
        boolean omen = omened != record;
        Optional<CrisisRecord> arrived = arrive(player, omened);
        record = arrived.orElse(omened);
        omens.greet(player, record);
        records.put(player.getUUID(), record);
        boolean marked = dirty.remove(player.getUUID());
        return marked || raid.isPresent() || omen || arrived.isPresent()
                || before.getRoadCount() != record.getRoadCount()
                || before.getSettledCount() != record.getSettledCount()
                || !record.getFeudsList().equals(before.getFeudsList());
    }

    private Optional<CrisisRecord> arrive(ServerPlayer player, CrisisRecord record) {
        // 到点了还没来人才试；时辰、地方合不合适由来犯那边看。
        if (!CrisisSchedule.strikeDue(record)) {
            return Optional.empty();
        }
        return arrivals.whenFit(player, record, feudEntries(player), seed(player, record));
    }

    boolean requestRoad(ServerPlayer player, String party, long ticket) {
        // 只在完整来犯没有占用场次、当前签也没有受理过时尝试到场。
        var record = records.get(player.getUUID());
        if (record == null || ticket <= record.getRoadRequest() || record.hasPending()
                || !CrisisFabric.parties().containsKey(party) || nextRaid(player, record).isPresent()
                || !player.isAlive() || player.isCreative() || player.isSpectator()
                || player.level().dimension() != Level.OVERWORLD) {
            return false;
        }
        long last = Math.max(record.getRoadSettledAt(), record.getSettledAt());
        long now = record.getOnlineTicks();
        if (now - last < ROAD_REST) {
            return false;
        }
        var pending = Pending.newBuilder()
                .setParty(party)
                .setRoadRequest(ticket)
                .setScheduledAt(now)
                .setStrikeAt(now)
                .build();
        var scheduled = record.toBuilder()
                .setRoadRequest(ticket)
                .setPending(pending)
                .build();
        var arrived = arrivals.arrive(player, scheduled, List.of(), seed(player, record));
        return arrived.isPresent() && update(player, ignored -> arrived.orElseThrow());
    }

    boolean leaveRoad(ServerPlayer player, long ticket) {
        // 只撤仍在场的那一签，旧对话不会误结下一场。
        var record = records.get(player.getUUID());
        if (record == null || ticket <= 0 || !record.getPending().getArrived()
                || record.getPending().getRoadRequest() != ticket) {
            return false;
        }
        return update(player, current -> endings.escape(player, current));
    }

    Strike strike(ServerPlayer player, Optional<String> party) {
        // 调试：排着没来的立刻来；没排的按给的队伍表（缺省通用）排第 0 拨、不给征兆、立刻来；不看时辰。
        CrisisRecord record = records.get(player.getUUID());
        if (record == null) {
            return Strike.NOT_ATTACHED;
        }
        Optional<String> pendingParty = Optional.of(record).filter(CrisisRecord::hasPending)
                .map(present -> present.getPending().getParty());
        if (record.hasPending() && record.getPending().getArrived()) {
            return Strike.ALREADY_ARRIVED;
        }
        if (pendingParty.isPresent() && party.isPresent() && !party.equals(pendingParty)) {
            return Strike.OTHER_PARTY;
        }
        String chosen = pendingParty.or(() -> party).orElse(rules.genericParty());
        if (!CrisisFabric.parties().containsKey(chosen)) {
            return Strike.UNKNOWN_PARTY;
        }
        CrisisRecord scheduled = record;
        if (pendingParty.isEmpty()) {
            scheduled = CrisisSchedule.schedule(record, new CrisisSchedule.Raid(chosen, "", 0, 0), 0);
        }
        Optional<CrisisRecord> arrived = arrivals.arrive(player, CrisisArrival.strikeNow(scheduled),
                feudEntries(player), seed(player, record));
        arrived.ifPresent(present -> update(player, ignored -> present));
        return arrived.isPresent() ? Strike.ARRIVED : Strike.NO_SPOT;
    }

    Omen omen(ServerPlayer player, Optional<String> party) {
        // 调试：不等时刻，立刻给下一次征兆；没排着就先照风声招来的那样排一场（给成了才记下），
        // 队伍表按给的，缺省通用。给什么照平时的规矩，只是不等。
        CrisisRecord stored = records.get(player.getUUID());
        if (stored == null) {
            return Omen.NOT_ATTACHED;
        }
        Optional<Omen> wrongParty = omenPartyRefused(stored, party);
        if (wrongParty.isPresent()) {
            return wrongParty.get();
        }
        CrisisRecord record = stored.hasPending() ? stored : schedule(player, stored, omenRaid(stored, party));
        Optional<Omen> refused = omenRefused(record);
        if (refused.isPresent()) {
            return refused.get();
        }
        Optional<Villager> villager = omens.villager(player);
        CrisisSchedule.OmenStep kind = CrisisSchedule.omenKind(record.getPending(), villager.isPresent());
        if (kind == CrisisSchedule.OmenStep.WAIT) {
            return Omen.NO_VILLAGER;
        }
        if (!omens.give(player, kind, villager, record.getPending())) {
            return Omen.NO_SPOT;
        }
        update(player, ignored -> CrisisSchedule.omenDone(record, kind));
        return kind == CrisisSchedule.OmenStep.RUMOR ? Omen.RUMOR : Omen.NOTE;
    }

    private static Optional<Omen> omenPartyRefused(CrisisRecord record, Optional<String> party) {
        // 指定了队伍表：得有这张表，已排着没来人的那一场也得是这张表（已经来人的照旧说来人了）。
        if (party.isEmpty() || record.hasPending() && record.getPending().getArrived()) {
            return Optional.empty();
        }
        if (!CrisisFabric.parties().containsKey(party.get())) {
            return Optional.of(Omen.UNKNOWN_PARTY);
        }
        boolean other = record.hasPending() && !record.getPending().getParty().equals(party.get());
        return other ? Optional.of(Omen.OTHER_PARTY) : Optional.empty();
    }

    private CrisisSchedule.Raid omenRaid(CrisisRecord record, Optional<String> party) {
        // 调试指令排的一场：和风声招来的同一个来人时刻，只是队伍表可以指定。
        CrisisSchedule.Raid heat = CrisisSchedule.heatRaid(record, rules);
        return party.map(chosen -> new CrisisSchedule.Raid(chosen, heat.feud(), heat.stage(), heat.strikeAt()))
                .orElse(heat);
    }

    private static Optional<Omen> omenRefused(CrisisRecord record) {
        // 已经来人、征兆给完了，都给不了。
        if (record.getPending().getArrived()) {
            return Optional.of(Omen.ALREADY_ARRIVED);
        }
        return CrisisSchedule.omenLeft(record) ? Optional.empty() : Optional.of(Omen.ALL_GIVEN);
    }

    private Optional<CrisisSchedule.Raid> nextRaid(ServerPlayer player, CrisisRecord record) {
        // 出身的仇家先排，按写的先后；都没到日子才看风声。
        Optional<CrisisSchedule.Raid> feud = CrisisFeuds.due(record, feudEntries(player), rules);
        if (feud.isPresent() || !CrisisHeat.heatRaidDue(record, rules)) {
            return feud;
        }
        return Optional.of(CrisisSchedule.heatRaid(record, rules));
    }

    private CrisisRecord feuds(ServerPlayer player, CrisisRecord record) {
        // 出身里有、记录里没有的仇家补上；玩家到了 min_realm 的开始计日，第一拨哪天来按世界种子和玩家定。
        List<CrisisFeuds.Entry> entries = feudEntries(player);
        if (entries.isEmpty()) {
            return record;
        }
        return CrisisFeuds.start(CrisisFeuds.reconcile(record, entries), entries, seed(player, record));
    }

    boolean originKnown(ServerPlayer player) {
        // 这次在线已经收到过进服或选定事件，知道这名玩家有没有仇家了。
        return feuds.containsKey(player.getUUID());
    }

    List<CrisisFeuds.Entry> feudEntries(ServerPlayer player) {
        // 出身选定或进服时记下的；没选出身的玩家没有。
        return feuds.getOrDefault(player.getUUID(), List.of());
    }

    void chosen(ServerPlayer player, Origin origin) {
        // 选定出身：记下这名玩家的仇家，记录已经接上的话马上补进去、开始计日并写回。
        feuds.put(player.getUUID(), CrisisOrigins.entries(origin));
        update(player, record -> feuds(player, record));
    }

    void ready(ServerPlayer player, Optional<Origin> origin) {
        // 进服：老玩家的出身这时才查得到，对一遍，补上记录里还没有的仇家；记录还没接上的，接上后下一步补。
        feuds.put(player.getUUID(), origin.map(CrisisOrigins::entries).orElse(List.of()));
        update(player, record -> feuds(player, record));
    }

    private CrisisRecord observe(ServerPlayer player, CrisisRecord record) {
        // 读不到身体这一回就不看；读到了按境界和已开经脉数比较。
        Optional<BodySnapshot> body = bodies.read(player);
        if (body.isEmpty()) {
            return record;
        }
        return CrisisHeat.observe(record, observation(body.get()), rules.heat().rise());
    }

    static CrisisHeat.Observation observation(BodySnapshot body) {
        // 真炁看境界；开脉数只数已经开通的经脉。
        long opened = body.getMeridianStatesList().stream()
                .filter(state -> state.getOpening().getStage() == BodyMessages.MeridianOpeningStage.OPENED)
                .count();
        return new CrisisHeat.Observation(body.getRealm() == BodyMessages.Realm.TRUE_QI, (int) opened);
    }

    CrisisRecord schedule(ServerPlayer player, CrisisRecord record, CrisisSchedule.Raid raid) {
        // 征兆次数按世界种子、玩家和第几场定，重开服不变。
        int omens = CrisisSchedule.omenCount(seed(player, record), rules.omen());
        LOGGER.info("给玩家 {} 排上一场：队伍表 {}，仇家 {}，{} 次征兆", player.getScoreboardName(), raid.party(),
                raid.feud(), omens);
        return CrisisSchedule.schedule(record, raid, omens);
    }

    static long seed(ServerPlayer player, CrisisRecord record) {
        // 世界种子、玩家和已了结几场。
        return CrisisSchedule.seed(player.level().getServer().overworld().getSeed(), player.getUUID(),
                record.getSettledCount());
    }

    private void load(ServerPlayer player) {
        // 身体读得到才接上，同时记下此刻会的卷当底；记录坏了只记一次错误，这名玩家本次在线不再参与，也不拿空记录盖掉它。
        // 来了人的那一场不在这里重新登记：来人在上次下线时已清走，崩服留下的按孤儿清，记录由收场那边按逃了结。
        UUID id = player.getUUID();
        if (broken.contains(id)) {
            return;
        }
        Optional<BodySnapshot> body = bodies.read(player);
        if (body.isEmpty()) {
            return;
        }
        try {
            CrisisRecord record = CrisisRecords.read(body.get());
            var migrated = RetiredRaids.restore(player, record, endings, rules.genericParty());
            records.put(id, migrated);
            if (migrated != record) {
                flush(player);
            }
            knownVolumes.put(id, Set.copyOf(CrisisVolumes.known(id)));
        } catch (IllegalArgumentException corrupt) {
            broken.add(id);
            LOGGER.error("玩家 {} 的招敌记录损坏，本次在线不排招敌", player.getScoreboardName(), corrupt);
        }
    }

    Optional<CrisisRecord> record(ServerPlayer player) {
        // 还没接上（身体没读到）或记录损坏时为空。
        return Optional.ofNullable(records.get(player.getUUID()));
    }

    boolean update(ServerPlayer player, UnaryOperator<CrisisRecord> change) {
        // 指令和事件改记录都走这里，改完马上写回；还没接上的玩家改不了。
        CrisisRecord record = records.get(player.getUUID());
        if (record == null) {
            return false;
        }
        records.put(player.getUUID(), change.apply(record));
        flush(player);
        return true;
    }

    void flush(ServerPlayer player) {
        // 现读一次身体拿修订号再写；这一刻读不到或宿主拒绝就等下次，内存里的记录不丢。
        CrisisRecord record = records.get(player.getUUID());
        Optional<BodySnapshot> body = bodies.read(player);
        if (record == null) {
            return;
        }
        if (body.isEmpty()) {
            dirty.add(player.getUUID());
            return;
        }
        try {
            bodies.write(player, body.get(), record);
        } catch (IllegalStateException rejected) {
            dirty.add(player.getUUID());
            LOGGER.warn("玩家 {} 的招敌记录这次没写进身体，下次再写", player.getScoreboardName(), rejected);
        }
    }

    void learned(UUID entity, List<TechniqueService.KnownEntry> known) {
        // 会的卷变了：和上次比，新会的卷里稀有的各涨一次 rare_volume，下一步写回。底在接上记录时取，接上前学的不算；
        // 每只生人学卷也走到这里，不是接上了记录的玩家直接跳过。
        Set<String> before = knownVolumes.get(entity);
        if (before == null) {
            return;
        }
        List<String> now = CrisisVolumes.covered(known);
        long rare = now.stream().filter(volume -> !before.contains(volume) && CrisisVolumes.rare(volume, rules))
                .count();
        knownVolumes.put(entity, Set.copyOf(now));
        if (rare > 0) {
            records.computeIfPresent(entity, (id, record) -> CrisisHeat.raise(record,
                    rare * rules.heat().rise().rareVolume()));
            dirty.add(entity);
        }
    }

    void disconnect(ServerPlayer player) {
        // 下线：来了人的那一场算逃，名下的来人（盯人的、在撤的）当场清走；写回最后一段在线时间，然后放掉。
        // 此时玩家仍在在线名单，身体宿主可以检查修订并写回。
        UUID id = player.getUUID();
        records.computeIfPresent(id, (ignored, record) -> endings.leave(player, record));
        CrisisRaiders.release(id).forEach(raider -> CrisisRetreats.find(player.level().getServer(), raider)
                .ifPresent(Mob::discard));
        flush(player);
        records.remove(id);
        broken.remove(id);
        feuds.remove(id);
        knownVolumes.remove(id);
        dirty.remove(id);
        arrivals.forget(id);
        omens.forget(id);
    }

    void clear() {
        // 停服时玩家都已下线写回，这里只清内存。
        records.clear();
        broken.clear();
        feuds.clear();
        knownVolumes.clear();
        dirty.clear();
        arrivals.clear();
        omens.clear();
        CrisisRaiders.clear();
        CrisisRetreats.clear();
    }
}
