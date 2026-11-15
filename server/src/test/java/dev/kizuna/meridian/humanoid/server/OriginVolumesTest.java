package dev.kizuna.meridian.humanoid.server;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.Test;

/** 出身开局卷：要装着，前置卷要写在同一出身更前面。 */
public class OriginVolumesTest {
    private static final Set<String> INSTALLED = Set.of("test:upper", "test:lower", "test:other");
    private static final Predicate<String> IS_INSTALLED = INSTALLED::contains;
    private static final Function<String, List<String>> PREREQUISITES =
            volume -> volume.equals("test:lower") ? List.of("test:upper") : List.of();

    @Test
    public void installedVolumesWithPrerequisitesFirstPass() {
        // 上卷在前、下卷在后；没有前置的卷单独写也行；没写开局卷的出身不用核对。
        OriginVolumes.require(Map.of("test:a", List.of("test:upper", "test:lower"), "test:b", List.of("test:other"),
                "test:c", List.of()), IS_INSTALLED, PREREQUISITES);
    }

    @Test
    public void missingVolumeIsRejected() {
        // 卷没装：报出身和卷。
        var error = assertThrows(IllegalArgumentException.class, () -> OriginVolumes.require(
                Map.of("test:a", List.of("test:gone")), IS_INSTALLED, PREREQUISITES));
        String message = error.getMessage();
        assertTrue(message, message.contains("test:a") && message.contains("test:gone"));
    }

    @Test
    public void prerequisiteMissingOrAfterIsRejected() {
        // 下卷的前置上卷没写、或写在下卷后面，都拒。
        assertThrows(IllegalArgumentException.class, () -> OriginVolumes.require(
                Map.of("test:a", List.of("test:lower")), IS_INSTALLED, PREREQUISITES));
        var error = assertThrows(IllegalArgumentException.class, () -> OriginVolumes.require(
                Map.of("test:a", List.of("test:lower", "test:upper")), IS_INSTALLED, PREREQUISITES));
        assertTrue(error.getMessage(), error.getMessage().contains("test:upper"));
    }
}
