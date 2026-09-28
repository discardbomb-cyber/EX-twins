package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.drone.HiveType;
import it.hurts.sskirillss.relics.items.relics.base.data.research.ResearchTemplate;

final class HiveResearch {
    static ResearchTemplate forType(HiveType type) {
        int[][] points = switch (type) {
            case RF -> new int[][] {{15,15},{15,4},{5,8},{25,8},{4,23},{26,23},{15,27},{10,15},{20,15}};
            case MANA -> new int[][] {{15,15},{15,3},{6,8},{3,18},{9,27},{21,27},{27,18},{24,8},{15,23}};
            case TWINS -> new int[][] {{15,15},{15,3},{3,15},{15,27},{27,15},{9,9},{9,21},{21,21},{21,9}};
        };
        var builder = ResearchTemplate.builder();
        for (int index = 0; index < points.length; index++) builder.star(index, points[index][0], points[index][1]);
        for (int index = 1; index < points.length; index++) {
            builder.link(index, index == points.length - 1 ? 1 : index + 1);
            if (index % 2 == 1) builder.link(0, index);
        }
        return builder.build();
    }
    private HiveResearch() { }
}
