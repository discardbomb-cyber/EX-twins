package dev.hurtify.relicsaddon.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The shipped OBJ models stay compact: {@code tools/compact_obj.mjs} drops repeated {@code v} / {@code vt} /
 * {@code vn} records and renumbers the faces, and the generators write through it. This check parses every
 * OBJ under the models directory and fails on a repeated record (the same text twice) or on a face, line or
 * point index outside the records defined before it. Pure text; no Minecraft classes.
 */
public final class ObjCompactionCheck {
    private static final Set<String> RECORDS = Set.of("v", "vt", "vn");
    /** Which record type each slash-separated slot of an index token refers to, per statement. */
    private static final Map<String, String[]> INDEXED = Map.of(
        "f", new String[] {"v", "vt", "vn"},
        "l", new String[] {"v", "vt"},
        "p", new String[] {"v"});

    public static void main(String[] args) throws IOException {
        Path models = Path.of(args.length > 0 ? args[0] : "src/main/resources/assets/relics_addon/models");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(models)) {
            files = walk.filter(path -> path.toString().toLowerCase().endsWith(".obj")).sorted().toList();
        }
        if (files.isEmpty()) throw new AssertionError("no OBJ models under " + models);
        List<String> problems = new ArrayList<>();
        long bytes = 0;
        int faces = 0;
        for (Path file : files) {
            bytes += Files.size(file);
            faces += check(file, problems);
        }
        if (!problems.isEmpty()) {
            problems.forEach(System.err::println);
            throw new AssertionError(problems.size() + " OBJ compaction problem(s); run node tools/compact_obj.mjs");
        }
        System.out.println("OBJ models compact: " + files.size() + " files, " + faces + " faces, " + bytes + " bytes, no repeated v/vt/vn records, all indices in range");
    }

    /** Returns the file's face count; appends every problem found to {@code problems}. */
    private static int check(Path file, List<String> problems) throws IOException {
        Map<String, Set<String>> seen = Map.of("v", new HashSet<>(), "vt", new HashSet<>(), "vn", new HashSet<>());
        Map<String, int[]> counts = Map.of("v", new int[1], "vt", new int[1], "vn", new int[1]);
        int faces = 0, number = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            number++;
            String trimmed = line.strip();
            int space = trimmed.indexOf(' ');
            String word = space < 0 ? trimmed : trimmed.substring(0, space);
            if (RECORDS.contains(word)) {
                counts.get(word)[0]++;
                if (!seen.get(word).add(trimmed)) problems.add(file + ":" + number + ": repeated record '" + trimmed + "'");
                continue;
            }
            String[] slots = INDEXED.get(word);
            if (slots == null) continue;
            if (word.equals("f")) faces++;
            String[] tokens = trimmed.split("\\s+");
            for (int t = 1; t < tokens.length; t++) {
                String[] parts = tokens[t].split("/", -1);
                if (parts.length > slots.length) {
                    problems.add(file + ":" + number + ": too many index slots in '" + tokens[t] + "'");
                    continue;
                }
                for (int slot = 0; slot < parts.length; slot++) {
                    if (parts[slot].isEmpty()) continue;
                    int defined = counts.get(slots[slot])[0];
                    int index;
                    try {
                        index = Integer.parseInt(parts[slot]);
                    } catch (NumberFormatException e) {
                        problems.add(file + ":" + number + ": bad index '" + parts[slot] + "'");
                        continue;
                    }
                    if (index < 1 || index > defined) {
                        problems.add(file + ":" + number + ": " + slots[slot] + " index " + index + " outside 1.." + defined);
                    }
                }
            }
        }
        return faces;
    }
}
