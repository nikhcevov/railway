package dev.nikhcevov.railway.mixin;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartBehavior;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the cart field so powered-rail suppression can inspect Railway tags. */
@Mixin(MinecartBehavior.class)
public interface MinecartBehaviorAccessor {
	@Accessor("minecart")
	AbstractMinecart railway$minecart();
}
