package com.smi7i.sandevistan.mech;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Последствия способностей костюма: земля после удара «покоцанная» — трава вытоптана в землю и грубую землю,
 * камень в булыжник, кирпич в треснувший, растения и снег сбиты, а у самого центра изредка вмятины в один блок.
 * Блоки не выпадают; неразрушимые/очень твёрдые, блоки с содержимым и жидкости не трогаются.
 */
public final class GroundScar {
    private static final Random RNG = new Random();

    private GroundScar() {}

    /** Круг радиуса r вокруг точки. power 0..1.5 — сила (сколько блоков меняется и есть ли вмятины). */
    public static void circle(ServerLevel level, Vec3 at, double r, double power) {
        for (int x = (int) Math.floor(-r); x <= Math.ceil(r); x++) {
            for (int z = (int) Math.floor(-r); z <= Math.ceil(r); z++) {
                double d = Math.sqrt(x * x + z * z) / r;
                if (d > 1.0) continue;
                // К краю — реже и с рваной границей.
                double chance = power * (0.95 - 0.75 * d * d) + (RNG.nextDouble() - 0.5) * 0.25;
                scarColumn(level, at.x + x, at.y, at.z + z, chance, d < 0.35 ? power * 0.18 * (1 - d / 0.35) : 0);
            }
        }
    }

    /** Полоса вперёд от origin по dir: от from до to блоков, ширина от w0 до w1 (рваные края). */
    public static void strip(ServerLevel level, Vec3 origin, Vec3 dir, double from, double to, double w0, double w1, double power) {
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (double d = from; d <= to; d += 0.5) {
            double k = (d - from) / Math.max(0.1, to - from);
            double w = w0 + (w1 - w0) * k;
            for (double s = -w / 2; s <= w / 2; s += 0.5) {
                Vec3 p = origin.add(dir.scale(d)).add(side.scale(s));
                if (!done.add(BlockPos.asLong((int) Math.floor(p.x), 0, (int) Math.floor(p.z)))) continue;
                double edge = Math.abs(s) / Math.max(0.25, w / 2);
                double chance = power * (0.9 - 0.55 * edge - 0.25 * k) + (RNG.nextDouble() - 0.5) * 0.25;
                scarColumn(level, p.x, origin.y, p.z, chance, edge < 0.4 && k < 0.5 ? power * 0.06 : 0);
            }
        }
    }

    /** Один столбец: ищем поверхность возле высоты y, сбиваем растения/снег, портим верхний блок, изредка — вмятина. */
    private static void scarColumn(ServerLevel level, double x, double y, double z, double chance, double dentChance) {
        if (chance <= 0 || RNG.nextDouble() > chance) return;
        BlockPos pos = BlockPos.containing(x, y + 2, z);
        BlockPos top = null;
        for (int i = 0; i < 7; i++, pos = pos.below()) {
            BlockState st = level.getBlockState(pos);
            if (st.isAir()) continue;
            if (st.canBeReplaced() && st.getFluidState().isEmpty()) {
                // Трава, цветы, снежный слой — сбиваются.
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                continue;
            }
            if (!st.getCollisionShape(level, pos).isEmpty()) top = pos.immutable();
            break;
        }
        if (top == null) return;
        BlockState st = level.getBlockState(top);
        if (!safe(level, top, st)) return;
        // Вмятина: верхний блок исчезает, под ним — тоже «побитый».
        if (dentChance > 0 && RNG.nextDouble() < dentChance && level.getBlockState(top.above()).isAir()) {
            BlockState below = level.getBlockState(top.below());
            if (!below.isAir() && safe(level, top.below(), below)) {
                level.setBlock(top, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                BlockState dam = damaged(below);
                if (dam != null) level.setBlock(top.below(), dam, Block.UPDATE_ALL);
                return;
            }
        }
        BlockState dam = damaged(st);
        if (dam != null) level.setBlock(top, dam, Block.UPDATE_ALL);
    }

    private static boolean safe(ServerLevel level, BlockPos pos, BlockState st) {
        float hardness = st.getDestroySpeed(level, pos);
        return hardness >= 0 && hardness <= 30 && !st.hasBlockEntity() && st.getFluidState().isEmpty();
    }

    /** Во что превращается блок после удара (null — не меняется). */
    private static BlockState damaged(BlockState st) {
        Block b = st.getBlock();
        double r = RNG.nextDouble();
        if (b == Blocks.GRASS_BLOCK || b == Blocks.PODZOL || b == Blocks.MYCELIUM || b == Blocks.MOSS_BLOCK || b == Blocks.DIRT_PATH
                || b == Blocks.FARMLAND) {
            return (r < 0.45 ? Blocks.COARSE_DIRT : r < 0.85 ? Blocks.DIRT : Blocks.ROOTED_DIRT).defaultBlockState();
        }
        if (b == Blocks.DIRT) return (r < 0.6 ? Blocks.COARSE_DIRT : Blocks.ROOTED_DIRT).defaultBlockState();
        if (b == Blocks.STONE || b == Blocks.SMOOTH_STONE) return (r < 0.8 ? Blocks.COBBLESTONE : Blocks.GRAVEL).defaultBlockState();
        if (b == Blocks.ANDESITE || b == Blocks.DIORITE || b == Blocks.GRANITE) return (r < 0.6 ? Blocks.COBBLESTONE : Blocks.GRAVEL).defaultBlockState();
        if (b == Blocks.DEEPSLATE) return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        if (b == Blocks.STONE_BRICKS) return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        if (b == Blocks.DEEPSLATE_BRICKS) return Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState();
        if (b == Blocks.DEEPSLATE_TILES) return Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState();
        if (b == Blocks.POLISHED_BLACKSTONE_BRICKS) return Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        if (b == Blocks.NETHER_BRICKS) return Blocks.CRACKED_NETHER_BRICKS.defaultBlockState();
        if (b == Blocks.SANDSTONE || b == Blocks.SMOOTH_SANDSTONE) return Blocks.SAND.defaultBlockState();
        if (b == Blocks.RED_SANDSTONE || b == Blocks.SMOOTH_RED_SANDSTONE) return Blocks.RED_SAND.defaultBlockState();
        if (b == Blocks.SNOW_BLOCK || b == Blocks.POWDER_SNOW) return Blocks.DIRT.defaultBlockState();
        if (b == Blocks.NETHERRACK && r < 0.3) return Blocks.MAGMA_BLOCK.defaultBlockState();
        return null;
    }
}
