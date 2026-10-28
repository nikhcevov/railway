package dev.nikhcevov.railway.commands;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import dev.nikhcevov.railway.domain.Failure;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bilingual server-side message catalog. The client only receives literal text,
 * so no resource pack or client mod is required.
 */
public final class Messages {
	public enum Lang {
		EN,
		RU
	}

	private static final Gson GSON = new Gson();
	private static final Map<String, String> EN = load("en_us");
	private static final Map<String, String> RU = load("ru_ru");
	private static final Pattern ENUM_DETAIL = Pattern.compile("[A-Z][A-Z0-9_]+");
	private static final Map<String, String> DETAIL_KEYS = Map.ofEntries(
			Map.entry("SIGN_SUPPORT_NOT_SOLID", "error.detail.sign_support_not_solid"),
			Map.entry("SIGN_NOT_ATTACHED", "error.detail.sign_not_attached"),
			Map.entry("SIGN_HEIGHT_OUT_OF_RANGE", "error.detail.sign_height_out_of_range"),
			Map.entry("MISSING_BUTTON", "error.detail.missing_button"),
			Map.entry("BUTTON_NOT_STONE", "error.detail.button_not_stone"),
			Map.entry("BUTTON_NOT_ON_PILLAR", "error.detail.button_not_on_pillar"),
			Map.entry("BUTTON_SUPPORT_NOT_SOLID", "error.detail.button_support_not_solid"),
			Map.entry("BUTTON_HEIGHT_OUT_OF_RANGE", "error.detail.button_height_out_of_range"),
			Map.entry("DOCK_NOT_ALIGNED", "error.detail.dock_not_aligned"),
			Map.entry("PILLAR_DISTANCE_OUT_OF_RANGE", "error.detail.pillar_distance_out_of_range"),
			Map.entry("NO_RAIL", "error.detail.sign_dock_no_rail"),
			Map.entry("MULTIPLE_RAILS", "error.detail.sign_dock_multiple_rails"),
			Map.entry("RAIL_NOT_STRAIGHT", "error.detail.sign_dock_rail_not_straight"),
			Map.entry("AMBIGUOUS_BOTH_SIDES", "error.detail.sign_dock_ambiguous_both_sides"),
			Map.entry("LINE_COUNT", "error.detail.sign_line_count"),
			Map.entry("MISSING_MARKER", "error.detail.sign_missing_marker"),
			Map.entry("UNSUPPORTED_MARKER", "error.detail.sign_unsupported_marker"),
			Map.entry("INVALID_MARKER", "error.detail.sign_invalid_marker"),
			Map.entry("MISSING_FROM", "error.detail.sign_missing_from"),
			Map.entry("INVALID_FLAGS", "error.detail.sign_invalid_flags"),
			Map.entry("wall sign required", "error.detail.wall_sign_required"),
			Map.entry("exactly one wall sign per pillar", "error.detail.one_wall_sign"),
			Map.entry("a station-only anchor must not have a button", "error.detail.station_only_no_button"),
			Map.entry("missing stone button", "error.detail.missing_stone_button"),
			Map.entry("exactly one stone button per pillar", "error.detail.one_stone_button"),
			Map.entry("local dock is not on the station rail", "error.detail.dock_not_on_station_rail"),
			Map.entry("blank sign", "error.detail.blank_sign"),
			Map.entry("missing source name", "error.detail.missing_source_name"),
			Map.entry("blank destination", "error.detail.blank_destination")
	);

	private Messages() {
	}

	public static Lang lang(ServerPlayer player) {
		String code = player.clientInformation().language();
		return code != null && code.toLowerCase(Locale.ROOT).startsWith("ru") ? Lang.RU : Lang.EN;
	}

	public static Lang lang(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player == null ? Lang.EN : lang(player);
	}

	public static Component text(CommandSourceStack source, String key, Object... args) {
		return Component.literal(format(lang(source), key, args));
	}

	public static Component text(ServerPlayer player, String key, Object... args) {
		return Component.literal(format(lang(player), key, args));
	}

	public static String format(Lang lang, String key, Object... args) {
		Map<String, String> catalog = lang == Lang.RU ? RU : EN;
		String template = catalog.get(key);
		if (template == null) {
			template = EN.getOrDefault(key, key);
		}
		try {
			return String.format(Locale.ROOT, template, args);
		} catch (RuntimeException e) {
			return template + " " + java.util.Arrays.toString(args);
		}
	}

	public static Component failure(CommandSourceStack source, Failure failure) {
		return Component.literal(failureText(lang(source), failure));
	}

	public static Component failure(ServerPlayer player, Failure failure) {
		return Component.literal(failureText(lang(player), failure));
	}

	public static String failureText(Lang lang, Failure failure) {
		return failureText(lang, failure, false);
	}

	/** Diagnostics/admin rendering keeps the stable code for support reports. */
	public static String failureTextWithCode(Lang lang, Failure failure) {
		return failureText(lang, failure, true);
	}

	private static String failureText(Lang lang, Failure failure, boolean includeCode) {
		String key = "error." + failure.code().name().toLowerCase(Locale.ROOT);
		String base = format(lang, key, readableDetail(lang, failure.detail())).trim();
		StringBuilder text = new StringBuilder(base);
		if (failure.location() != null) {
			text.append(" (").append(failure.location().shortString()).append(')');
		}
		if (includeCode) {
			text.append(" [").append(failure.code().name()).append(']');
		}
		return text.toString();
	}

	/** Converts internal enum details into a localized phrase; free text is preserved. */
	private static String readableDetail(Lang lang, String detail) {
		if (detail == null || detail.isBlank()) {
			return "";
		}
		String key = DETAIL_KEYS.get(detail);
		if (key != null) {
			return format(lang, key);
		}
		if (ENUM_DETAIL.matcher(detail).matches()) {
			return detail.toLowerCase(Locale.ROOT).replace('_', ' ');
		}
		return detail;
	}

	public static void actionBar(ServerPlayer player, String key, Object... args) {
		player.sendOverlayMessage(text(player, key, args));
	}

	private static Map<String, String> load(String code) {
		String path = "/assets/railway/lang/" + code + ".json";
		try (InputStream stream = Messages.class.getResourceAsStream(path)) {
			if (stream == null) {
				return Map.of();
			}
			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				Map<String, String> parsed = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {
				}.getType());
				return parsed == null ? Map.of() : Map.copyOf(parsed);
			}
		} catch (IOException e) {
			return Map.of();
		}
	}
}
