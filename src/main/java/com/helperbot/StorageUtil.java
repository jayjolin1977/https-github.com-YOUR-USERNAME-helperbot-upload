package com.helperbot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Helpers for reading and filling the chests a helper stores things in. */
public final class StorageUtil {
	private StorageUtil() {}

	/** Only real storage blocks — never furnaces, hoppers etc., whose slots have special meanings. */
	public static boolean isStorageBlock(BlockState state) {
		Block b = state.getBlock();
		return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock;
	}

	public static boolean isLoaded(Level level, BlockPos pos) {
		return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
	}

	/** A double chest is stored once, under the position of whichever half sorts first. */
	public static BlockPos normalize(Level level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (s.getBlock() instanceof ChestBlock && s.hasProperty(ChestBlock.TYPE)
				&& s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos other = pos.relative(ChestBlock.getConnectedDirection(s));
			return other.asLong() < pos.asLong() ? other.immutable() : pos.immutable();
		}
		return pos.immutable();
	}

	public static Container getContainer(Level level, BlockPos pos) {
		if (pos == null || !isLoaded(level, pos)) return null;
		BlockState s = level.getBlockState(pos);
		if (s.getBlock() instanceof ChestBlock cb) {
			return ChestBlock.getContainer(cb, s, level, pos, true);
		}
		if (!isStorageBlock(s)) return null;
		return level.getBlockEntity(pos) instanceof Container c ? c : null;
	}

	/** The usable chests (loaded, de-duplicated). Also forgets saved chests that were broken. */
	public static List<BlockPos> resolve(Level level, List<BlockPos> saved) {
		LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
		Iterator<BlockPos> it = saved.iterator();
		while (it.hasNext()) {
			BlockPos p = it.next();
			if (!isLoaded(level, p)) continue;
			if (!isStorageBlock(level.getBlockState(p))) {
				it.remove();
				continue;
			}
			out.add(normalize(level, p));
		}
		return new ArrayList<>(out);
	}

	public static int maxFor(Container c, ItemStack stack) {
		return Math.min(stack.getMaxStackSize(), c.getMaxStackSize());
	}

