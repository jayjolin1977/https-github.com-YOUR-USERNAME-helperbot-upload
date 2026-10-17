package com.helperbot;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final ResourceKey<EntityType<?>> HELPER_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(HelperBotMod.MOD_ID, "helper"));

	public static final EntityType<HelperEntity> HELPER = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			HELPER_KEY,
			EntityType.Builder.of(HelperEntity::new, MobCategory.MISC)
					.sized(0.6f, 1.8f)
					.clientTrackingRange(10)
					.build(HELPER_KEY));

	private ModEntities() {}

	public static void init() {
		FabricDefaultAttributeRegistry.register(HELPER, HelperEntity.createAttributes());
	}
}
