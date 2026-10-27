package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tick-batched graph scan. Discovery follows physically adjacent rail blocks at
 * the same height or one block above/below; a three-port rail becomes a turnout
 * with every flat shape that can connect two of its available ports.
 *
 * <p>An unloaded candidate does not abort the scan. The scanner remembers the
 * candidate, keeps discovering loaded neighbours, and retries the affected
 * frontier position on later advances. The caller loads the exposed chunks (see
 * {@link #pendingPositions()}); when no chunk can be made readable within
 * {@link #DEFAULT_MAX_CHUNK_WAIT_TICKS} advances, the scan fails with
 * {@link FailureCode#CHUNKS_NOT_READY} instead of returning a partial graph.
 *
 * The final graph build is all-or-nothing. Curves and slopes are allowed;
 * detector/activator rails, four-way crossings and ambiguous heights fail.
 */
public final class GraphScanner {
	/**
	 * Consecutive advances that may end while waiting on the same unresolved
	 * unloaded set. {@code ScanService} drives one advance per server tick, so the
	 * default bounds a single stuck chunk to ten seconds.
	 */
	public static final int DEFAULT_MAX_CHUNK_WAIT_TICKS = 200;

	private record Scanned(RailShapeType shape, RailKind kind) {
	}

	private final String dimension;
	private final BlockLocation start;
	private final GraphRailView view;
	private final int maxRails;
	private final long revision;
	private final int maxChunkWaitTicks;
	private final ArrayDeque<BlockLocation> frontier = new ArrayDeque<>();
	private final Set<BlockLocation> seen = new LinkedHashSet<>();
	private final Map<BlockLocation, Scanned> rails = new LinkedHashMap<>();
	/** Candidate positions whose chunk is not readable yet. */
	private final Set<BlockLocation> pendingUnloaded = new LinkedHashSet<>();
	/** Frontier positions that met at least one unloaded candidate and need a retry. */
	private final Set<BlockLocation> blockedParents = new LinkedHashSet<>();

	private boolean initialized;
	private boolean finished;
	private int waitTicks;
	private Set<BlockLocation> lastPending = Set.of();
	private Failure failure;
	private RailGraph graph;

	public GraphScanner(String dimension, BlockLocation start, GraphRailView view, int maxRails, long revision) {
		this(dimension, start, view, maxRails, revision, DEFAULT_MAX_CHUNK_WAIT_TICKS);
	}

	public GraphScanner(String dimension, BlockLocation start, GraphRailView view, int maxRails, long revision,
			int maxChunkWaitTicks) {
		this.dimension = dimension;
		this.start = start;
		this.view = view;
		this.maxRails = maxRails;
		this.revision = revision;
		this.maxChunkWaitTicks = Math.max(0, maxChunkWaitTicks);
	}

	public boolean advance(int railBudget, long deadlineNanos) {
		if (finished) {
			return true;
		}
		if (!initialized) {
			initialized = true;
			switch (view.graphProbe(start)) {
				case GraphRailView.Probe.Unloaded ignored -> {
					pendingUnloaded.add(start);
					blockedParents.add(start);
				}
				case GraphRailView.Probe.Empty ignored -> {
					fail(Failure.at(FailureCode.NO_RAIL, start));
					return true;
				}
				case GraphRailView.Probe.Rail rail -> add(start, rail);
			}
			if (finished) {
				return true;
			}
		}

		int processed = 0;
		while (!frontier.isEmpty() && processed < railBudget) {
			if (processed > 0 && System.nanoTime() > deadlineNanos) {
				break;
			}
			BlockLocation pos = frontier.poll();
			probeNeighbors(pos);
			if (finished) {
				return true;
			}
			processed++;
		}
		if (frontier.isEmpty()) {
			if (!blockedParents.isEmpty() || !pendingUnloaded.isEmpty()) {
				return holdForChunks();
			}
			finalizeGraph();
		}
		return finished;
	}

	private void probeNeighbors(BlockLocation pos) {
		boolean blocked = false;
		for (RailPort port : RailPort.values()) {
			for (int dy : new int[]{-1, 0, 1}) {
				BlockLocation candidate = new BlockLocation(pos.x() + port.dx(), pos.y() + dy, pos.z() + port.dz());
				if (seen.contains(candidate)) {
					continue;
				}
				switch (view.graphProbe(candidate)) {
					case GraphRailView.Probe.Unloaded ignored -> {
						pendingUnloaded.add(candidate);
						blocked = true;
					}
					case GraphRailView.Probe.Empty ignored -> pendingUnloaded.remove(candidate);
					case GraphRailView.Probe.Rail rail -> {
						pendingUnloaded.remove(candidate);
						add(candidate, rail);
						if (finished) {
							return;
						}
					}
				}
			}
		}
		if (blocked) {
			blockedParents.add(pos);
		} else {
			blockedParents.remove(pos);
		}
	}

	private void add(BlockLocation pos, GraphRailView.Probe.Rail rail) {
		if (seen.add(pos)) {
			rails.put(pos, new Scanned(rail.shape(), rail.kind()));
			frontier.add(pos);
			if (rails.size() > maxRails) {
				fail(Failure.at(FailureCode.LIMIT_EXCEEDED, pos, Integer.toString(maxRails)));
			}
		}
	}

	/**
	 * Ends an advance while chunks are missing. Progress resets the wait counter,
	 * so loaded chunks of a long route do not count against another chunk's wait.
	 */
	private boolean holdForChunks() {
		if (!pendingUnloaded.equals(lastPending)) {
			waitTicks = 0;
			lastPending = Set.copyOf(pendingUnloaded);
		} else {
			waitTicks++;
		}
		if (waitTicks >= maxChunkWaitTicks) {
			BlockLocation first = pendingUnloaded.isEmpty() ? start : pendingUnloaded.iterator().next();
			fail(Failure.at(FailureCode.CHUNKS_NOT_READY, first,
					"scan waited " + waitTicks + " tick(s) for " + pendingUnloaded.size()
							+ " unloaded block(s)"));
			return true;
		}
		frontier.addAll(blockedParents);
		blockedParents.clear();
		return false;
	}

	private void finalizeGraph() {
		if (finished) {
			return;
		}
		try {
			List<GraphRail> graphRails = new ArrayList<>(rails.size());
			for (Map.Entry<BlockLocation, Scanned> entry : rails.entrySet()) {
				graphRails.add(toGraphRail(entry.getKey(), entry.getValue()));
			}
			graph = RailGraph.build(dimension, revision, graphRails);
			finished = true;
		} catch (GraphBuildException e) {
			failure = e.failure();
			finished = true;
		}
	}

	private GraphRail toGraphRail(BlockLocation pos, Scanned scanned) throws GraphBuildException {
		Map<RailPort, Integer> available = new EnumMap<>(RailPort.class);
		for (RailPort port : RailPort.values()) {
			Integer height = connectionHeight(pos, port);
			if (height != null) {
				available.put(port, height);
			}
		}
		if (available.size() > 3) {
			throw new GraphBuildException(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, pos,
					"four-way or ambiguous crossing"));
		}
		if (available.size() < 3) {
			return GraphRail.fixed(pos, scanned.kind(), scanned.shape());
		}
		List<RailShapeType> alternatives = new ArrayList<>();
		for (RailShapeType shape : RailShapeType.values()) {
			if (shape.isSlope()) {
				continue;
			}
			boolean first = available.containsKey(shape.ports().get(0))
					&& available.get(shape.ports().get(0)) == shape.portHeight(shape.ports().get(0));
			boolean second = available.containsKey(shape.ports().get(1))
					&& available.get(shape.ports().get(1)) == shape.portHeight(shape.ports().get(1));
			if (first && second) {
				alternatives.add(shape);
			}
		}
		if (!alternatives.contains(scanned.shape())) {
			throw new GraphBuildException(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, pos,
					"turnout current shape does not connect two branches"));
		}
		return GraphRail.turnout(pos, scanned.kind(), scanned.shape(), alternatives);
	}

	/** Returns the required port height, or null when no rail connects there. */
	private Integer connectionHeight(BlockLocation pos, RailPort port) throws GraphBuildException {
		Integer result = null;
		for (int dy : new int[]{-1, 0, 1}) {
			BlockLocation candidate = new BlockLocation(pos.x() + port.dx(), pos.y() + dy, pos.z() + port.dz());
			Scanned neighbor = rails.get(candidate);
			if (neighbor == null) {
				continue;
			}
			RailPort back = port.opposite();
			if (!neighbor.shape().hasPort(back)) {
				continue;
			}
			int height = candidate.y() + neighbor.shape().portHeight(back) - pos.y();
			if (height != 0 && height != 1) {
				continue;
			}
			if (result != null) {
				throw new GraphBuildException(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, pos,
						"ambiguous adjacency at " + port));
			}
			result = height;
		}
		return result;
	}

	private void fail(Failure value) {
		failure = value;
		finished = true;
	}

	/** Aborts a running scan from outside, for example when its ticket budget is exhausted. */
	public void abort(Failure value) {
		if (!finished) {
			fail(value);
		}
	}

	public boolean isFinished() {
		return finished;
	}

	public boolean isFailed() {
		return failure != null;
	}

	/** True while at least one candidate chunk is not readable. */
	public boolean isWaiting() {
		return !finished && !pendingUnloaded.isEmpty();
	}

	/** Unloaded candidate positions the caller must make readable to continue. */
	public Set<BlockLocation> pendingPositions() {
		return Collections.unmodifiableSet(pendingUnloaded);
	}

	public Failure failure() {
		return failure;
	}

	public RailGraph graph() {
		if (!finished || graph == null) {
			throw new IllegalStateException("scan not complete");
		}
		return graph;
	}

	public int railCount() {
		return rails.size();
	}
}
