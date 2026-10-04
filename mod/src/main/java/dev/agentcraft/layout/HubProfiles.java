package dev.agentcraft.layout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which Foreman profile (project) each hub runs, and which port each profile owns on this machine.
 * Both live in the AgentCraft home ({@code $AGENTCRAFT_HOME} or {@code ~/.agentcraft}):
 * <ul>
 *   <li>{@code config.json} "hubs.profiles": hub id -> profile (default: the hub id; the main hub has
 *       no default, so it is not checked unless mapped);</li>
 *   <li>{@code ports.json}: profile -> port, shared by every world on the machine, so two projects
 *       never get the same port (hub ports used to be 7900 + slot, which collide across worlds).</li>
 * </ul>
 */
public final class HubProfiles {
	public static final String PORTS_FILE = "ports.json";
	/** Ports handed to hub Foremens (the main hub keeps the client's AGENTCRAFT_PORT, default 7878). */
	public static final int FIRST_PORT = 7901;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private HubProfiles() {
	}

	public static Path home() {
		String env = System.getenv("AGENTCRAFT_HOME");
		return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".agentcraft");
	}

	/** The profile hub {@code hubId} should reach, or null when it is not known (an unmapped main hub). */
	public static @Nullable String profileFor(String hubId) {
		String mapped = mapping().get(hubId);
		if (mapped != null) {
			return mapped;
		}
		return HubRegistry.MAIN.equals(hubId) ? null : hubId;
	}

	/** config.json "hubs.profiles" (empty when absent or unreadable). */
	public static Map<String, String> mapping() {
		Map<String, String> m = new LinkedHashMap<>();
		JsonObject hubs = readObject(home().resolve("config.json"), "hubs");
		if (hubs != null && hubs.has("profiles") && hubs.get("profiles").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : hubs.getAsJsonObject("profiles").entrySet()) {
				if (e.getValue().isJsonPrimitive()) {
					m.put(e.getKey(), e.getValue().getAsString());
				}
			}
		}
		return m;
	}

	/**
	 * The port {@code profile} owns on this machine: its recorded port, or the first free one from
	 * {@link #FIRST_PORT} that no other profile owns, nothing listens on and {@code avoid} does not
	 * contain (then recorded). Server thread (file I/O; it is quick).
	 */
	public static synchronized int portFor(String profile, Set<Integer> avoid) {
		Map<String, Integer> ports = ports();
		Integer mine = ports.get(profile);
		if (mine != null) {
			return mine;
		}
		Set<Integer> owned = new HashSet<>(ports.values());
		int port = FIRST_PORT;
		while (owned.contains(port) || avoid.contains(port) || port == 7879 || listening(port)) {
			port++;
		}
		ports.put(profile, port);
		savePorts(ports);
		AgentCraft.LOGGER.info("Foreman profile '{}' owns port {} on this machine", profile, port);
		return port;
	}

	/** Records a profile's port (e.g. a hub created before ports.json existed), unless it is taken by another profile. */
	public static synchronized void claim(String profile, int port) {
		Map<String, Integer> ports = ports();
		if (ports.containsKey(profile) || ports.containsValue(port)) {
			return;
		}
		ports.put(profile, port);
		savePorts(ports);
	}

	/** profile -> port from ports.json. */
	public static Map<String, Integer> ports() {
		Map<String, Integer> m = new LinkedHashMap<>();
		Path f = home().resolve(PORTS_FILE);
		if (!Files.isRegularFile(f)) {
			return m;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				m.put(e.getKey(), e.getValue().getAsInt());
			}
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("Could not read {}", f, e);
		}
		return m;
	}

	private static void savePorts(Map<String, Integer> ports) {
		Path f = home().resolve(PORTS_FILE);
		JsonObject o = new JsonObject();
		ports.forEach(o::addProperty);
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(PORTS_FILE + ".tmp");
			Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", f, e);
		}
	}

	private static boolean listening(int port) {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress("127.0.0.1", port), 150);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static @Nullable JsonObject readObject(Path file, String key) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : null;
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("Could not read {}", file, e);
			return null;
		}
	}
}
