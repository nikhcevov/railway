package dev.nikhcevov.railway.mixin;

import dev.nikhcevov.railway.minecraft.Railway;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Routines every server-side sign save through the registration service. The previous
 * text is captured before vanilla applies the edit so a rejected edit of a registered
 * sign can be reverted.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {
	@Unique
	private static final ThreadLocal<SignText> RAILWAY_PREVIOUS_TEXT = new ThreadLocal<>();

	@Shadow
	public abstract SignText getText(boolean front);

	@Inject(method = "updateSignText(Lnet/minecraft/world/entity/player/Player;ZLjava/util/List;)V",
			at = @At("HEAD"))
	private void railway$capturePreviousText(Player player, boolean front, List<FilteredText> messages,
			CallbackInfo ci) {
		if (!(player instanceof ServerPlayer) || !Railway.isStarted()) {
			RAILWAY_PREVIOUS_TEXT.remove();
			return;
		}
		RAILWAY_PREVIOUS_TEXT.set(getText(front));
	}

	@Inject(method = "updateSignText(Lnet/minecraft/world/entity/player/Player;ZLjava/util/List;)V",
			at = @At("RETURN"))
	private void railway$registerAfterSave(Player player, boolean front, List<FilteredText> messages,
			CallbackInfo ci) {
		SignText previous = RAILWAY_PREVIOUS_TEXT.get();
		RAILWAY_PREVIOUS_TEXT.remove();
		if (previous == null || !(player instanceof ServerPlayer serverPlayer) || !Railway.isStarted()) {
			return;
		}
		Railway railway = Railway.get();
		if (railway.disabled()) {
			return;
		}
		SignBlockEntity sign = (SignBlockEntity) (Object) this;
		if (!railway.isBuilder(serverPlayer)) {
			railway.signs().rejectUnauthorized(serverPlayer, sign, front, previous);
			return;
		}
		railway.signs().register(serverPlayer, sign, front, previous);
	}
}
