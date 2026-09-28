package dev.hurtify.relicsaddon.relic;

import it.hurts.sskirillss.relics.items.relics.base.data.research.ResearchTemplate;

/**
 * Coordinates are native Relics research units (five GUI pixels per unit).
 * Relics 0.12.8 draws 17px stars in its 110x155px card canvas at (5*x, 5*y),
 * so every graph stays inside the padded x=3..19, y=3..27 envelope.
 */
public final class ShieldResearch {
    public static ResearchTemplate protection(RelicRole role) {
        return switch (role) {
            case RF_SHIELD -> graph(new int[][] {
                    {12, 5}, {6, 15}, {10, 15}, {8, 26}, {17, 12}, {12, 12}
            }, new int[][] {{0,1},{1,2},{2,3},{3,4},{4,5},{5,0}});
            case MANA_SHIELD -> graph(new int[][] {
                    {11, 5}, {6, 13}, {5, 20}, {8, 26}, {14, 26}, {18, 20}, {17, 13}, {11, 18}
            }, new int[][] {{0,1},{1,2},{2,3},{3,4},{4,5},{5,6},{6,0},{0,7},{7,4}});
            case TWINS_SHIELD -> graph(new int[][] {
                    {6, 7}, {4, 15}, {6, 23}, {10, 15}, {15, 7}, {13, 15}, {15, 23}, {19, 15}
            }, new int[][] {{0,1},{1,2},{2,3},{3,0},{3,5},{4,5},{5,6},{6,7},{7,4}});
            default -> throw new IllegalArgumentException("No research for suspended drones");
        };
    }

    public static ResearchTemplate distribution(RelicRole role) {
        if (role == RelicRole.MANA_SHIELD) return graph(new int[][] {
                {11,5},{8,10},{11,16},{14,10},{5,20},{7,27},{11,25},{15,27},{18,20}
        }, new int[][] {{0,1},{1,2},{2,3},{3,0},{2,4},{4,5},{2,6},{2,8},{8,7}});
        if (role == RelicRole.TWINS_SHIELD) return graph(new int[][] {
                {11,5},{9,8},{11,12},{13,8},{6,18},{4,22},{6,26},{9,22},
                {16,18},{13,22},{16,26},{19,22}
        }, new int[][] {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},
                {8,9},{9,10},{10,11},{11,8},{2,4},{2,8},{7,9}});
        return graph(new int[][] {
                {11,5},{11,12},{5,18},{11,20},{17,18},{4,27},{11,27},{18,27}
        }, new int[][] {{0,1},{1,2},{1,3},{1,4},{2,5},{3,6},{4,7}});
    }

    public static ResearchTemplate gather(RelicRole role) {
        if (role == RelicRole.MANA_SHIELD) return graph(new int[][] {
                {8,5},{5,9},{4,17},{7,23},{11,19},{15,23},{18,17},{17,9},{14,5},{11,27}
        }, new int[][] {{0,1},{1,2},{2,3},{3,4},{4,5},{5,6},{6,7},{7,8},{4,9}});
        return graph(new int[][] {
                {4,6},{7,11},{18,6},{15,11},{11,16},{4,26},{7,21},{18,26},{15,21}
        }, new int[][] {{0,1},{1,4},{2,3},{3,4},{5,6},{6,4},{7,8},{8,4}});
    }

    public static ResearchTemplate restoration(RelicRole role) {
        return switch (role) {
            case RF_SHIELD -> graph(new int[][] {
                    {11,5},{6,10},{16,10},{4,18},{11,16},{18,18},{7,27},{15,27}
            }, new int[][] {{0,1},{0,2},{1,4},{2,4},{1,3},{2,5},{3,6},{4,6},{4,7},{5,7}});
            case MANA_SHIELD -> graph(new int[][] {
                    {11,5},{7,9},{15,9},{4,16},{11,15},{18,16},{6,23},{11,27},{16,23}
            }, new int[][] {{0,1},{0,2},{1,4},{2,4},{1,3},{2,5},{3,6},{4,6},{4,8},{5,8},{6,7},{7,8}});
            case TWINS_SHIELD -> graph(new int[][] {
                    {6,6},{16,6},{11,11},{4,17},{8,20},{14,20},{18,17},{11,27}
            }, new int[][] {{0,2},{1,2},{2,3},{2,6},{3,4},{4,7},{6,5},{5,7},{4,5}});
            default -> throw new IllegalArgumentException("No shield restoration research for suspended drones");
        };
    }

    public static ResearchTemplate stabilization(RelicRole role) {
        if (role != RelicRole.TWINS_SHIELD) throw new IllegalArgumentException("Only Twins stabilizes repairs");
        return graph(new int[][] {
                {11,5},{6,10},{16,10},{4,17},{11,15},{18,17},{7,23},{11,27},{15,23}
        }, new int[][] {{0,1},{0,2},{1,4},{2,4},{1,3},{2,5},{3,6},{4,6},{4,8},{5,8},{6,7},{7,8}});
    }

    private static ResearchTemplate graph(int[][] points, int[][] edges) {
        var builder = ResearchTemplate.builder();
        for (int id = 0; id < points.length; id++) builder.star(id, points[id][0], points[id][1]);
        for (int[] edge : edges) builder.link(edge[0], edge[1]);
        return builder.build();
    }

    private ShieldResearch() { }
}
