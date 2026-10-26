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
		String key = "error." + failure.code().name().toLowerCase(Locale.ROOT);
		String base = format(lang, key, failure.detail() == null ? "" : failure.detail()).trim();
		StringBuilder text = new StringBuilder(base);
		if (failure.location() != null) {
			text.append(" (").append(failure.location().shortString()).append(')');
		}
		text.append(" [").append(failure.code().name()).append(']');
		return text.toString();
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
