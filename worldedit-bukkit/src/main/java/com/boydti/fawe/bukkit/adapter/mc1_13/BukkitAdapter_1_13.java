package com.boydti.fawe.bukkit.adapter.mc1_13;

import com.boydti.fawe.Fawe;
import com.boydti.fawe.FaweCache;
import com.boydti.fawe.bukkit.adapter.DelegateLock;
import com.boydti.fawe.bukkit.adapter.NMSAdapter;
import com.boydti.fawe.config.Settings;
import com.boydti.fawe.object.collection.BitArray4096;
import com.boydti.fawe.util.MathMan;
import com.boydti.fawe.util.ReflectionUtils;
import com.boydti.fawe.util.TaskManager;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import io.papermc.lib.PaperLib;
import net.jpountz.util.UnsafeUtils;
import net.minecraft.server.v1_13_R2.Block;
import net.minecraft.server.v1_13_R2.Chunk;
import net.minecraft.server.v1_13_R2.ChunkCoordIntPair;
import net.minecraft.server.v1_13_R2.ChunkSection;
import net.minecraft.server.v1_13_R2.DataBits;
import net.minecraft.server.v1_13_R2.DataPalette;
import net.minecraft.server.v1_13_R2.DataPaletteBlock;
import net.minecraft.server.v1_13_R2.DataPaletteLinear;
import net.minecraft.server.v1_13_R2.GameProfileSerializer;
import net.minecraft.server.v1_13_R2.IBlockData;
import net.minecraft.server.v1_13_R2.PlayerChunk;
import net.minecraft.server.v1_13_R2.PlayerChunkMap;
import org.bukkit.craftbukkit.v1_13_R2.CraftChunk;
import org.bukkit.craftbukkit.v1_13_R2.CraftWorld;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

public final class BukkitAdapter_1_13 extends NMSAdapter {
    /*
    NMS fields
    */
    public final static Field fieldBits;
    public final static Field fieldPalette;
    public final static Field fieldSize;

    public final static Field fieldFluidCount;
    public final static Field fieldTickingBlockCount;
    public final static Field fieldNonEmptyBlockCount;

    private final static Field fieldDirtyCount;
    private final static Field fieldDirtyBits;

    private static final int CHUNKSECTION_BASE;
    private static final int CHUNKSECTION_SHIFT;

    private static final Field fieldLock;

    static {
        try {
            fieldSize = DataPaletteBlock.class.getDeclaredField("i");
            fieldSize.setAccessible(true);
            fieldBits = DataPaletteBlock.class.getDeclaredField("a");
            fieldBits.setAccessible(true);
            fieldPalette = DataPaletteBlock.class.getDeclaredField("h");
            fieldPalette.setAccessible(true);

            fieldFluidCount = ChunkSection.class.getDeclaredField("e");
            fieldFluidCount.setAccessible(true);
            fieldTickingBlockCount = ChunkSection.class.getDeclaredField("tickingBlockCount");
            fieldTickingBlockCount.setAccessible(true);
            fieldNonEmptyBlockCount = ChunkSection.class.getDeclaredField("nonEmptyBlockCount");
            fieldNonEmptyBlockCount.setAccessible(true);

            fieldDirtyCount = PlayerChunk.class.getDeclaredField("dirtyCount");
            fieldDirtyCount.setAccessible(true);
            fieldDirtyBits = PlayerChunk.class.getDeclaredField("h");
            fieldDirtyBits.setAccessible(true);

            {
                Field tmp;
                try {
                    tmp = DataPaletteBlock.class.getDeclaredField("writeLock");
                } catch (NoSuchFieldException paper) {
                    tmp = DataPaletteBlock.class.getDeclaredField("j");
                }
                ReflectionUtils.setAccessibleNonFinal(tmp);
                fieldLock = tmp;
                fieldLock.setAccessible(true);
            }

            Unsafe unsafe = UnsafeUtils.getUNSAFE();
            CHUNKSECTION_BASE = unsafe.arrayBaseOffset(ChunkSection[].class);
            int scale = unsafe.arrayIndexScale(ChunkSection[].class);
            if ((scale & (scale - 1)) != 0)
                throw new Error("data type scale not a power of two");
            CHUNKSECTION_SHIFT = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable rethrow) {
            rethrow.printStackTrace();
            throw new RuntimeException(rethrow);
        }
    }

