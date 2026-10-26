package dev.nikhcevov.railway.mixin;

import dev.nikhcevov.railway.minecraft.PlayerActions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Raises the vanilla ridden-cart speed clamp only for Railway service carts.
 * Vanilla clamps ridden carts to 0.75 * getMaxSpeed; 0.8 yields a 0.6 b/t
 * ceiling, i.e. 12 blocks/s at 20 TPS.
 */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartSpeedMixin {
	private static final double RAILWAY_MAX_SPEED = 0.8;

	@Inject(method = "getMaxSpeed", at = @At("HEAD"), cancellable = true)
	private void railway$managedMaxSpeed(ServerLevel level, CallbackInfoReturnable<Double> cir) {
		AbstractMinecart cart = (AbstractMinecart) (Object) this;
		if (cart.entityTags().contains(PlayerActions.MANAGED_TAG)) {
			cir.setReturnValue(RAILWAY_MAX_SPEED);
		}
	}
}
