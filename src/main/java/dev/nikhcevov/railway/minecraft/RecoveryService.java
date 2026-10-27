package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.dispatch.ChunkWindow;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.persistence.TripRecord;
import dev.nikhcevov.railway.recovery.RecoveryPolicy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Startup reconciliation of persisted trip checkpoints. Recovery never resumes a
 * trip: it locates the recorded service cart, safely stops or removes it, and
 * releases the checkpoint. Players are never teleported to an unconfirmed exit.
 *
 * <p>At most one existing checkpoint chunk is made readable through a bounded,
 * non-persistent recovery ticket; recovery never scans chunks and never generates
 * terrain. An unresolved checkpoint blocks new passenger trips only until it is
 * reconciled or its bounded deadline expires, so the server can always recover.
 *
 * <p>All access is on the server thread. The only asynchronous step is the level
 * storage probe, whose result is scheduled back to the server thread.
 */
public final class RecoveryService {
	/** How long one checkpoint may stay unresolved before it is cleaned up. */
	public static final long DEADLINE_TICKS = 1_200L;
	/** Retry cadence; also refreshes the recovery ticket timeout. */
	public static final long RETRY_INTERVAL_TICKS = 20L;
	/** Recovery never needs more than a few chunks: one per pending checkpoint. */
	public static final int MAX_RECOVERY_TICKETS = 4;

	/** Read-only level storage probe: does the chunk exist on disk? */
	@FunctionalInterface
	public interface ChunkStorage {
		void exists(ServerLevel level, ChunkPos pos, Consumer<Boolean> callback);
	}

	public record Stats(
			int pending,
			int blocking,
			int tickets,
			long ticketAcquisitions,
			long ticketReleases,
			long checkpointsRemoved,
			long cartsDiscarded,
			long cartsStopped,
			long cartsPreserved,
			long chunkProbes,
			long lateChunkPasses,
			long deadlines
	) {
	}

	private static final class Pending {
		final TripRecord record;
		final long startedTick;
		boolean blocking = true;
		/** {@code null} until the storage probe answers. */
		Boolean chunkOnDisk;
		boolean probeInFlight;
		boolean ticketHeld;
		boolean stopReported;
		long lastAttemptTick = Long.MIN_VALUE;

		Pending(TripRecord record, long startedTick) {
			this.record = record;
			this.startedTick = startedTick;
		}
	}

	private record ChunkPresence(RecoveryPolicy.Chunk state, ChunkWindow.Key key, boolean probePending) {
	}

	private final Railway railway;
	private final Logger logger;
	private final ChunkTicketManager tickets;
	private final ChunkStorage chunkStorage;
	private final Map<TripId, Pending> pending = new LinkedHashMap<>();
	private boolean closed;
	private long checkpointsRemoved;
	private long cartsDiscarded;
	private long cartsStopped;
	private long cartsPreserved;
	private long chunkProbes;
	private long lateChunkPasses;
	private long deadlines;

	public RecoveryService(Railway railway, Logger logger, ChunkTicketManager tickets) {
		this(railway, logger, tickets, RecoveryService::probeStorage);
	}

	RecoveryService(Railway railway, Logger logger, ChunkTicketManager tickets, ChunkStorage chunkStorage) {
		this.railway = railway;
		this.logger = logger;
		this.tickets = tickets;
		this.chunkStorage = chunkStorage;
	}

	// ----------------------------------------------------------- entry points

	/** Starts reconciliation for every loaded checkpoint. Called once at startup. */
	public void begin() {
		reconcileAll();
		if (!pending.isEmpty()) {
			logger.warn("Recovering {} interrupted trip checkpoint(s); trips never resume automatically",
					pending.size());
		}
	}

	/**
	 * Idempotent reconciliation pass: re-reads the checkpoints still in state and
	 * evaluates them. Safe to call repeatedly.
	 */
	public void reconcileAll() {
		closed = false;
		MinecraftServer server = railway.server();
		long now = server.getTickCount();
		for (TripRecord record : railway.state().trips()) {
			if (pending.containsKey(record.id()) || ownsActiveTrip(record)) {
				continue;
			}
			pending.put(record.id(), new Pending(record, now));
		}
		for (Pending entry : List.copyOf(pending.values())) {
			evaluate(server, entry, now);
		}
	}

