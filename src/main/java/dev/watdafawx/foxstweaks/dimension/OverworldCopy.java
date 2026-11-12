package dev.watdafawx.foxstweaks.dimension;

import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Shared bits of the overworld-copy dimension: its key, the config toggle, and what counts as a frame. */
public final class OverworldCopy {
    public static final ResourceKey<Level> COPY =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "overworld_copy"));

    private static List<? extends String> parsedFrom;
    private static List<Block> frameBlocks = List.of();
    private static List<TagKey<Block>> frameTags = List.of();

    /** The datapack condition also reads this, so an unloaded config means "on" (the default). */
    public static boolean enabled() {
        return !Config.SPEC.isLoaded() || Config.OVERWORLD_COPY.get();
    }

    public static boolean isFrame(BlockState state) {
        if (!Config.SPEC.isLoaded()) {
            return false;
        }

        parse();

        if (state.is(Blocks.OBSIDIAN)) {
            return false;
        }

        for (Block block : frameBlocks) {
            if (state.is(block)) {
                return true;
            }
        }

        for (TagKey<Block> tag : frameTags) {
            if (state.is(tag)) {
                return true;
            }
        }

        return false;
    }

    /** What a generated portal is built from: the first configured block, else crying obsidian. */
    public static BlockState frameForGeneration() {
        if (Config.SPEC.isLoaded()) {
            parse();

            if (!frameBlocks.isEmpty()) {
                return frameBlocks.get(0).defaultBlockState();
            }

            for (TagKey<Block> tag : frameTags) {
                for (var holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                    if (!holder.value().defaultBlockState().is(Blocks.OBSIDIAN)) {
                        return holder.value().defaultBlockState();
                    }
                }
            }
        }

        return Blocks.CRYING_OBSIDIAN.defaultBlockState();
    }

    private static synchronized void parse() {
        List<? extends String> source = Config.OVERWORLD_COPY_FRAME.get();

        if (source == parsedFrom) {
            return;
        }

        List<Block> blocks = new ArrayList<>();
        List<TagKey<Block>> tags = new ArrayList<>();

        for (String entry : source) {
            boolean tag = entry.startsWith("#");
            ResourceLocation id = ResourceLocation.tryParse(tag ? entry.substring(1) : entry);

            if (id == null) {
                LogUtils.getLogger().warn("overworldCopyPortalFrame: '{}' is not a valid id, ignored", entry);
            } else if (tag) {
                tags.add(TagKey.create(Registries.BLOCK, id));
            } else if (BuiltInRegistries.BLOCK.containsKey(id)) {
                blocks.add(BuiltInRegistries.BLOCK.get(id));
            } else {
                LogUtils.getLogger().warn("overworldCopyPortalFrame: unknown block '{}', ignored", entry);
            }
        }

        frameBlocks = blocks;
        frameTags = tags;
        parsedFrom = source;
    }

    private OverworldCopy() {
    }
}
