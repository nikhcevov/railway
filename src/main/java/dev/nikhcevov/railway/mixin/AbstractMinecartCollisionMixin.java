package dev.nikhcevov.railway.mixin;

import dev.nikhcevov.railway.minecraft.PlayerActions;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/**
 * Railway service carts are not part of the entity collision world. Living
 * entities select push targets through {@code EntitySelector.pushableBy}, which
 * first checks {@code isPushable()}; returning false removes the service cart
 * from that loop, so neither a standing player/mob nor a moving vanilla cart can
 * shove it, exchange momentum with it, or derail its trip. The service cart also
 * stops pushing entities.
 *
 * <p>Block collisions are untouched: the planned path still stops the cart.
 * Ordinary carts keep vanilla pushability.
 */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartCollisionMixin {
	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private void railway$serviceCartIgnoresEntityPush(CallbackInfoReturnable<Boolean> cir) {
		AbstractMinecart cart = (AbstractMinecart) (Object) this;
		if (cart.entityTags().contains(PlayerActions.MANAGED_TAG)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void railway$serviceCartIgnoresEntityCollision(Entity other, CallbackInfo ci) {
		AbstractMinecart cart = (AbstractMinecart) (Object) this;
		if (cart.entityTags().contains(PlayerActions.MANAGED_TAG)
				|| (other != null && other.entityTags().contains(PlayerActions.MANAGED_TAG))) {
			ci.cancel();
		}
	}
}
