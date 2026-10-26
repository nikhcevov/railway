package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.RailAxis;

/**
 * A rail probe result. {@code straight} is false for curved shapes and
 * {@code slope} is true for ascending shapes.
 */
public record RailInfo(RailAxis axis, RailKind kind, boolean straight, boolean slope) {
}
