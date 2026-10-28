package dev.nikhcevov.railway.mixin;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rail placement/removal changes station topology. Invalidate the affected
 * scanned component before sign registration can consult an old graph.
 *
 * <p>Only touch graphs adjacent to the changed rail, not every graph in the
 * level: GameTests place rails in separate plots on one shared level.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
			at = @At("HEAD"))
	private void railway$invalidateRailTopology(BlockPos pos, BlockState state, int flags,
			CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof ServerLevel level) || !Railway.isStarted()) {
			return;
		}
		BlockState previous = level.getBlockState(pos);
		boolean wasRail = BaseRailBlock.isRail(previous);
		boolean isRail = BaseRailBlock.isRail(state);
		if (!wasRail && !isRail) {
			return;
		}
		// Powered-rail updates and other same-block state changes do not alter the
		// topology. A rail shape update caused by a neighbouring placement is
		// covered by the new rail's own invalidation.
		if (wasRail && isRail && previous.getBlock() == state.getBlock()) {
			return;
		}
		Railway railway = Railway.get();
		BlockLocation location = RailWorld.loc(pos);
		if (wasRail) {
			railway.scans().invalidate(location);
		}
		if (isRail) {
			railway.scans().invalidateAdjacent(level, location);
		}
	}
}
