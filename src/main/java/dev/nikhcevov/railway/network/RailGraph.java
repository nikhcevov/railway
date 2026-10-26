package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Directed rail topology. States include the entry side, so a switch can offer
 * different exits from the same rail. Edges are immutable; the current world
 * shape is only used to seed {@link GraphRail#shape()}.
 */
public final class RailGraph {
	public record Edge(
			RailState to,
			double length,
			RailShapeType sourceShape,
			SwitchRequirement requirement
	) {
		public Edge {
			Objects.requireNonNull(to, "to");
			Objects.requireNonNull(sourceShape, "sourceShape");
		}
	}

	private static final Comparator<BlockLocation> POSITION_ORDER =
			Comparator.comparingInt(BlockLocation::x)
					.thenComparingInt(BlockLocation::y)
					.thenComparingInt(BlockLocation::z);

	private static final Comparator<RailState> STATE_ORDER =
			Comparator.comparing(RailState::pos, POSITION_ORDER)
					.thenComparingInt(state -> state.entryPort().ordinal());

	private final String dimension;
	private final long revision;
	private final Map<BlockLocation, GraphRail> rails;
	private final Map<RailState, List<Edge>> edges;
	private final String componentKey;

	private RailGraph(String dimension, long revision, Map<BlockLocation, GraphRail> rails,
			Map<RailState, List<Edge>> edges, String componentKey) {
		this.dimension = dimension;
		this.revision = revision;
		this.rails = Map.copyOf(rails);
		this.edges = Map.copyOf(edges);
		this.componentKey = componentKey;
	}

	public static RailGraph build(String dimension, long revision, Collection<GraphRail> input)
			throws GraphBuildException {
		if (input.isEmpty()) {
			throw new GraphBuildException(Failure.detail(FailureCode.INTERNAL, "empty graph"));
		}
		Map<BlockLocation, GraphRail> rails = new TreeMap<>(POSITION_ORDER);
		for (GraphRail rail : input) {
			if (rails.putIfAbsent(rail.pos(), rail) != null) {
				throw new GraphBuildException(Failure.at(FailureCode.INTERNAL, rail.pos(), "duplicate rail"));
			}
		}
		for (GraphRail rail : rails.values()) {
			validateRail(rail);
		}

		Map<RailState, List<Edge>> edges = new LinkedHashMap<>();
		for (GraphRail rail : rails.values()) {
			for (RailShapeType alternative : rail.alternatives()) {
				SwitchRequirement requirement = rail.isSwitch()
						? new SwitchRequirement(rail.pos(), alternative)
						: null;
				for (RailPort entry : alternative.ports()) {
					RailPort exit = alternative.other(entry);
					List<RailState> targets = resolveTargets(rails, rail, alternative, exit);
					RailState from = new RailState(rail.pos(), entry);
					List<Edge> list = edges.computeIfAbsent(from, ignored -> new ArrayList<>());
					for (RailState target : targets) {
						list.add(new Edge(target, 1.0, alternative, requirement));
					}
				}
			}
		}
		for (List<Edge> list : edges.values()) {
			list.sort(Comparator.comparing(Edge::to, STATE_ORDER)
					.thenComparingInt(edge -> edge.sourceShape().ordinal()));
		}
		Map<BlockLocation, Set<BlockLocation>> connections = new HashMap<>();
		for (Map.Entry<RailState, List<Edge>> entry : edges.entrySet()) {
			Set<BlockLocation> targets = connections.computeIfAbsent(entry.getKey().pos(),
					ignored -> new LinkedHashSet<>());
			for (Edge edge : entry.getValue()) {
				targets.add(edge.to().pos());
			}
			entry.setValue(List.copyOf(entry.getValue()));
		}
		for (Map.Entry<BlockLocation, Set<BlockLocation>> entry : connections.entrySet()) {
			if (entry.getValue().size() > 3) {
				throw new GraphBuildException(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, entry.getKey(),
						"four-way or ambiguous crossing"));
			}
		}

		String key = componentKey(dimension, rails);
		return new RailGraph(dimension, revision, rails, edges, key);
	}

	private static void validateRail(GraphRail rail) throws GraphBuildException {
		if (rail.kind() == RailKind.DETECTOR || rail.kind() == RailKind.ACTIVATOR) {
			throw new GraphBuildException(Failure.at(FailureCode.UNSUPPORTED_RAIL, rail.pos(),
					rail.kind().name().toLowerCase(java.util.Locale.ROOT)));
		}
		if (rail.isSwitch()) {
			if (rail.kind() == RailKind.POWERED) {
				throw new GraphBuildException(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, rail.pos(),
						"powered rail cannot be a turnout"));
			}
			if (rail.alternatives().size() > 3) {
				throw new GraphBuildException(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, rail.pos(),
						"more than three turnout states"));
			}
			for (RailShapeType alternative : rail.alternatives()) {
				if (alternative.isSlope()) {
					throw new GraphBuildException(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, rail.pos(),
							"turnout on slope"));
				}
			}
		}
	}

	private static List<RailState> resolveTargets(Map<BlockLocation, GraphRail> rails, GraphRail rail,
			RailShapeType shape, RailPort exit) throws GraphBuildException {
		int connectionHeight = rail.pos().y() + shape.portHeight(exit);
		List<RailState> targets = new ArrayList<>();
		Set<BlockLocation> candidatePositions = new LinkedHashSet<>();
		candidatePositions.add(exit.adjacent(rail.pos(), connectionHeight));
		candidatePositions.add(exit.adjacent(rail.pos(), connectionHeight - 1));
		RailPort backPort = exit.opposite();
		Set<BlockLocation> validConnections = new LinkedHashSet<>();

		for (BlockLocation candidate : candidatePositions) {
			GraphRail neighbor = rails.get(candidate);
			if (neighbor == null) {
				continue;
			}
			for (RailShapeType neighborShape : neighbor.alternatives()) {
				if (!neighborShape.hasPort(backPort)) {
					continue;
				}
				if (candidate.y() + neighborShape.portHeight(backPort) == connectionHeight) {
					validConnections.add(candidate);
					targets.add(new RailState(candidate, backPort));
					break;
				}
			}
		}
		if (validConnections.size() > 1) {
			throw new GraphBuildException(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, rail.pos(),
					"ambiguous adjacency at " + exit));
		}
		return List.copyOf(targets);
	}

	private static String componentKey(String dimension, Map<BlockLocation, GraphRail> rails) {
		BlockLocation first = rails.keySet().iterator().next();
		BlockLocation last = null;
		int hash = 1;
		for (BlockLocation pos : rails.keySet()) {
			last = pos;
			hash = 31 * hash + pos.hashCode();
		}
		return dimension + '|' + rails.size() + '|' + first.x() + ',' + first.y() + ',' + first.z()
				+ '|' + last.x() + ',' + last.y() + ',' + last.z() + '|' + Integer.toHexString(hash);
	}

	public String dimension() {
		return dimension;
	}

	public long revision() {
		return revision;
	}

	public String componentKey() {
		return componentKey;
	}

	public GraphRail rail(BlockLocation pos) {
		return rails.get(pos);
	}

	public Map<BlockLocation, GraphRail> rails() {
		return rails;
	}

	public List<Edge> neighbors(RailState state) {
		return edges.getOrDefault(state, List.of());
	}

	public Set<BlockLocation> adjacentRails(BlockLocation pos) {
		Set<BlockLocation> adjacent = new LinkedHashSet<>();
		for (RailPort port : RailPort.values()) {
			RailState state = new RailState(pos, port);
			for (Edge edge : neighbors(state)) {
				adjacent.add(edge.to().pos());
			}
		}
		return adjacent;
	}

	public Set<BlockLocation> switches() {
		Set<BlockLocation> switches = new LinkedHashSet<>();
		for (GraphRail rail : rails.values()) {
			if (rail.isSwitch()) {
				switches.add(rail.pos());
			}
		}
		return switches;
	}

}
