package dev.hurtify.relicsaddon.gametest;

import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** Read-only diagnosis of explicitly selected local test-player data. */
public final class ShieldSaveDiagnostic {
    public static void main(String[] args) throws Exception {
        for (String file : args) {
            var root = NbtIo.readCompressed(Path.of(file), NbtAccounter.unlimitedHeap());
            System.out.println("SAVE " + file + " health=" + root.getFloat("Health") + " mode=" + root.getInt("playerGameType"));
            inspect(root, "player");
        }
    }

    private static void inspect(Tag tag, String path) {
        if (tag instanceof CompoundTag compound) {
            if (compound.getString("id").startsWith("relics_addon:")) {
                System.out.println(path + " = " + compound);
                return;
            }
            for (String key : compound.getAllKeys()) {
                if (key.contains("curios") || key.contains("research")) System.out.println(path + "." + key + " = " + compound.get(key));
                else inspect(compound.get(key), path + "." + key);
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) inspect(list.get(i), path + "[" + i + "]");
        }
    }
}
