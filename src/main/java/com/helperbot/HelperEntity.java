package com.helperbot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A worker that gathers blocks and items around a spot you choose and carries
 * them to your storage chests. All of its thinking happens on the server.
 */
public class HelperEntity extends PathfinderMob {
	public enum Mode { IDLE, FOLLOW, MINE_ORES, MINE_STONE, CHOP, COLLECT, DEPOSIT, SORT }

	private static final double REACH = 4.5;
	private static final int MAX_TREE_LOGS = 200;
	private static final int NAME_COLOR = 0xFFAA00;
	private static final TagKey<Block> C_ORES =
			TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "ores"));

	private enum Walk { ARRIVED, WALKING, STUCK }

	// ---- saved state ----
	private final SimpleContainer inventory = new SimpleContainer(27);
	private UUID ownerUuid;
	private Mode mode = Mode.IDLE;
	private Mode resumeMode = Mode.IDLE;
	private BlockPos anchor;
	private int radius = 16;
	private final List<BlockPos> chests = new ArrayList<>();

	// ---- working state (not saved; rebuilt as needed) ----
	private BlockPos target;
	private int breakTicks, breakNeeded, ticksOnTarget, repaths;
	private ItemEntity targetItem;
	private int itemTicks;
	private final Set<Long> blacklist = new HashSet<>();
	private final Set<UUID> itemBlacklist = new HashSet<>();
	private int idleTicks, scanCooldown;
	private BlockPos chestTarget;
	private int walkTicks, failedDeposits, sortStalls;

	public HelperEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
		this.setCanPickUpLoot(false);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 40.0)
				.add(Attributes.MOVEMENT_SPEED, 0.3)
				.add(Attributes.FOLLOW_RANGE, 48.0);
	}

	@Override
	public void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
	}

	// ======================================================================
	// Public API used by commands
	// ======================================================================

	public UUID getOwnerUuid() { return ownerUuid; }
	public void setOwner(UUID uuid) { this.ownerUuid = uuid; }
	public Mode getMode() { return mode; }
	public SimpleContainer getInventory() { return inventory; }
	public List<BlockPos> getChests() { return chests; }

	public int carriedCount() {
		int n = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) n += inventory.getItem(i).getCount();
		return n;
	}

	public boolean addChest(BlockPos pos) {
		Level l = level();
		BlockPos n = StorageUtil.normalize(l, pos);
		for (BlockPos p : chests) {
			if (StorageUtil.normalize(l, p).equals(n)) return false;
		}
		chests.add(n);
		return true;
	}

	public boolean removeChest(BlockPos pos) {
		Level l = level();
		BlockPos n = StorageUtil.normalize(l, pos);
		return chests.removeIf(p -> p.equals(pos) || StorageUtil.normalize(l, p).equals(n));
	}

	public void startTask(Mode newMode, BlockPos where, int range) {
		clearWork();
		blacklist.clear();
		itemBlacklist.clear();
		this.mode = newMode;
		this.resumeMode = Mode.IDLE;
		this.anchor = where.immutable();
		this.radius = range;
		if (newMode == Mode.CHOP) {
			setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
		} else if (newMode == Mode.MINE_ORES || newMode == Mode.MINE_STONE) {
			setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		}
	}

	public void setSimpleMode(Mode newMode) {
		clearWork();
		this.mode = newMode;
		this.resumeMode = Mode.IDLE;
	}

	public void startDeposit() {
		clearWork();
		this.mode = Mode.DEPOSIT;
		this.resumeMode = Mode.IDLE;
	}

	// ======================================================================
	// Main loop (server only)
	// ======================================================================

	@Override
	public void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);

		if (tickCount % 40 == 0 && getHealth() < getMaxHealth()) heal(1.0f);
		if (tickCount % 10 == 0 && mode != Mode.FOLLOW && mode != Mode.IDLE) vacuumItems(level);

		switch (mode) {
			case IDLE -> {}
			case FOLLOW -> tickFollow(level);
			case MINE_ORES, MINE_STONE, CHOP -> tickGather(level);
			case COLLECT -> tickCollect(level, true);
			case DEPOSIT -> tickDeposit(level);
			case SORT -> tickSort(level);
		}
	}

	// ---------------------------------------------------------------- follow

	private void tickFollow(ServerLevel level) {
		ServerPlayer owner = getOwnerPlayer(level);
		if (owner == null || owner.level() != level) return;
		getLookControl().setLookAt(owner);
		double d = distanceToSqr(owner);
		if (d > 400.0) {
			snapTo(owner.getX(), owner.getY(), owner.getZ(), getYRot(), getXRot());
			getNavigation().stop();
		} else if (d > 9.0) {
			if (tickCount % 10 == 0 || getNavigation().isDone()) getNavigation().moveTo(owner, 1.2);
		} else {
			getNavigation().stop();
		}
	}

	// ---------------------------------------------------------------- gather (mine / chop)

	private void tickGather(ServerLevel level) {
		if (anchor == null) { setSimpleMode(Mode.IDLE); return; }
		if (isInventoryFull()) { goDeposit(level); return; }

		if (target == null) {
			if (scanCooldown > 0) {
				scanCooldown--;
			} else {
				target = findBlockTarget(level);
				scanCooldown = target == null ? 40 : 0;
				ticksOnTarget = 0;
				breakTicks = 0;
				breakNeeded = 0;
				repaths = 0;
			}
			if (target == null) {
				// Nothing to break right now: tidy up any drops in the area instead.
				if (tickCollect(level, false)) { idleTicks = 0; return; }
				if (++idleTicks > 200) finishTask(level, "Nothing left to " + verb() + " here.");
				return;
			}
		}
		idleTicks = 0;

		BlockState state = level.getBlockState(target);
		if (!isWanted(level, target, state, true)) { dropTarget(level, false); return; }
		if (++ticksOnTarget > 400) { dropTarget(level, true); return; }

		Vec3 c = Vec3.atCenterOf(target);
		getLookControl().setLookAt(c.x, c.y, c.z);

		if (getEyePosition().distanceTo(c) <= REACH) {
			getNavigation().stop();
			if (breakNeeded == 0) breakNeeded = computeBreakTicks(level, target, state);
			breakTicks++;
			if (breakTicks % 5 == 1) swing(InteractionHand.MAIN_HAND);
			level.destroyBlockProgress(getId(), target, Math.min(9, breakTicks * 10 / Math.max(1, breakNeeded)));
			if (breakTicks >= breakNeeded) {
				BlockPos done = target;
				dropTarget(level, false);
				harvest(level, done, state);
			}
		} else if (getNavigation().isDone()) {
			if (++repaths > 3 || !getNavigation().moveTo(c.x, target.getY(), c.z, 1.0)) {
				dropTarget(level, true); // can't get there — try something else
			}
		}
	}

	private void dropTarget(ServerLevel level, boolean blacklistIt) {
		if (target != null) {
			level.destroyBlockProgress(getId(), target, -1);
			if (blacklistIt) blacklist.add(target.asLong());
		}
		target = null;
		breakTicks = 0;
		breakNeeded = 0;
		ticksOnTarget = 0;
		repaths = 0;
	}

	private String verb() {
		return switch (mode) {
			case MINE_ORES -> "mine (no reachable ores)";
			case MINE_STONE -> "mine (no reachable stone)";
			case CHOP -> "chop (no reachable trees)";
			default -> "do";
		};
	}

	private int computeBreakTicks(ServerLevel level, BlockPos pos, BlockState state) {
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0) return Integer.MAX_VALUE;
		float speed = Math.max(1.0f, getMainHandItem().getDestroySpeed(state));
		int ticks = (int) Math.ceil(hardness * 30.0f / speed);
		return Math.max(3, Math.min(200, ticks));
	}

	private BlockPos findBlockTarget(ServerLevel level) {
		int r = radius;
		int yMin, yMax;
		switch (mode) {
			case MINE_STONE -> { yMin = 0; yMax = 4; }   // clears a room from floor height up
			case CHOP -> { yMin = -4; yMax = 12; }
			default -> { yMin = -8; yMax = 8; }
		}
		List<BlockPos> candidates = new ArrayList<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				int x = anchor.getX() + dx, z = anchor.getZ() + dz;
				if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
				for (int dy = yMin; dy <= yMax; dy++) {
					m.set(x, anchor.getY() + dy, z);
					if (blacklist.contains(m.asLong())) continue;
					if (isWanted(level, m, level.getBlockState(m), false)) candidates.add(m.immutable());
				}
			}
		}
		Vec3 me = position();
		candidates.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(me)));
		int checked = 0;
		for (BlockPos p : candidates) {
			if (isWanted(level, p, level.getBlockState(p), true)) return p;
			if (mode == Mode.CHOP) {
				// The "is this a real tree?" test is slow, so remember logs that failed it
				// (they're part of a build) and only test the nearest few per scan.
				blacklist.add(p.asLong());
				if (++checked > 64) break;
			}
			// Buried ores/stone are skipped but not forgotten: mining a vein exposes them.
		}
		return null;
	}

	/** @param full also run the slower checks (leaves near a log, open air next to stone). */
	private boolean isWanted(ServerLevel level, BlockPos pos, BlockState s, boolean full) {
		if (s.isAir() || s.hasBlockEntity() || s.getDestroySpeed(level, pos) < 0) return false;
		return switch (mode) {
			case MINE_ORES -> isOre(s) && (!full || isExposed(level, pos));
			case MINE_STONE -> s.is(BlockTags.BASE_STONE_OVERWORLD)
					&& pos.getY() >= anchor.getY() && pos.getY() <= anchor.getY() + 4
					&& (!full || isExposed(level, pos));
			case CHOP -> s.is(BlockTags.LOGS) && (!full || isNaturalTree(level, pos));
			default -> false;
		};
	}

	private static boolean isOre(BlockState s) {
		return s.is(C_ORES)
				|| s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES)
				|| s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.GOLD_ORES)
				|| s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
				|| s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES)
				|| s.is(Blocks.NETHER_QUARTZ_ORE) || s.is(Blocks.ANCIENT_DEBRIS);
	}

	private static boolean isExposed(Level level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (level.getBlockState(pos.relative(d)).isAir()) return true;
		}
		return false;
	}

	/** A log counts as a tree only if natural (not player-placed) leaves are close by — protects builds. */
	private static boolean isNaturalTree(Level level, BlockPos log) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dy = 0; dy <= 14; dy++) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					m.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz);
					BlockState s = level.getBlockState(m);
					if (s.is(BlockTags.LEAVES)
							&& !(s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private void harvest(ServerLevel level, BlockPos pos, BlockState state) {
		if (mode == Mode.CHOP) {
			fellTree(level, pos);
		} else {
			breakAndKeep(level, pos, state);
		}
	}

	private void breakAndKeep(ServerLevel level, BlockPos pos, BlockState state) {
		ItemStack tool = getMainHandItem();
		BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
		List<ItemStack> drops = Block.getDrops(state, level, pos, be, this, tool);
		level.destroyBlock(pos, false, this);
		state.spawnAfterBreak(level, pos, tool, true); // ores still give XP
		for (ItemStack d : drops) keep(level, d);
	}

	/** Cuts the whole connected tree, not just one log, then replants if carrying a sapling. */
	private void fellTree(ServerLevel level, BlockPos start) {
		Set<BlockPos> logs = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start);
		logs.add(start);
		BlockPos lowest = start;
		while (!queue.isEmpty() && logs.size() < MAX_TREE_LOGS) {
			BlockPos cur = queue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos n = cur.offset(dx, dy, dz);
						if (logs.contains(n)) continue;
						if (Math.abs(n.getX() - start.getX()) > 10 || Math.abs(n.getZ() - start.getZ()) > 10) continue;
						if (!level.getBlockState(n).is(BlockTags.LOGS)) continue;
						// Trunks continue straight down even where the leaves are far above.
						boolean trunkBelow = dx == 0 && dz == 0 && dy == -1;
						if (!trunkBelow && !isNaturalTree(level, n)) continue;
						logs.add(n);
						queue.add(n);
						if (n.getY() < lowest.getY()) lowest = n;
					}
				}
			}
		}
		swing(InteractionHand.MAIN_HAND);
		for (BlockPos p : logs) breakAndKeep(level, p, level.getBlockState(p));
		replant(level, lowest);
	}

	private void replant(ServerLevel level, BlockPos base) {
		if (!level.getBlockState(base).isAir() || !level.getBlockState(base.below()).is(BlockTags.DIRT)) return;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			if (s.is(ItemTags.SAPLINGS) && s.getItem() instanceof BlockItem bi) {
				BlockState sapling = bi.getBlock().defaultBlockState();
				if (sapling.canSurvive(level, base)) {
					level.setBlockAndUpdate(base, sapling);
					s.shrink(1);
					inventory.setChanged();
					return;
				}
			}
		}
	}

	// ---------------------------------------------------------------- collect items

	/** @return true while it has an item to go after. */
	private boolean tickCollect(ServerLevel level, boolean standalone) {
		if (anchor == null) { setSimpleMode(Mode.IDLE); return false; }
		if (standalone && isInventoryFull()) { goDeposit(level); return true; }

		if (targetItem != null && ++itemTicks > 300) {
			itemBlacklist.add(targetItem.getUUID()); // couldn't reach it
			targetItem = null;
		}
		if (targetItem == null || !targetItem.isAlive()) {
			targetItem = null;
			if (tickCount % 10 == 0) {
				targetItem = findItem(level);
				itemTicks = 0;
			}
		}
		if (targetItem == null) {
			if (standalone && ++idleTicks > 100) finishTask(level, "All the dropped items around here are picked up.");
			return false;
		}
		idleTicks = 0;
		getLookControl().setLookAt(targetItem);
		if (distanceToSqr(targetItem) < 2.25) {
			pickUp(targetItem);
			targetItem = null;
		} else if (getNavigation().isDone() || tickCount % 20 == 0) {
			getNavigation().moveTo(targetItem, 1.0);
		}
		return true;
	}

	private ItemEntity findItem(ServerLevel level) {
		AABB area = new AABB(anchor).inflate(radius, 8, radius);
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, area,
				e -> e.isAlive() && !e.hasPickUpDelay() && !itemBlacklist.contains(e.getUUID())
						&& inventory.canAddItem(e.getItem()));
		ItemEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (ItemEntity e : items) {
			double d = distanceToSqr(e);
			if (d < bestD) { bestD = d; best = e; }
		}
		return best;
	}

	private void vacuumItems(ServerLevel level) {
		List<ItemEntity> near = level.getEntitiesOfClass(ItemEntity.class,
				getBoundingBox().inflate(1.5, 0.5, 1.5), e -> e.isAlive() && !e.hasPickUpDelay());
		for (ItemEntity e : near) pickUp(e);
	}

	private void pickUp(ItemEntity e) {
		ItemStack stack = e.getItem();
		int before = stack.getCount();
		ItemStack rest = inventory.addItem(stack.copy());
		int taken = before - rest.getCount();
		if (taken <= 0) return;
		take(e, taken);
		if (rest.isEmpty()) e.discard();
		else e.setItem(rest);
	}

	private void keep(ServerLevel level, ItemStack stack) {
		ItemStack rest = inventory.addItem(stack);
		if (!rest.isEmpty()) spawnAtLocation(level, rest); // full — it'll come back for it after unloading
	}

	// ---------------------------------------------------------------- deposit

	private void goDeposit(ServerLevel level) {
		dropTarget(level, false);
		targetItem = null;
		if (mode != Mode.DEPOSIT) resumeMode = mode;
		mode = Mode.DEPOSIT;
		chestTarget = null;
		failedDeposits = 0;
		getNavigation().stop();
	}

	private void tickDeposit(ServerLevel level) {
		if (inventory.isEmpty()) { finishDeposit(level); return; }
		List<BlockPos> list = StorageUtil.resolve(level, chests);
		if (chestTarget == null) {
			if (list.isEmpty()) {
				notifyOwner(level, chests.isEmpty()
						? "I don't have any storage chests yet! Look at a chest and type /helper chest add"
						: "My storage chests aren't loaded — come closer to them.");
				stopAll();
				return;
			}
			chestTarget = StorageUtil.pickDepositChest(level, list, inventory);
			walkTicks = 0;
			if (chestTarget == null) {
				notifyOwner(level, "All my storage chests are full! Add another with /helper chest add");
				stopAll();
				return;
			}
		}
		Walk w = walkTo(chestTarget);
		if (w == Walk.STUCK) {
			notifyOwner(level, "I can't reach the chest at " + chestTarget.toShortString() + ". Is there a path to it?");
			stopAll();
		} else if (w == Walk.ARRIVED) {
			int moved = StorageUtil.depositAt(level, list, chestTarget, inventory);
			level.playSound(null, chestTarget, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);
			swing(InteractionHand.MAIN_HAND);
			chestTarget = null;
			if (moved == 0 && ++failedDeposits > 2) {
				notifyOwner(level, "My storage chests are full! Add another with /helper chest add");
				stopAll();
			}
		}
	}

	private void finishDeposit(ServerLevel level) {
		Mode next = resumeMode;
		resumeMode = Mode.IDLE;
		chestTarget = null;
		if (next == Mode.IDLE || next == Mode.DEPOSIT) {
			mode = Mode.IDLE;
			notifyOwner(level, "Everything's put away.");
		} else {
			mode = next;
		}
	}

	// ---------------------------------------------------------------- sort

	private void tickSort(ServerLevel level) {
		if (chestTarget == null) {
			if (!inventory.isEmpty()) { goDeposit(level); return; } // comes back to sorting afterwards
			List<BlockPos> list = StorageUtil.resolve(level, chests);
			if (list.isEmpty()) {
				notifyOwner(level, "I don't have any storage chests to sort. Use /helper chest add");
				stopAll();
				return;
			}
			chestTarget = list.size() < 2 ? null : StorageUtil.findMisplacedSource(level, list);
			walkTicks = 0;
			if (chestTarget == null) {
				StorageUtil.compactAll(level, list);
				notifyOwner(level, "All sorted! Matching items are grouped together and stacked neatly.");
				stopAll();
				return;
			}
		}
		Walk w = walkTo(chestTarget);
		if (w == Walk.STUCK) {
			notifyOwner(level, "I can't reach the chest at " + chestTarget.toShortString() + ".");
			stopAll();
		} else if (w == Walk.ARRIVED) {
			List<BlockPos> list = StorageUtil.resolve(level, chests);
			int taken = StorageUtil.takeMisplaced(level, list, chestTarget, inventory);
			level.playSound(null, chestTarget, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);
			swing(InteractionHand.MAIN_HAND);
			chestTarget = null;
			if (taken == 0 && ++sortStalls > 2) {
				StorageUtil.compactAll(level, list);
				notifyOwner(level, "Sorted as much as I can — some chests are full.");
				stopAll();
			} else if (taken > 0) {
				sortStalls = 0;
			}
		}
	}

	// ---------------------------------------------------------------- shared helpers

	private Walk walkTo(BlockPos pos) {
		Vec3 c = Vec3.atCenterOf(pos);
		if (position().distanceToSqr(c) <= 9.0) {
			getNavigation().stop();
			getLookControl().setLookAt(c.x, c.y, c.z);
			return Walk.ARRIVED;
		}
		if (++walkTicks > 600) return Walk.STUCK;
		if (getNavigation().isDone() || walkTicks % 40 == 0) {
			getNavigation().moveTo(c.x, c.y, c.z, 1.0);
		}
		return Walk.WALKING;
	}

	private void finishTask(ServerLevel level, String message) {
		dropTarget(level, false);
		if (inventory.isEmpty()) {
			notifyOwner(level, "Done! " + message);
			setSimpleMode(Mode.IDLE);
		} else {
			notifyOwner(level, "Done! " + message + " Taking everything to storage.");
			clearWork();
			mode = Mode.DEPOSIT;
			resumeMode = Mode.IDLE;
		}
	}

	private void stopAll() {
		clearWork();
		mode = Mode.IDLE;
		resumeMode = Mode.IDLE;
	}

	private void clearWork() {
		if (target != null) level().destroyBlockProgress(getId(), target, -1);
		target = null;
		targetItem = null;
		chestTarget = null;
		breakTicks = breakNeeded = ticksOnTarget = repaths = 0;
		walkTicks = idleTicks = scanCooldown = itemTicks = failedDeposits = sortStalls = 0;
		getNavigation().stop();
	}

	private boolean isInventoryFull() {
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (inventory.getItem(i).isEmpty()) return false;
		}
		return true;
	}

	private ServerPlayer getOwnerPlayer(ServerLevel level) {
		if (ownerUuid == null) return null;
		return level.getServer().getPlayerList().getPlayer(ownerUuid);
	}

	public void notifyOwner(ServerLevel level, String msg) {
		ServerPlayer p = getOwnerPlayer(level);
		if (p == null) return;
		p.sendSystemMessage(Component.literal("[" + getName().getString() + "] ").withColor(NAME_COLOR)
				.append(Component.literal(msg).withColor(0xFFFFFF)));
	}

	// ======================================================================
	// Vanilla behaviour tweaks
	// ======================================================================

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (level().isClientSide()) return InteractionResult.SUCCESS;
		if (player.getUUID().equals(ownerUuid) || player.isCreative()) {
			player.openMenu(new SimpleMenuProvider(
					(syncId, playerInv, p) -> ChestMenu.threeRows(syncId, playerInv, inventory),
					getDisplayName()));
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		// Other players can't hurt your helper (creative players and the owner still can).
		if (source.getEntity() instanceof Player p && !p.getUUID().equals(ownerUuid) && !p.isCreative()) {
			return false;
		}
		return super.hurtServer(level, source, amount);
	}

	@Override
	public boolean causeFallDamage(double fallDistance, float damageMultiplier, DamageSource source) {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distanceSquared) {
		return false;
	}

	@Override
	public void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
		// No super call: its tools are virtual, so only drop what it was carrying.
		Containers.dropContents(level, this, inventory);
	}

	// ======================================================================
	// Saving
	// ======================================================================

	@Override
	public void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		if (ownerUuid != null) out.store("Owner", UUIDUtil.CODEC, ownerUuid);
		out.putString("HelperMode", mode.name());
		out.putString("ResumeMode", resumeMode.name());
		if (anchor != null) out.store("Anchor", BlockPos.CODEC, anchor);
		out.putInt("Radius", radius);
		out.store("Chests", BlockPos.CODEC.listOf(), new ArrayList<>(chests));
		List<ItemStack> carried = new ArrayList<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			if (!s.isEmpty()) carried.add(s.copy());
		}
		out.store("Carried", ItemStack.CODEC.listOf(), carried);
	}

	@Override
	public void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		ownerUuid = in.read("Owner", UUIDUtil.CODEC).orElse(null);
		mode = parseMode(in.getStringOr("HelperMode", "IDLE"));
		resumeMode = parseMode(in.getStringOr("ResumeMode", "IDLE"));
		anchor = in.read("Anchor", BlockPos.CODEC).orElse(null);
		radius = in.getIntOr("Radius", 16);
		chests.clear();
		in.read("Chests", BlockPos.CODEC.listOf()).ifPresent(chests::addAll);
		inventory.clearContent();
		in.read("Carried", ItemStack.CODEC.listOf()).ifPresent(list -> list.forEach(inventory::addItem));
	}

	private static Mode parseMode(String s) {
		try {
			return Mode.valueOf(s);
		} catch (IllegalArgumentException e) {
			return Mode.IDLE;
		}
	}
}
