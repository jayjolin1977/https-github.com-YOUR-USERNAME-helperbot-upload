package com.helperbot;

import com.helperbot.HelperEntity.Mode;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class HelperCommands {
	private static final int MAX_HELPERS_PER_PLAYER = 3;
	private static final int DEFAULT_RADIUS = 16;
	private static final int YELLOW = 0xFFFF55, GOLD = 0xFFAA00, GRAY = 0xAAAAAA, AQUA = 0x55FFFF;

	private HelperCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(literal("helper")
				.executes(HelperCommands::help)
				.then(literal("help").executes(HelperCommands::help))
				.then(literal("summon").executes(HelperCommands::summon))
				.then(literal("dismiss").executes(HelperCommands::dismiss))
				.then(literal("follow").executes(c -> simple(c, Mode.FOLLOW, "Following you.")))
				.then(literal("stay").executes(c -> simple(c, Mode.IDLE, "Staying here.")))
				.then(literal("stop").executes(c -> simple(c, Mode.IDLE, "Stopped.")))
				.then(literal("mine")
						.then(withRadius(literal("ores"), Mode.MINE_ORES))
						.then(withRadius(literal("stone"), Mode.MINE_STONE)))
				.then(withRadius(literal("chop"), Mode.CHOP))
				.then(withRadius(literal("collect"), Mode.COLLECT))
				.then(literal("store").executes(HelperCommands::store))
				.then(literal("sort").executes(HelperCommands::sort))
				.then(literal("status").executes(HelperCommands::status))
				.then(literal("chest")
						.then(literal("add")
								.executes(c -> chestAdd(c, null))
								.then(argument("pos", BlockPosArgument.blockPos())
										.executes(c -> chestAdd(c, BlockPosArgument.getLoadedBlockPos(c, "pos")))))
						.then(literal("remove")
								.executes(c -> chestRemove(c, null))
								.then(argument("pos", BlockPosArgument.blockPos())
										.executes(c -> chestRemove(c, BlockPosArgument.getLoadedBlockPos(c, "pos")))))
						.then(literal("list").executes(HelperCommands::chestList))
						.then(literal("clear").executes(HelperCommands::chestClear))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> withRadius(
			LiteralArgumentBuilder<CommandSourceStack> node, Mode mode) {
		return node
				.executes(c -> work(c, mode, DEFAULT_RADIUS))
				.then(argument("radius", IntegerArgumentType.integer(4, 32))
						.executes(c -> work(c, mode, IntegerArgumentType.getInteger(c, "radius"))));
	}

	// ---------------------------------------------------------------- helpers

	private static List<HelperEntity> helpersOf(ServerPlayer player) {
		ServerLevel level = player.level();
		return new ArrayList<>(level.getEntities(ModEntities.HELPER,
				h -> player.getUUID().equals(h.getOwnerUuid())));
	}

	private static List<HelperEntity> requireHelpers(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		List<HelperEntity> list = helpersOf(c.getSource().getPlayerOrException());
		if (list.isEmpty()) {
			c.getSource().sendFailure(Component.literal("You don't have a helper nearby. Use /helper summon"));
		}
		return list;
	}

	private static void say(CommandContext<CommandSourceStack> c, Component msg) {
		c.getSource().sendSuccess(() -> msg, false);
	}

	private static void say(CommandContext<CommandSourceStack> c, String msg) {
		say(c, Component.literal(msg));
	}

	private static void sayCmd(CommandContext<CommandSourceStack> c, String cmd, String what) {
		say(c, Component.literal(cmd).withColor(YELLOW).append(Component.literal(" – " + what).withColor(0xFFFFFF)));
	}

	// ---------------------------------------------------------------- commands

	private static int help(CommandContext<CommandSourceStack> c) {
		say(c, Component.literal("Helper Bot commands").withColor(GOLD));
		sayCmd(c, "/helper summon", "get a helper (up to " + MAX_HELPERS_PER_PLAYER + ")");
		sayCmd(c, "/helper chest add", "look at a chest/barrel to make it storage");
		sayCmd(c, "/helper mine ores [radius]", "mine exposed ores around you");
		sayCmd(c, "/helper mine stone [radius]", "clear exposed stone around you");
		sayCmd(c, "/helper chop [radius]", "fell trees around you and replant");
		sayCmd(c, "/helper collect [radius]", "pick up dropped items around you");
		sayCmd(c, "/helper store", "put everything it carries into storage");
		sayCmd(c, "/helper sort", "organise your storage chests");
		sayCmd(c, "/helper follow | stay | stop | status | dismiss", "control it");
		say(c, Component.literal("Right-click your helper to see what it's carrying.").withColor(GRAY));
		return 1;
	}

	private static int summon(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		List<HelperEntity> existing = helpersOf(player);
		if (existing.size() >= MAX_HELPERS_PER_PLAYER) {
			c.getSource().sendFailure(Component.literal("You already have " + existing.size() + " helpers here."));
			return 0;
		}
		ServerLevel level = player.level();
		HelperEntity h = ModEntities.HELPER.create(level, EntitySpawnReason.COMMAND);
		if (h == null) return 0;
		h.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0f);
		h.setOwner(player.getUUID());
		h.setCustomName(Component.literal(player.getName().getString() + "'s Helper").withColor(AQUA));
		h.setCustomNameVisible(true);
		h.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		if (!existing.isEmpty()) {
			for (BlockPos p : existing.get(0).getChests()) h.addChest(p); // share storage
		}
		level.addFreshEntity(h);
		h.setSimpleMode(Mode.FOLLOW);
		say(c, "Your helper is here and following you. Next: look at a chest and type /helper chest add");
		return 1;
	}

	private static int dismiss(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		List<HelperEntity> list = requireHelpers(c);
		for (HelperEntity h : list) {
			for (int i = 0; i < h.getInventory().getContainerSize(); i++) {
				ItemStack s = h.getInventory().removeItemNoUpdate(i);
				if (!s.isEmpty() && !player.getInventory().add(s)) player.drop(s, false);
			}
			h.discard();
		}
		if (!list.isEmpty()) say(c, "Helper dismissed. Anything it was carrying is now in your inventory.");
		return list.size();
	}

	private static int simple(CommandContext<CommandSourceStack> c, Mode mode, String msg) throws CommandSyntaxException {
		List<HelperEntity> list = requireHelpers(c);
		for (HelperEntity h : list) h.setSimpleMode(mode);
		if (!list.isEmpty()) say(c, msg);
		return list.size();
	}

	private static int work(CommandContext<CommandSourceStack> c, Mode mode, int radius) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		List<HelperEntity> list = requireHelpers(c);
		if (list.isEmpty()) return 0;
		BlockPos here = player.blockPosition();
		for (HelperEntity h : list) h.startTask(mode, here, radius);
		String what = switch (mode) {
			case MINE_ORES -> "Mining exposed ores";
			case MINE_STONE -> "Clearing exposed stone (from your feet up 4 blocks)";
			case CHOP -> "Chopping trees";
			case COLLECT -> "Picking up dropped items";
			default -> "Working";
		};
		say(c, what + " within " + radius + " blocks of here.");
		if (list.get(0).getChests().isEmpty()) {
			say(c, Component.literal("Tip: it has no storage chest yet, so it'll stop when its pockets are full. Use /helper chest add").withColor(YELLOW));
		}
		return list.size();
	}

	private static int store(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		List<HelperEntity> list = requireHelpers(c);
		for (HelperEntity h : list) h.startDeposit();
		if (!list.isEmpty()) say(c, "Taking everything to storage.");
		return list.size();
	}

	private static int sort(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		List<HelperEntity> list = requireHelpers(c);
		if (list.isEmpty()) return 0;
		// One sorter is enough; extra helpers would just get in each other's way.
		list.get(0).startTask(Mode.SORT, player.blockPosition(), DEFAULT_RADIUS);
		say(c, "Sorting your storage chests — each item type will be gathered into one chest.");
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		List<HelperEntity> list = requireHelpers(c);
		for (HelperEntity h : list) {
			say(c, Component.literal(h.getName().getString()).withColor(AQUA).append(Component.literal(
					": " + h.getMode().name().toLowerCase().replace('_', ' ')
							+ ", carrying " + h.carriedCount() + " items, " + h.getChests().size()
							+ " storage chest(s), at " + h.blockPosition().toShortString()
							+ ", health " + Math.round(h.getHealth()) + "/" + Math.round(h.getMaxHealth()))
					.withColor(0xFFFFFF)));
		}
		return list.size();
	}

	private static BlockPos lookedAtBlock(ServerPlayer player) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0f).scale(6.0));
		BlockHitResult hit = player.level().clip(
				new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
	}

	private static int chestAdd(CommandContext<CommandSourceStack> c, BlockPos given) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		BlockPos pos = given != null ? given : lookedAtBlock(player);
		if (pos == null) {
			c.getSource().sendFailure(Component.literal("Look at a chest or barrel (or type its coordinates)."));
			return 0;
		}
		if (!StorageUtil.isStorageBlock(player.level().getBlockState(pos))) {
			c.getSource().sendFailure(Component.literal("That's not a chest, barrel or shulker box."));
			return 0;
		}
		List<HelperEntity> list = requireHelpers(c);
		if (list.isEmpty()) return 0;
		boolean added = false;
		for (HelperEntity h : list) added |= h.addChest(pos);
		say(c, added ? "Added storage chest at " + pos.toShortString() + " (" + list.get(0).getChests().size() + " total)."
				: "That chest is already on the list.");
		return 1;
	}

	private static int chestRemove(CommandContext<CommandSourceStack> c, BlockPos given) throws CommandSyntaxException {
		ServerPlayer player = c.getSource().getPlayerOrException();
		BlockPos pos = given != null ? given : lookedAtBlock(player);
		if (pos == null) {
			c.getSource().sendFailure(Component.literal("Look at the chest to remove (or type its coordinates)."));
			return 0;
		}
		List<HelperEntity> list = requireHelpers(c);
		boolean removed = false;
		for (HelperEntity h : list) removed |= h.removeChest(pos);
		if (!list.isEmpty()) say(c, removed ? "Removed that storage chest." : "That chest wasn't on the list.");
		return removed ? 1 : 0;
	}

	private static int chestList(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		List<HelperEntity> list = requireHelpers(c);
		if (list.isEmpty()) return 0;
		List<BlockPos> chests = list.get(0).getChests();
		if (chests.isEmpty()) {
			say(c, "No storage chests yet. Look at one and type /helper chest add");
		} else {
			say(c, "Storage chests:");
			for (BlockPos p : chests) say(c, " - " + p.toShortString());
		}
		return chests.size();
	}

	private static int chestClear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		List<HelperEntity> list = requireHelpers(c);
		for (HelperEntity h : list) h.getChests().clear();
		if (!list.isEmpty()) say(c, "Cleared all storage chests.");
		return list.size();
	}
}
