package dev.nikhcevov.railway.mixin;

import dev.nikhcevov.railway.minecraft.PlayerActions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Powered rails are ordinary track for a Railway-managed service cart.
 * Vanilla would add a boost on powered rails and slow the cart on unpowered
 * powered rails; both fight Railway's speed plan and can overshoot the dock.
 * Normal carts are untouched.
 */
@Mixin(NewMinecartBehavior.class)
public abstract class NewMinecartBehaviorMixin {
	@Inject(method = "calculateBoostTrackSpeed", at = @At("HEAD"), cancellable = true)
	private void railway$suppressPoweredBoost(Vec3 deltaMovement, BlockPos pos, BlockState state,
			CallbackInfoReturnable<Vec3> cir) {
		AbstractMinecart cart = ((MinecartBehaviorAccessor) (Object) this).railway$minecart();
		if (cart.entityTags().contains(PlayerActions.MANAGED_TAG)) {
			cir.setReturnValue(deltaMovement);
		}
	}

	@Inject(method = "calculateHaltTrackSpeed", at = @At("HEAD"), cancellable = true)
	private void railway$suppressPoweredHalt(Vec3 deltaMovement, BlockState state,
			CallbackInfoReturnable<Vec3> cir) {
		AbstractMinecart cart = ((MinecartBehaviorAccessor) (Object) this).railway$minecart();
		if (cart.entityTags().contains(PlayerActions.MANAGED_TAG)) {
			cir.setReturnValue(deltaMovement);
		}
	}
}
