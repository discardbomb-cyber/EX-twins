package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.drone.HiveType;
import it.hurts.sskirillss.relics.items.relics.base.data.research.ResearchTemplate;

final class HiveResearch {
    static ResearchTemplate forType(HiveType type) {
        int[][] points = switch (type) {
            // Native Relics 0.12.8 canvas: 110x155px, 17px star sprites at 5px per unit.
            case RF -> new int[][] {{11,15},{11,5},{5,8},{17,8},{4,23},{18,23},{11,27},{8,15},{14,15}};
            case MANA -> new int[][] {{11,15},{11,4},{5,8},{4,18},{7,27},{15,27},{18,18},{17,8},{11,23}};
            case TWINS -> new int[][] {{11,15},{11,4},{4,15},{11,27},{18,15},{7,9},{7,21},{15,21},{15,9}};
        };
        var builder = ResearchTemplate.builder();
        for (int index = 0; index < points.length; index++) builder.star(index, points[index][0], points[index][1]);
        for (int index = 1; index < points.length; index++) {
            builder.link(index, index == points.length - 1 ? 1 : index + 1);
            if (index % 2 == 1) builder.link(0, index);
        }
        return builder.build();
    }
    static ResearchTemplate upgrade(HiveType type, String id) {
        int[][] points;
        int[][] edges;
        if (id.equals(HiveUpgrades.COMBAT)) {
            points = new int[][] {{11,4},{5,11},{9,15},{4,24},{11,20},{18,24},{13,15},{17,11}};
            edges = new int[][] {{0,1},{1,2},{2,3},{3,4},{4,5},{5,6},{6,7},{7,0},{2,6}};
        } else if (id.equals(HiveUpgrades.SUPPORT)) {
            points = new int[][] {{9,5},{13,5},{13,12},{18,12},{18,18},{13,18},{13,26},{9,26},{9,18},{4,18},{4,12},{9,12}};
            edges = new int[points.length][2];
            for (int i = 0; i < points.length; i++) edges[i] = new int[] {i, (i + 1) % points.length};
        } else {
            points = new int[][] {{11,4},{5,8},{4,17},{7,25},{15,25},{18,17},{17,8},{11,13},{11,21}};
            edges = new int[][] {{0,1},{1,2},{2,3},{3,4},{4,5},{5,6},{6,0},{1,7},{6,7},{7,8},{8,3},{8,4}};
        }
        var builder = ResearchTemplate.builder();
        for (int i = 0; i < points.length; i++) {
            int x = points[i][0], y = points[i][1];
            if (type == HiveType.MANA) x += i % 2 == 0 ? -1 : 1;
            if (type == HiveType.TWINS) { x = 22 - x; y += i % 2 == 0 ? 1 : -1; }
            builder.star(i, x, y);
        }
        for (int[] edge : edges) builder.link(edge[0], edge[1]);
        return builder.build();
    }
    private HiveResearch() { }
}