	public static boolean hasRoomFor(Container c, ItemStack stack) {
		if (c == null || stack.isEmpty()) return false;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.isEmpty()) {
				if (c.canPlaceItem(i, stack)) return true;
			} else if (ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < maxFor(c, s)) {
				return true;
			}
		}
		return false;
	}

	/** Inserts a stack, topping up matching stacks first. Returns what didn't fit. */
	public static ItemStack insert(Container c, ItemStack stack) {
		ItemStack rest = stack.copy();
		for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, rest)) {
				int move = Math.min(rest.getCount(), maxFor(c, s) - s.getCount());
				if (move > 0) {
					s.grow(move);
					rest.shrink(move);
				}
			}
		}
		for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
			if (c.getItem(i).isEmpty() && c.canPlaceItem(i, rest)) {
				int move = Math.min(rest.getCount(), maxFor(c, rest));
				c.setItem(i, rest.split(move));
			}
		}
		c.setChanged();
		return rest;
	}

	public static int count(Container c, Item item) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.is(item)) n += s.getCount();
		}
		return n;
	}

	/** The chest that already holds the most of this item and still has room; else the first with room. */
	public static BlockPos bestChestFor(Level level, List<BlockPos> chests, ItemStack stack) {
		BlockPos best = null;
		int bestCount = -1;
		for (BlockPos p : chests) {
			Container c = getContainer(level, p);
			if (!hasRoomFor(c, stack)) continue;
			int n = count(c, stack.getItem());
			if (n > bestCount) {
				bestCount = n;
				best = p;
			}
		}
		return best;
	}

	public static BlockPos pickDepositChest(Level level, List<BlockPos> chests, SimpleContainer carried) {
		for (int i = 0; i < carried.getContainerSize(); i++) {
			ItemStack s = carried.getItem(i);
			if (s.isEmpty()) continue;
			BlockPos p = bestChestFor(level, chests, s);
			if (p != null) return p;
		}
		return null;
	}

	/** Puts every carried stack whose best home is {@code at} into that chest. Returns items moved. */
	public static int depositAt(Level level, List<BlockPos> chests, BlockPos at, SimpleContainer carried) {
		Container c = getContainer(level, at);
		if (c == null) return 0;
		int moved = 0;
		for (int i = 0; i < carried.getContainerSize(); i++) {
			ItemStack s = carried.getItem(i);
			if (s.isEmpty()) continue;
			if (!at.equals(bestChestFor(level, chests, s))) continue;
			ItemStack rest = insert(c, s);
			moved += s.getCount() - rest.getCount();
			carried.setItem(i, rest);
		}
		carried.setChanged();
		return moved;
	}

	// ---------- sorting ----------

	/** For each item type, the chest that holds the most of it (ties go to the earlier chest). */
	private static Map<Item, BlockPos> homes(Level level, List<BlockPos> chests) {
		Map<Item, BlockPos> home = new HashMap<>();
		Map<Item, Integer> most = new HashMap<>();
		for (BlockPos p : chests) {
			Container c = getContainer(level, p);
			if (c == null) continue;
			Map<Item, Integer> here = new HashMap<>();
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack s = c.getItem(i);
				if (!s.isEmpty()) here.merge(s.getItem(), s.getCount(), Integer::sum);
			}
			for (Map.Entry<Item, Integer> e : here.entrySet()) {
				if (e.getValue() > most.getOrDefault(e.getKey(), 0)) {
					most.put(e.getKey(), e.getValue());
					home.put(e.getKey(), p);
				}
			}
		}
		return home;
	}

	private static boolean misplaced(Level level, Map<Item, BlockPos> home, BlockPos at, ItemStack s) {
		BlockPos h = home.get(s.getItem());
		return h != null && !h.equals(at) && hasRoomFor(getContainer(level, h), s);
	}

	/** A chest holding something that belongs in a different chest, or null when everything is in place. */
	public static BlockPos findMisplacedSource(Level level, List<BlockPos> chests) {
		Map<Item, BlockPos> home = homes(level, chests);
		for (BlockPos p : chests) {
			Container c = getContainer(level, p);
			if (c == null) continue;
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack s = c.getItem(i);
				if (!s.isEmpty() && misplaced(level, home, p, s)) return p;
			}
		}
		return null;
	}

	/** Takes misplaced stacks out of {@code from} into the helper's pockets. Returns items taken. */
	public static int takeMisplaced(Level level, List<BlockPos> chests, BlockPos from, SimpleContainer carried) {
		Container c = getContainer(level, from);
		if (c == null) return 0;
		Map<Item, BlockPos> home = homes(level, chests);
		int taken = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.isEmpty() || !misplaced(level, home, from, s)) continue;
			ItemStack rest = carried.addItem(s.copy());
			taken += s.getCount() - rest.getCount();
			c.setItem(i, rest);
			if (!rest.isEmpty()) break; // pockets are full
		}
		c.setChanged();
		return taken;
	}

	/** Merges partial stacks and orders a chest by item name. Never changes what's inside. */
	public static void compact(Container c) {
		List<ItemStack> stacks = new ArrayList<>();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty()) stacks.add(s.copy());
		}
		if (stacks.isEmpty()) return;
		stacks.sort(Comparator.comparing((ItemStack s) -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));

		List<ItemStack> merged = new ArrayList<>();
		for (ItemStack s : stacks) {
			for (ItemStack m : merged) {
				if (s.isEmpty()) break;
				if (ItemStack.isSameItemSameComponents(m, s)) {
					int move = Math.min(s.getCount(), maxFor(c, m) - m.getCount());
					if (move > 0) {
						m.grow(move);
						s.shrink(move);
					}
				}
			}
			if (!s.isEmpty()) merged.add(s);
		}

		for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, ItemStack.EMPTY);
		for (int i = 0; i < merged.size() && i < c.getContainerSize(); i++) c.setItem(i, merged.get(i));
		c.setChanged();
	}

	public static void compactAll(Level level, List<BlockPos> chests) {
		for (BlockPos p : chests) {
			Container c = getContainer(level, p);
			if (c != null) compact(c);
		}
	}
}