	public void tick(MinecraftServer server) {
		if (closed || pending.isEmpty()) {
			return;
		}
		long now = server.getTickCount();
		for (Pending entry : List.copyOf(pending.values())) {
			if (pending.get(entry.record.id()) != entry) {
				continue;
			}
			if (now - entry.startedTick > DEADLINE_TICKS) {
				deadlines++;
				logger.warn("Recovery of trip {} is still unresolved after {} ticks; dropping the"
						+ " checkpoint, late entities are handled by the orphan sweep",
						entry.record.id(), now - entry.startedTick);
				drop(entry, "deadline");
				continue;
			}
			if (entry.lastAttemptTick != Long.MIN_VALUE
					&& now - entry.lastAttemptTick < RETRY_INTERVAL_TICKS) {
				continue;
			}
			evaluate(server, entry, now);
		}
	}

	/**
	 * Late chunk load: reconcile a checkpoint whose recorded chunk just became
	 * entity-ticking and remove any orphan managed cart that appeared with it.
	 */
	public void onChunkReady(ServerLevel level, LevelChunk chunk) {
		if (closed) {
			return;
		}
		lateChunkPasses++;
		ChunkPos pos = chunk.getPos();
		if (!level.areEntitiesActuallyLoadedAndTicking(pos)) {
			return;
		}
		MinecraftServer server = level.getServer();
		long now = server.getTickCount();
		String dimension = RailWorld.dimension(level);
		for (Pending entry : List.copyOf(pending.values())) {
			if (pending.get(entry.record.id()) != entry) {
				continue;
			}
			TripRecord record = entry.record;
			if (record.position() == null || !dimension.equals(record.dimension())) {
				continue;
			}
			if (!ChunkWindow.Key.of(record.position()).equals(new ChunkWindow.Key(pos.x(), pos.z()))) {
				continue;
			}
			evaluate(server, entry, now);
		}
		removeOrphanCarts(level, pos);
	}

	/**
	 * On join, a managed cart that is not part of a live trip is a recovery
	 * leftover. Stop it in place and keep the passenger riding: no teleport and no
	 * forced ejection. The checkpoint stays until the cart is empty or expires.
	 */
	public void onPlayerJoin(ServerPlayer player) {
		if (closed || !(player.getVehicle() instanceof Minecart cart)) {
			return;
		}
		if (!isManaged(cart) || railway.trips().managesCart(cart.getUUID())) {
			return;
		}
		cart.setDeltaMovement(Vec3.ZERO);
		logger.info("Stopped recovered service cart {} carrying {}", cart.getUUID(), player.getUUID());
	}

	/** Releases recovery tickets; checkpoints stay for the next start. */
	public void shutdown() {
		closed = true;
		tickets.releaseAllRecovery();
		pending.clear();
	}

	// ---------------------------------------------------------------- queries

	/** Whether any unresolved checkpoint still blocks new passenger trips. */
	public boolean blocksNewTrips() {
		for (Pending entry : pending.values()) {
			if (entry.blocking) {
				return true;
			}
		}
		return false;
	}

	public int pendingCount() {
		return pending.size();
	}

	/** Whether a valid checkpoint still expects this cart UUID to be reconciled. */
	public boolean isPendingCart(UUID cartUuid) {
		for (Pending entry : pending.values()) {
			if (entry.record.cartUuid().equals(cartUuid)) {
				return true;
			}
		}
		return false;
	}

	public Stats stats() {
		int blocking = 0;
		for (Pending entry : pending.values()) {
			if (entry.blocking) {
				blocking++;
			}
		}
		ChunkTicketManager.RecoveryStats ticketStats = tickets.recoveryStats();
		return new Stats(pending.size(), blocking, ticketStats.tickets(), ticketStats.acquisitions(),
				ticketStats.releases(), checkpointsRemoved, cartsDiscarded, cartsStopped, cartsPreserved,
				chunkProbes, lateChunkPasses, deadlines);
	}

	// ----------------------------------------------------------- reconciliation

