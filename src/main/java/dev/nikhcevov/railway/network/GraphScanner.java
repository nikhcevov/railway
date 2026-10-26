package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * The final graph build is all-or-nothing. Curves and slopes are allowed;
 * detector/activator rails, four-way crossings and ambiguous heights fail.
 */
public final class GraphScanner {
	private record Scanned(RailShapeType shape, RailKind kind) {
	}

	private final String dimension;
	private final BlockLocation start;
	private final GraphRailView view;
	private final int maxRails;
	private final long revision;
	private final ArrayDeque<BlockLocation> frontier = new ArrayDeque<>();
	private final Set<BlockLocation> seen = new LinkedHashSet<>();
	private final Map<BlockLocation, Scanned> rails = new LinkedHashMap<>();

	private boolean initialized;
	private boolean finished;
	private Failure failure;
	private RailGraph graph;

	public GraphScanner(String dimension, BlockLocation start, GraphRailView view, int maxRails, long revision) {
		this.dimension = dimension;
		this.start = start;
		this.view = view;
		this.maxRails = maxRails;
		this.revision = revision;
	}

	public boolean advance(int railBudget, long deadlineNanos) {
		if (finished) {
			return true;
		}
		if (!initialized) {
			initialized = true;
			if (!probeFirst() || finished) {
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
			finalizeGraph();
		}
		return finished;
	}

	private boolean probeFirst() {
		return switch (view.graphProbe(start)) {
			case GraphRailView.Probe.Unloaded ignored -> {
				fail(Failure.at(FailureCode.SCAN_INCOMPLETE, start));
				yield false;
			}
			case GraphRailView.Probe.Empty ignored -> {
				fail(Failure.at(FailureCode.NO_RAIL, start));
				yield false;
			}
			case GraphRailView.Probe.Rail rail -> {
				add(start, rail);
				yield true;
			}
		};
	}

	private void probeNeighbors(BlockLocation pos) {
		for (RailPort port : RailPort.values()) {
			for (int dy : new int[]{-1, 0, 1}) {
				BlockLocation candidate = new BlockLocation(pos.x() + port.dx(), pos.y() + dy, pos.z() + port.dz());
				if (seen.contains(candidate)) {
					continue;
				}
				switch (view.graphProbe(candidate)) {
					case GraphRailView.Probe.Unloaded ignored -> {
						fail(Failure.at(FailureCode.SCAN_INCOMPLETE, candidate));
						return;
					}
					case GraphRailView.Probe.Empty ignored -> {
					}
					case GraphRailView.Probe.Rail rail -> add(candidate, rail);
				}
			}
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

	public boolean isFinished() {
		return finished;
	}

	public boolean isFailed() {
		return failure != null;
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
