package dev.hurtify.relicsaddon.adapter.out.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hurtify.relicsaddon.adapter.out.world.McVectors;
import dev.hurtify.relicsaddon.domain.shield.ShieldCellMove;
import dev.hurtify.relicsaddon.domain.shield.ShieldImpact;
import dev.hurtify.relicsaddon.domain.shield.ShieldImpactHistory;
import dev.hurtify.relicsaddon.domain.shield.ShieldSettings;
import dev.hurtify.relicsaddon.domain.shield.ShieldStackState;
import dev.hurtify.relicsaddon.domain.shield.ShieldTopology;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * Saved and synced forms of the shield components (shield_stack_state, shield_impact, shield_impacts and
 * shield_settings), moved unchanged out of the records they encode. The bytes and NBT are frozen by
 * golden/codecs.txt.
 */
public final class ShieldCodecs {
    public static final Codec<ShieldCellMove> CELL_MOVE = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, ShieldTopology.CELL_COUNT - 1).fieldOf("from").forGetter(ShieldCellMove::from),
            Codec.intRange(0, ShieldTopology.CELL_COUNT - 1).fieldOf("to").forGetter(ShieldCellMove::to)
    ).apply(instance, ShieldCellMove::new));

    public static final Codec<ShieldStackState> STACK_STATE = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("enabled").forGetter(ShieldStackState::enabled),
            Codec.INT.fieldOf("front").forGetter(ShieldStackState::front),
            Codec.INT.fieldOf("left").forGetter(ShieldStackState::left),
            Codec.INT.fieldOf("right").forGetter(ShieldStackState::right),
            Codec.INT.fieldOf("back").forGetter(ShieldStackState::back),
            Codec.INT.fieldOf("lastHitPanel").forGetter(ShieldStackState::lastHitPanel),
            Codec.FLOAT.fieldOf("lastAbsorbed").forGetter(ShieldStackState::lastAbsorbed),
            Codec.LONG.fieldOf("lastActiveGameTime").forGetter(ShieldStackState::lastActiveGameTime),
            // Missing on legacy saves is intentionally empty, not a free refill.
            Codec.INT.optionalFieldOf("sharedBuffer", 0).forGetter(ShieldStackState::sharedBuffer),
            Codec.INT.listOf(0, ShieldTopology.CELL_COUNT).optionalFieldOf("cells", List.of()).forGetter(ShieldStackState::cells),
            CELL_MOVE.listOf(0, 3).optionalFieldOf("moves", List.of()).forGetter(ShieldStackState::moves),
            Codec.LONG.optionalFieldOf("gatherTime", -1L).forGetter(ShieldStackState::gatherTime)
    ).apply(instance, ShieldStackState::new));
    public static final StreamCodec<ByteBuf, ShieldStackState> STACK_STATE_STREAM = new StreamCodec<>() {
        @Override public void encode(ByteBuf buffer, ShieldStackState state) {
            buffer.writeBoolean(state.enabled()).writeByte(state.lastHitPanel()).writeFloat(state.lastAbsorbed())
                    .writeLong(state.lastActiveGameTime()).writeShort(state.sharedBuffer());
            for (int hp : state.cells()) buffer.writeByte(hp);
            buffer.writeLong(state.gatherTime()).writeByte(state.moves().size());
            for (ShieldCellMove move : state.moves()) buffer.writeShort(move.from()).writeShort(move.to());
        }
        @Override public ShieldStackState decode(ByteBuf buffer) {
            boolean enabled = buffer.readBoolean(); int hit = buffer.readByte(); float absorbed = buffer.readFloat();
            long active = buffer.readLong(); int pool = buffer.readUnsignedShort();
            var cells = new ArrayList<Integer>(ShieldTopology.CELL_COUNT);
            for (int id = 0; id < ShieldTopology.CELL_COUNT; id++) cells.add((int) buffer.readUnsignedByte());
            long gathered = buffer.readLong(); int count = buffer.readUnsignedByte();
            if (count > 3) throw new IllegalArgumentException("Oversized shield relocation packet");
            var moves = new ArrayList<ShieldCellMove>(count);
            for (int id = 0; id < count; id++) moves.add(new ShieldCellMove(buffer.readUnsignedShort(), buffer.readUnsignedShort()));
            return new ShieldStackState(enabled, 12, 12, 12, 12, hit, absorbed, active, pool, cells, moves, gathered);
        }
    };

    public static final Codec<ShieldImpact> IMPACT = RecordCodecBuilder.create(instance -> instance.group(
            Vec3.CODEC.xmap(McVectors::toDomain, McVectors::toMc).fieldOf("normal").forGetter(ShieldImpact::normal),
            Codec.LONG.fieldOf("gameTime").forGetter(ShieldImpact::gameTime),
            Codec.INT.fieldOf("panel").forGetter(ShieldImpact::panel),
            Codec.FLOAT.fieldOf("absorbed").forGetter(ShieldImpact::absorbed),
            Codec.BOOL.fieldOf("broken").forGetter(ShieldImpact::broken),
            Codec.intRange(0, ShieldTopology.CELL_COUNT - 1).listOf(0, 3).optionalFieldOf("brokenCells", List.of()).forGetter(ShieldImpact::brokenCells),
            Codec.DOUBLE.optionalFieldOf("distance", -1.0).forGetter(ShieldImpact::distance),
            Codec.FLOAT.optionalFieldOf("strike", 0F).forGetter(ShieldImpact::strike)
    ).apply(instance, ShieldImpact::new));
    public static final StreamCodec<ByteBuf, ShieldImpact> IMPACT_STREAM = ByteBufCodecs.fromCodec(IMPACT);

    public static final Codec<ShieldImpactHistory> IMPACT_HISTORY = IMPACT.listOf(0, ShieldImpactHistory.LIMIT)
            .xmap(ShieldImpactHistory::new, ShieldImpactHistory::impacts);
    public static final StreamCodec<ByteBuf, ShieldImpactHistory> IMPACT_HISTORY_STREAM = ByteBufCodecs.fromCodec(IMPACT_HISTORY);

    public static final Codec<ShieldSettings> SETTINGS = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("radius", 2.0).forGetter(ShieldSettings::radius),
            Codec.STRING.optionalFieldOf("coverage", "allies").forGetter(ShieldSettings::coverage)
    ).apply(instance, ShieldSettings::new));
    public static final StreamCodec<ByteBuf, ShieldSettings> SETTINGS_STREAM = ByteBufCodecs.fromCodec(SETTINGS);

    private ShieldCodecs() {
    }

    // ShieldStackState.DEFAULT builds the shield topology. That used to happen when shield_stack_state
    // registered and initialised the record holding these codecs; shield_settings, the first registration
    // to touch this class, now does it, still inside the data-component RegisterEvent.
    static {
        Objects.requireNonNull(ShieldStackState.DEFAULT);
    }
}
