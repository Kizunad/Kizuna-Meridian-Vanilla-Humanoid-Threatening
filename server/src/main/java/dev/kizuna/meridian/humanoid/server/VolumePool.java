package dev.kizuna.meridian.humanoid.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Predicate;

/** 出生外卷的加权书目。只在合用的书之间比权重，同书的两种入门卷平分该书的机会。 */
public record VolumePool(double wanderingChance, double trueChance, double middleChance, List<Book> books) {
    public static final VolumePool DISABLED = new VolumePool(0, 0, 0, List.of());

    public record Book(String id, String nativeSpecies, int weight, List<String> upper, Optional<String> middle) {
        public Book {
            upper = upper.stream().sorted().toList();
        }
    }

    public VolumePool {
        books = books.stream().sorted(Comparator.comparing(Book::id)).toList();
    }

    public List<String> draw(UUID entity, String species, HumanoidTier tier, Predicate<String> usable) {
        if (tier == HumanoidTier.MORTAL) {
            return List.of();
        }
        double chance = tier == HumanoidTier.TRUE_QI ? trueChance : wanderingChance;
        if (sample(entity, "grant") >= chance) {
            return List.of();
        }
        Book selected = null;
        List<String> variants = List.of();
        double best = Double.POSITIVE_INFINITY;
        for (Book book : books) {
            if (book.weight() == 0 || book.nativeSpecies().equals(species)) {
                continue;
            }
            var available = book.upper().stream().filter(usable).toList();
            if (available.isEmpty()) {
                continue;
            }
            // 独立指数分布取最小值，入选概率正比于书的权重，和表的遍历顺序无关。
            double score = -Math.log1p(-sample(entity, "book/" + book.id())) / book.weight();
            if (score < best) {
                best = score;
                selected = book;
                variants = available;
            }
        }
        if (selected == null) {
            return List.of();
        }
        String upper = variants.stream().min(Comparator.comparingDouble(id -> sample(entity, "upper/" + id)))
                .orElseThrow();
        var volumes = new ArrayList<>(List.of(upper));
        if (tier == HumanoidTier.TRUE_QI && sample(entity, "middle/" + upper) < middleChance) {
            selected.middle().filter(usable).ifPresent(volumes::add);
        }
        return List.copyOf(volumes);
    }

    private static double sample(UUID entity, String key) {
        // 分开抽命中、书、入门卷和进阶，不推进身体生成器的随机序列。
        var bytes = (entity + "/" + key).getBytes(StandardCharsets.UTF_8);
        long seed = UUID.nameUUIDFromBytes(bytes).getMostSignificantBits();
        return new SplittableRandom(seed).nextDouble();
    }
}