    protected static boolean setSectionAtomic(ChunkSection[] sections, ChunkSection expected, ChunkSection value, int layer) {
        long offset = ((long) layer << CHUNKSECTION_SHIFT) + CHUNKSECTION_BASE;
        if (layer >= 0 && layer < sections.length) {
            return UnsafeUtils.getUNSAFE().compareAndSwapObject(sections, offset, expected, value);
        }
        return false;
    }

    protected static DelegateLock applyLock(ChunkSection section) {
        try {
            synchronized (section) {
                DataPaletteBlock<IBlockData> blocks = section.getBlocks();
                Lock currentLock = (Lock) fieldLock.get(blocks);
                if (currentLock instanceof DelegateLock) {
                    return (DelegateLock) currentLock;
                }
                DelegateLock newLock = new DelegateLock(currentLock);
                fieldLock.set(blocks, newLock);
                return newLock;
            }
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static Chunk ensureLoaded(net.minecraft.server.v1_13_R2.World nmsWorld, int X, int Z) {
        Chunk nmsChunk = nmsWorld.getChunkIfLoaded(X, Z);
        if (nmsChunk != null) {
            return nmsChunk;
        }
        if (Fawe.isMainThread()) {
            return nmsWorld.getChunkAt(X, Z);
        }
        if (PaperLib.isPaper()) {
            CraftWorld craftWorld = nmsWorld.getWorld();
            CompletableFuture<org.bukkit.Chunk> future = craftWorld.getChunkAtAsync(X, Z, true);
            try {
                CraftChunk chunk = (CraftChunk) future.get();
                return chunk.getHandle();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        // TODO optimize
        return TaskManager.IMP.sync(() -> nmsWorld.getChunkAt(X, Z));
    }

    public static PlayerChunk getPlayerChunk(net.minecraft.server.v1_13_R2.WorldServer nmsWorld, final int cx, final int cz) {
        PlayerChunkMap chunkMap = nmsWorld.getPlayerChunkMap();
        PlayerChunk playerChunk = chunkMap.getChunk(cx, cz);
        if (playerChunk == null) {
            return null;
        }
        return playerChunk;
    }

    public static void sendChunk(net.minecraft.server.v1_13_R2.WorldServer nmsWorld, int X, int Z, int mask) {
        PlayerChunk playerChunk = getPlayerChunk(nmsWorld, X, Z);
        if (playerChunk == null) {
            return;
        }
        if (playerChunk.e()) {
            Chunk nmsChunk = playerChunk.chunk;
            ChunkSection[] sections = nmsChunk.getSections();
            for (int layer = 0; layer < 16; layer++) {
                if (sections[layer] == null && (mask & (1 << layer)) != 0) {
                    sections[layer] = new ChunkSection(layer << 4, nmsWorld.worldProvider.g());
                }
            }
            TaskManager.IMP.sync(() -> {
                try {
                    int dirtyBits = fieldDirtyBits.getInt(playerChunk);
                    if (dirtyBits == 0) {
                        nmsWorld.getPlayerChunkMap().a(playerChunk);
                    }
                    if (mask == 0) {
                        dirtyBits = 65535;
                    } else {
                        dirtyBits |= mask;
                    }

                    fieldDirtyBits.set(playerChunk, dirtyBits);
                    fieldDirtyCount.set(playerChunk, 64);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
                return null;
            });
            return;
        }
        return;
    }

    /*
    NMS conversion
     */
    public static ChunkSection newChunkSection(final int layer, final char[] blocks, boolean light) {
        return newChunkSection(layer, null, blocks, light);
    }

    public static ChunkSection newChunkSection(final int layer, final Function<Integer, char[]> get, char[] set, boolean light) {
        if (set == null) {
            return newChunkSection(layer, light);
        }
        final int[] blockToPalette = FaweCache.INSTANCE.getBLOCK_TO_PALETTE().get();
        final int[] paletteToBlock = FaweCache.INSTANCE.getPALETTE_TO_BLOCK().get();
        final long[] blockStates = FaweCache.INSTANCE.getBLOCK_STATES().get();
        final int[] blocksCopy = FaweCache.INSTANCE.getSECTION_BLOCKS().get();
        try {
            int[] num_palette_buffer = new int[1];
            int air;
            if (get == null) {
                air = createPalette(blockToPalette, paletteToBlock, blocksCopy, num_palette_buffer, set);
            } else {
                air = createPalette(layer, blockToPalette, paletteToBlock, blocksCopy, num_palette_buffer, get, set);
            }
            int num_palette = num_palette_buffer[0];
            // BlockStates
            int bitsPerEntry = MathMan.log2nlz(num_palette - 1);
            if (Settings.IMP.PROTOCOL_SUPPORT_FIX || num_palette != 1) {
                bitsPerEntry = Math.max(bitsPerEntry, 4); // Protocol support breaks <4 bits per entry
            } else {
                bitsPerEntry = Math.max(bitsPerEntry, 1); // For some reason minecraft needs 4096 bits to store 0 entries
            }

            final int blockBitArrayEnd = (bitsPerEntry * 4096) >> 6;
            if (num_palette == 1) {
                for (int i = 0; i < blockBitArrayEnd; i++) blockStates[i] = 0;
            } else {
                final BitArray4096 bitArray = new BitArray4096(blockStates, bitsPerEntry);
                bitArray.fromRaw(blocksCopy);
            }

            ChunkSection section = newChunkSection(layer, light);
            // set palette & data bits
            final DataPaletteBlock<IBlockData> dataPaletteBlocks = section.getBlocks();
            // private DataPalette<T> h;
            // protected DataBits a;
            final long[] bits = Arrays.copyOfRange(blockStates, 0, blockBitArrayEnd);
            final DataBits nmsBits = new DataBits(bitsPerEntry, 4096, bits);
            final DataPalette<IBlockData> palette;
//                palette = new DataPaletteHash<>(Block.REGISTRY_ID, bitsPerEntry, dataPaletteBlocks, GameProfileSerializer::d, GameProfileSerializer::a);
            palette = new DataPaletteLinear<>(Block.REGISTRY_ID, bitsPerEntry, dataPaletteBlocks, GameProfileSerializer::d);

            // set palette
            for (int i = 0; i < num_palette; i++) {
                final int ordinal = paletteToBlock[i];
                blockToPalette[ordinal] = Integer.MAX_VALUE;
                final BlockState state = BlockTypesCache.states[ordinal];
                final IBlockData ibd = ((BlockMaterial_1_13) state.getMaterial()).getState();
                palette.a(ibd);
            }
            try {
                fieldBits.set(dataPaletteBlocks, nmsBits);
                fieldPalette.set(dataPaletteBlocks, palette);
                fieldSize.set(dataPaletteBlocks, bitsPerEntry);
                setCount(0, 4096 - air, section);
            } catch (final IllegalAccessException | NoSuchFieldException e) {
                throw new RuntimeException(e);
            }

            return section;
        } catch (final Throwable e){
            Arrays.fill(blockToPalette, Integer.MAX_VALUE);
            throw e;
        }
    }

    private static ChunkSection newChunkSection(int layer, boolean light) {
        return new ChunkSection(layer << 4, light);
    }

    public static void setCount(final int tickingBlockCount, final int nonEmptyBlockCount, final ChunkSection section) throws NoSuchFieldException, IllegalAccessException {
        fieldFluidCount.setShort(section, (short) 0); // TODO FIXME
        fieldTickingBlockCount.setShort(section, (short) tickingBlockCount);
        fieldNonEmptyBlockCount.setShort(section, (short) nonEmptyBlockCount);
    }
}
