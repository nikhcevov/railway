package dev.nikhcevov.railway.mixin;

import dev.nikhcevov.railway.minecraft.PlayerActions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.Explosion;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Railway service cart is not a world object players may capture: a hit must
 * never destroy it into a dropped minecart item or eject the passenger
 * mid-route. Vanilla {@code VehicleEntity#hurtServer} accumulates
 * {@code damage * 10} and destroys the vehicle once it passes 40; the destroy
 * path drops {@code Items.MINECART}.
 *
 * <p>Returning "not hurt" at HEAD also skips knockback and hit effects.
 * {@code ignoreExplosion} additionally removes the cart from the explosion
 * damage/push loop: only path damage can interrupt the trip, the passenger
 * still takes explosion damage normally. Only carts tagged
 * {@link PlayerActions#MANAGED_TAG} are affected; ordinary carts keep vanilla
 * damage and drops.
 */
@Mixin(VehicleEntity.class)
public abstract class VehicleEntityDamageMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void railway$ignoreManagedCartDamage(ServerLevel level, DamageSource source, float damage,
			CallbackInfoReturnable<Boolean> cir) {
		Entity entity = (Entity) (Object) this;
		if (entity.entityTags().contains(PlayerActions.MANAGED_TAG)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "ignoreExplosion", at = @At("HEAD"), cancellable = true)
	private void railway$ignoreManagedCartExplosion(Explosion explosion, CallbackInfoReturnable<Boolean> cir) {
		Entity entity = (Entity) (Object) this;
		if (entity.entityTags().contains(PlayerActions.MANAGED_TAG)) {
			cir.setReturnValue(true);
		}
	}
}
