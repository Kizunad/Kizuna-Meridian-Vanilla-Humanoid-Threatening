package dev.kizuna.meridian.humanoid.server;

import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/** 档位标签：恰好指向一个档位才算数。 */
public final class HumanoidTierTest {
    private static final String TRUE_QI_TAG = HumanoidTier.TAG_PREFIX + "true_qi";
    private static final String MORTAL_TAG = HumanoidTier.TAG_PREFIX + "mortal";

    @Test
    public void oneTierTagNamesTheTier() {
        // 三档各有自己的标签；别的标签不碍事，同一个标签写两遍也只算一个。
        for (HumanoidTier tier : HumanoidTier.values()) {
            assertEquals(Optional.of(tier), HumanoidTier.tagged(Set.of(HumanoidTier.TAG_PREFIX + tier.id(), "other")));
        }
        assertEquals(Optional.of(HumanoidTier.TRUE_QI), HumanoidTier.tagged(List.of(TRUE_QI_TAG, TRUE_QI_TAG)));
    }

    @Test
    public void missingMisspeltOrConflictingTagsNameNothing() {
        // 没有标签、档位名写错、大小写不对、同时指了两个档：都当没指定，照自然抽档。
        assertEquals(Optional.empty(), HumanoidTier.tagged(Set.of()));
        assertEquals(Optional.empty(), HumanoidTier.tagged(Set.of("true_qi", "meridian_humanoid.tier")));
        assertEquals(Optional.empty(), HumanoidTier.tagged(Set.of(HumanoidTier.TAG_PREFIX + "adept")));
        assertEquals(Optional.empty(), HumanoidTier.tagged(Set.of(HumanoidTier.TAG_PREFIX + "TRUE_QI")));
        assertEquals(Optional.empty(), HumanoidTier.tagged(Set.of(TRUE_QI_TAG, MORTAL_TAG)));
        assertEquals(Optional.of(HumanoidTier.MORTAL),
                HumanoidTier.tagged(Set.of(MORTAL_TAG, HumanoidTier.TAG_PREFIX + "adept")));
    }
}
