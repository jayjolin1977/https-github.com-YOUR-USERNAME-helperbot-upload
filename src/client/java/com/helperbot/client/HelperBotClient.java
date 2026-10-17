package com.helperbot.client;

import com.helperbot.HelperBotMod;
import com.helperbot.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.resources.Identifier;

public class HelperBotClient implements ClientModInitializer {
	public static final ModelLayerLocation HELPER_LAYER = new ModelLayerLocation(
			Identifier.fromNamespaceAndPath(HelperBotMod.MOD_ID, "helper"), "main");

	@Override
	@SuppressWarnings("deprecation")
	public void onInitializeClient() {
		// A plain player-shaped body with a 64x64 skin texture.
		ModelLayerRegistry.registerModelLayer(HELPER_LAYER,
				() -> LayerDefinition.create(HumanoidModel.createMesh(CubeDeformation.NONE, 0.0f), 64, 64));
		EntityRendererRegistry.register(ModEntities.HELPER, HelperRenderer::new);
	}
}
