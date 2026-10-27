package dev.nikhcevov.railway.minecraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.function.Consumer;

/**
 * Read-only level-storage probe. A missing entry means the chunk was never
 * saved; the probe never touches the chunk generator and never loads the chunk.
 */
final class LevelStorageProbe {
	private LevelStorageProbe() {
	}

	static void exists(ServerLevel level, ChunkPos pos, Consumer<Boolean> callback) {
		level.getChunkSource().chunkMap.read(pos).whenComplete((data, error) ->
				callback.accept(error == null && data != null && data.isPresent()));
	}
}