	private void evaluate(MinecraftServer server, Pending entry, long now) {
		entry.lastAttemptTick = now;
		TripRecord record = entry.record;
		ServerLevel level = record.dimension() == null ? null : RailWorld.level(server, record.dimension());
		Entity entity = findEntity(server, level, record.cartUuid());
		if (entity != null) {
			if (entity instanceof Minecart cart) {
				reconcileCart(entry, cart);
			} else {
				cartsPreserved++;
				logger.warn("Checkpoint {} cart UUID belongs to a non-minecart entity; leaving it and"
						+ " dropping the checkpoint", record.id());
				drop(entry, "uuid is not a minecart");
			}
			return;
		}

		ChunkPresence presence = presence(entry, level);
		if (presence.probePending()) {
			entry.blocking = true;
			return;
		}
		RecoveryPolicy.Action action = RecoveryPolicy.decide(RecoveryPolicy.Cart.ABSENT,
				presence.state(), now - entry.startedTick > DEADLINE_TICKS);
		entry.blocking = RecoveryPolicy.blocksNewTrips(action);
		if (action == RecoveryPolicy.Action.REQUEST_TICKET) {
			requestChunk(entry, level, presence.key());
		} else if (action == RecoveryPolicy.Action.DROP_CHECKPOINT) {
			drop(entry, "stale checkpoint; cart is not present");
		}
	}

	private void reconcileCart(Pending entry, Minecart cart) {
		if (!isManaged(cart)) {
			cartsPreserved++;
			logger.warn("Checkpoint {} maps to an unmanaged cart {}; leaving the cart and dropping"
					+ " the checkpoint", entry.record.id(), cart.getUUID());
			drop(entry, "cart is not managed by Railway");
			return;
		}
		RecoveryPolicy.Action action = RecoveryPolicy.decide(
				cart.isVehicle() ? RecoveryPolicy.Cart.OCCUPIED : RecoveryPolicy.Cart.EMPTY,
				RecoveryPolicy.Chunk.LOADED, false);
		entry.blocking = RecoveryPolicy.blocksNewTrips(action);
		if (action == RecoveryPolicy.Action.STOP_CART) {
			cart.setDeltaMovement(Vec3.ZERO);
			releaseTicket(entry);
			if (!entry.stopReported) {
				entry.stopReported = true;
				cartsStopped++;
				logger.info("Recovered service cart {} still carries a passenger; leaving it stopped",
						cart.getUUID());
			}
			return;
		}
		// An empty managed cart is discarded only after the checkpoint was found,
		// so a user cart can never be selected by this path.
		if (PlayerActions.discardCart(cart)) {
			cartsDiscarded++;
			drop(entry, "cart removed");
		} else {
			logger.warn("Recovered service cart {} could not be removed yet", cart.getUUID());
		}
	}

	private ChunkPresence presence(Pending entry, ServerLevel level) {
		TripRecord record = entry.record;
		if (record.position() == null) {
			return new ChunkPresence(RecoveryPolicy.Chunk.UNKNOWN, null, false);
		}
		ChunkWindow.Key key = ChunkWindow.Key.of(record.position());
		if (level == null) {
			// The dimension is not loaded: wait for it or for the deadline.
			return new ChunkPresence(RecoveryPolicy.Chunk.UNLOADED, key, false);
		}
		ChunkPos pos = new ChunkPos(key.x(), key.z());
		boolean loaded = level.getChunkSource().hasChunk(key.x(), key.z());
		if (loaded && level.areEntitiesActuallyLoadedAndTicking(pos)) {
			return new ChunkPresence(RecoveryPolicy.Chunk.LOADED, key, false);
		}
		if (Boolean.FALSE.equals(entry.chunkOnDisk)) {
			return new ChunkPresence(RecoveryPolicy.Chunk.MISSING, key, false);
		}
		if (Boolean.TRUE.equals(entry.chunkOnDisk)) {
			return new ChunkPresence(RecoveryPolicy.Chunk.UNLOADED, key, false);
		}
		probeStorage(entry, level, key, pos);
		return new ChunkPresence(RecoveryPolicy.Chunk.UNLOADED, key, true);
	}

