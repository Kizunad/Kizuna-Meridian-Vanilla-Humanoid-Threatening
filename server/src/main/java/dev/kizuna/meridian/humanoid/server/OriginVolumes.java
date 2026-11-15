package dev.kizuna.meridian.humanoid.server;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 出身开局卷的核对：选定出身时按出身 JSON 里写的先后逐卷学，前置卷没会时功法库会拒绝那一卷，
 * 所以每一卷都要已装进功法注册表，它的前置卷也要写在同一个出身更前面的开局卷里；不对就启动失败并报清。
 */
public final class OriginVolumes {
    private OriginVolumes() {
        // 只有静态核对。
    }

    /**
     * @param volumesByOrigin 出身 ID → 它的开局卷，按写的先后
     * @param installed 这卷在不在功法注册表里
     * @param prerequisites 一卷的前置卷
     */
    public static void require(Map<String, List<String>> volumesByOrigin, Predicate<String> installed,
                               Function<String, List<String>> prerequisites) {
        // 每个出身单独核对，报错里写出身和卷。
        volumesByOrigin.forEach((origin, volumes) -> requireOrigin(origin, volumes, installed, prerequisites));
    }

    private static void requireOrigin(String origin, List<String> volumes, Predicate<String> installed,
                                      Function<String, List<String>> prerequisites) {
        // 先看装没装，再看前置卷是不是排在前面；同一卷写两次也算排在前面，第二次学是空操作。
        for (int index = 0; index < volumes.size(); index++) {
            String volume = volumes.get(index);
            if (!installed.test(volume)) {
                throw HumanoidErrors.invalid(HumanoidErrors.ORIGIN + ": 出身 " + origin + " 的开局卷没装：" + volume);
            }
            List<String> earlier = volumes.subList(0, index);
            for (String required : prerequisites.apply(volume)) {
                if (!earlier.contains(required)) {
                    throw HumanoidErrors.invalid(HumanoidErrors.ORIGIN + ": 出身 " + origin + " 的开局卷 " + volume
                            + " 要先会前置卷 " + required + "，得写在它前面");
                }
            }
        }
    }
}
