package com.helperbot.client;

import com.helperbot.HelperBotMod;
import com.helperbot.HelperEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/** Draws the helper as a player-shaped figure holding its current tool. */
public class HelperRenderer extends HumanoidMobRenderer<HelperEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
	private static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(HelperBotMod.MOD_ID, "textures/entity/helper.png");

	public HelperRenderer(EntityRendererProvider.Context ctx) {
		super(ctx, new HumanoidModel<>(ctx.bakeLayer(HelperBotClient.HELPER_LAYER)), 0.5f);
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public Identifier getTextureLocation(HumanoidRenderState state) {
		return TEXTURE;
	}
}