	private void requestChunk(Pending entry, ServerLevel level, ChunkWindow.Key key) {
		if (level == null || key == null) {
			return;
		}
		int budget = Math.min(railway.config().maxManagedChunkTickets(), MAX_RECOVERY_TICKETS);
		if (budget <= 0) {
			// Tickets are disabled by config: wait for a natural late load.
			entry.ticketHeld = false;
			entry.blocking = RecoveryPolicy.blocksNewTrips(RecoveryPolicy.Action.WAIT_FOR_CHUNK);
			return;
		}
		entry.ticketHeld = tickets.acquireRecovery(level, entry.record.id(), key, budget);
	}

	private void probeStorage(Pending entry, ServerLevel level, ChunkWindow.Key key, ChunkPos pos) {
		if (entry.probeInFlight) {
			return;
		}
		entry.probeInFlight = true;
		chunkProbes++;
		chunkStorage.exists(level, pos, exists -> {
			MinecraftServer server = railway.server();
			try {
				server.execute(() -> {
					entry.probeInFlight = false;
					entry.chunkOnDisk = exists;
					if (closed || pending.get(entry.record.id()) != entry) {
						return;
					}
					evaluate(server, entry, server.getTickCount());
				});
			} catch (RuntimeException e) {
				entry.probeInFlight = false;
				logger.warn("Cannot schedule the recovery chunk probe result: {}", e.getMessage());
			}
		});
	}

	private void drop(Pending entry, String reason) {
		if (pending.remove(entry.record.id()) == null) {
			return;
		}
		releaseTicket(entry);
		checkpointsRemoved++;
		if (!ownsActiveTrip(entry.record)) {
			railway.state().removeTrip(entry.record.id());
			railway.saveState();
		}
		logger.info("Recovery checkpoint {} closed: {}", entry.record.id(), reason);
	}

	private void releaseTicket(Pending entry) {
		if (!entry.ticketHeld) {
			return;
		}
		entry.ticketHeld = false;
		tickets.releaseRecovery(entry.record.id());
	}

	private void removeOrphanCarts(ServerLevel level, ChunkPos pos) {
		AABB box = new AABB(pos.getMinBlockX(), level.getMinY(), pos.getMinBlockZ(),
				pos.getMaxBlockX() + 1.0, level.getMaxY() + 1.0, pos.getMaxBlockZ() + 1.0);
		EntityTypeTest<Entity, Minecart> minecartType = EntityTypeTest.forClass(Minecart.class);
		List<Minecart> carts = level.getEntities(minecartType, box, cart ->
				cart.entityTags().contains(PlayerActions.MANAGED_TAG)
						&& !railway.trips().managesCart(cart.getUUID())
						&& !isPendingCart(cart.getUUID()));
		for (Minecart cart : carts) {
			if (cart.isVehicle()) {
				cart.setDeltaMovement(Vec3.ZERO);
				continue;
			}
			if (PlayerActions.discardCart(cart)) {
				cartsDiscarded++;
			}
		}
	}

	private boolean ownsActiveTrip(TripRecord record) {
		return railway.trips().isActive(record.id()) || railway.trips().managesCart(record.cartUuid());
	}

	private static Entity findEntity(MinecraftServer server, ServerLevel preferred, UUID uuid) {
		if (preferred != null) {
			Entity entity = preferred.getEntity(uuid);
			if (entity != null) {
				return entity;
			}
		}
		for (ServerLevel level : server.getAllLevels()) {
			if (level == preferred) {
				continue;
			}
			Entity entity = level.getEntity(uuid);
			if (entity != null) {
				return entity;
			}
		}
		return null;
	}

	private static boolean isManaged(Minecart cart) {
		return cart.entityTags().contains(PlayerActions.MANAGED_TAG);
	}

	/**
	 * Checks level storage without touching the chunk generator. A missing entry
	 * means the chunk was never saved, so a saved cart cannot be hidden there.
	 */
	private static void probeStorage(ServerLevel level, ChunkPos pos, Consumer<Boolean> callback) {
		LevelStorageProbe.exists(level, pos, callback);
	}
}
