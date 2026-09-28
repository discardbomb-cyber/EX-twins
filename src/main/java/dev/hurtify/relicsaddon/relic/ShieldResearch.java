package dev.hurtify.relicsaddon.relic;

import it.hurts.sskirillss.relics.items.relics.base.data.research.ResearchTemplate;

/** Coordinates are native Relics research units (five GUI pixels per unit). */
public final class ShieldResearch {
    public static ResearchTemplate protection(RelicRole role) {
        return switch (role) {
            case RF_SHIELD -> graph(new int[][] {
                    {17, 4}, {7, 15}, {13, 15}, {10, 27}, {23, 12}, {16, 12}
            }, new int[][] {{0,1},{1,2},{2,3},{3,4},{4,5},{5,0}});
            case MANA_SHIELD -> graph(new int[][] {
                    {15, 4}, {8, 13}, {6, 20}, {10, 26}, {20, 26}, {24, 20}, {22, 13}, {15, 18}
            }, new int[][] {{0,1},{1,2},{2,3},{3,4},{4,5},{5,6},{6,0},{0,7},{7,4}});
            case TWINS_SHIELD -> graph(new int[][] {
                    {8, 7}, {3, 15}, {8, 23}, {13, 15}, {22, 7}, {17, 15}, {22, 23}, {27, 15}
            }, new int[][] {{0,1},{1,2},{2,3},{3,0},{3,5},{4,5},{5,6},{6,7},{7,4}});
            default -> throw new IllegalArgumentException("No research for suspended drones");
        };
    }

    public static ResearchTemplate distribution(RelicRole role) {
        if (role == RelicRole.MANA_SHIELD) return graph(new int[][] {
                {15,4},{10,10},{15,16},{20,10},{5,20},{9,27},{15,25},{21,27},{25,20}
        }, new int[][] {{0,1},{1,2},{2,3},{3,0},{2,4},{4,5},{2,6},{2,8},{8,7}});
        if (role == RelicRole.TWINS_SHIELD) return graph(new int[][] {
                {15,4},{11,8},{15,12},{19,8},{7,18},{3,22},{7,26},{11,22},
                {23,18},{19,22},{23,26},{27,22}
        }, new int[][] {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},
                {8,9},{9,10},{10,11},{11,8},{2,4},{2,8},{7,9}});
        return graph(new int[][] {
                {15,4},{15,12},{6,18},{15,20},{24,18},{4,27},{15,28},{26,27}
        }, new int[][] {{0,1},{1,2},{1,3},{1,4},{2,5},{3,6},{4,7}});
    }

    public static ResearchTemplate gather(RelicRole role) {
        if (role == RelicRole.MANA_SHIELD) return graph(new int[][] {
                {10,4},{5,9},{4,17},{9,23},{15,19},{21,23},{26,17},{25,9},{20,4},{15,28}
        }, new int[][] {{0,1},{1,2},{2,3},{3,4},{4,5},{5,6},{6,7},{7,8},{4,9}});
        return graph(new int[][] {
                {4,6},{9,11},{26,6},{21,11},{15,16},{4,26},{9,21},{26,26},{21,21}
        }, new int[][] {{0,1},{1,4},{2,3},{3,4},{5,6},{6,4},{7,8},{8,4}});
    }

    private static ResearchTemplate graph(int[][] points, int[][] edges) {
        var builder = ResearchTemplate.builder();
        for (int id = 0; id < points.length; id++) builder.star(id, points[id][0], points[id][1]);
        for (int[] edge : edges) builder.link(edge[0], edge[1]);
        return builder.build();
    }

    private ShieldResearch() { }
}
