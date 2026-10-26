package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.StationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Dijkstra over {@link RailState}. Cost is one block per traversed rail; ties
 * are broken by stable coordinate/port order. Turnout shapes are accumulated
 * from the chosen edges and conflicting repeat visits are rejected.
 */
public final class RailGraphPlanner {
	private static final Comparator<BlockLocation> POSITION_ORDER =
			Comparator.comparingInt(BlockLocation::x)
					.thenComparingInt(BlockLocation::y)
					.thenComparingInt(BlockLocation::z);

	private record Node(RailState state, double cost) {
	}

	private record Previous(RailState state, RailGraph.Edge edge) {
	}

	public sealed interface Result {
		record Planned(GraphRoutePlan plan) implements Result {
		}

		record Rejected(Failure failure) implements Result {
		}
	}

	private RailGraphPlanner() {
	}

	public static Result plan(RailGraph graph, StationId fromStation, StationId toStation,
			BlockLocation fromDock, BlockLocation toDock, int maxRouteLength) {
		GraphRail from = graph.rail(fromDock);
		if (from == null) {
			return new Result.Rejected(Failure.at(FailureCode.DOCK_NOT_ON_LINE, fromDock));
		}
		GraphRail to = graph.rail(toDock);
		if (to == null) {
			return new Result.Rejected(Failure.at(FailureCode.DOCK_NOT_ON_LINE, toDock));
		}
		if (fromDock.equals(toDock)) {
			return new Result.Rejected(Failure.at(FailureCode.TOO_SHORT, toDock));
		}
		if (from.isSwitch() || !from.shape().isStraightHorizontal()) {
			return new Result.Rejected(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, fromDock, "departure dock"));
		}
		if (to.isSwitch() || !to.shape().isStraightHorizontal()) {
			return new Result.Rejected(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, toDock, "arrival dock"));
		}

		Comparator<Node> order = Comparator.comparingDouble(Node::cost)
				.thenComparing(node -> node.state().pos(), POSITION_ORDER)
				.thenComparingInt(node -> node.state().entryPort().ordinal());
		PriorityQueue<Node> queue = new PriorityQueue<>(order);
		Map<RailState, Double> distances = new HashMap<>();
		Map<RailState, Previous> previous = new HashMap<>();
		Set<RailState> settled = new HashSet<>();

		for (RailPort port : from.shape().ports()) {
			RailState seed = new RailState(fromDock, port);
			distances.put(seed, 0.0);
			queue.add(new Node(seed, 0.0));
		}

		RailState goal = null;
		while (!queue.isEmpty()) {
			Node node = queue.poll();
			if (!settled.add(node.state())) {
				continue;
			}
			if (node.state().pos().equals(toDock)) {
				goal = node.state();
				break;
			}
			for (RailGraph.Edge edge : graph.neighbors(node.state())) {
				RailState next = edge.to();
				double cost = node.cost() + edge.length();
				Double known = distances.get(next);
				if (known == null || cost < known) {
					distances.put(next, cost);
					previous.put(next, new Previous(node.state(), edge));
					queue.add(new Node(next, cost));
				}
			}
		}

		if (goal == null) {
			return new Result.Rejected(Failure.at(FailureCode.NO_PATH, fromDock));
		}

		List<RailState> states = new ArrayList<>();
		List<RailGraph.Edge> pathEdges = new ArrayList<>();
		RailState cursor = goal;
		while (true) {
			states.add(cursor);
			Previous prev = previous.get(cursor);
			if (prev == null) {
				break;
			}
			pathEdges.add(prev.edge());
			cursor = prev.state();
		}
		Collections.reverse(states);
		Collections.reverse(pathEdges);

		List<RouteStep> steps = new ArrayList<>();
		for (int i = 0; i < pathEdges.size(); i++) {
			RailState state = states.get(i);
			RailShapeType shape = pathEdges.get(i).sourceShape();
			steps.add(new RouteStep(state.pos(), shape, state.entryPort(), shape.other(state.entryPort())));
		}
		RailState last = states.getLast();
		steps.add(new RouteStep(last.pos(), to.shape(), last.entryPort(), to.shape().other(last.entryPort())));

		if (steps.size() < 3
				|| !steps.get(steps.size() - 2).shape().isStraightHorizontal()
				|| !steps.get(steps.size() - 3).shape().isStraightHorizontal()) {
			return new Result.Rejected(Failure.at(FailureCode.TOO_SHORT, toDock,
					Integer.toString(Math.max(0, steps.size() - 2))));
		}
		if (pathEdges.size() > maxRouteLength) {
			return new Result.Rejected(Failure.at(FailureCode.LIMIT_EXCEEDED, toDock,
					Integer.toString(maxRouteLength)));
		}

		Map<BlockLocation, RailShapeType> required = new LinkedHashMap<>();
		for (RailGraph.Edge edge : pathEdges) {
			SwitchRequirement requirement = edge.requirement();
			if (requirement == null) {
				continue;
			}
			RailShapeType existing = required.putIfAbsent(requirement.pos(), requirement.requiredShape());
			if (existing != null && existing != requirement.requiredShape()) {
				return new Result.Rejected(Failure.at(FailureCode.NO_PATH, requirement.pos(),
						"conflicting turnout requirements"));
			}
		}
		List<SwitchRequirement> switches = new ArrayList<>();
		required.forEach((pos, shape) -> switches.add(new SwitchRequirement(pos, shape)));

		GraphRoutePlan plan = new GraphRoutePlan(
				fromStation,
				toStation,
				fromDock,
				toDock,
				graph.dimension(),
				steps,
				switches,
				pathEdges.size(),
				graph.componentKey()
		);
		return new Result.Planned(plan);
	}
}
