package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * One rail block and the shapes it may take. A fixed rail has exactly one
 * alternative; a turnout lists every shape the controller may select.
 */
public record GraphRail(
		BlockLocation pos,
		RailKind kind,
		RailShapeType shape,
		List<RailShapeType> alternatives
) {
	public GraphRail {
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(shape, "shape");
		alternatives = List.copyOf(new LinkedHashSet<>(alternatives));
		if (alternatives.isEmpty()) {
			throw new IllegalArgumentException("rail has no shape alternatives");
		}
		if (!alternatives.contains(shape)) {
			throw new IllegalArgumentException("current shape is not among alternatives");
		}
	}

	public static GraphRail fixed(BlockLocation pos, RailKind kind, RailShapeType shape) {
		return new GraphRail(pos, kind, shape, List.of(shape));
	}

	public static GraphRail turnout(BlockLocation pos, RailKind kind, RailShapeType current,
			List<RailShapeType> alternatives) {
		return new GraphRail(pos, kind, current, alternatives);
	}

	public boolean isSwitch() {
		return alternatives.size() > 1;
	}
}
