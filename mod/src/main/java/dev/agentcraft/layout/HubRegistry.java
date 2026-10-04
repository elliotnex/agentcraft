package dev.agentcraft.layout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.world.HqWorld;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The hubs of an HQ world ({@code agentcraft-hubs.json} in the world folder). A hub is one studio
 * bound to one project: its own Foreman (profile = hub id, own port) and its own team. Hub
 * {@code main} is always there, in slot 0 where the original studio stands; every further hub gets
 * the next slot, {@link #SPACING} blocks further east, so studios never overlap.
 *
 * <p>Read from any thread ({@link #all()} is an immutable snapshot); changed on the server thread.
 * The client (same JVM in singleplayer) reads it directly, like {@link Anchors}.
 */
public final class HubRegistry {
	public static final String FILE = "agentcraft-hubs.json";
	public static final String MAIN = "main";
	/** Distance between two hubs' origins along +X (the studio site is 93 blocks wide). */
	public static final int SPACING = 160;
	/**
	 * Fallback hub port for a hub record without one: {@code PORT_BASE + slot}. New hubs get the port
	 * their project owns on this machine ({@link HubProfiles#portFor}); the main hub uses the client's
	 * AGENTCRAFT_PORT (default 7878).
	 */
	public static final int PORT_BASE = 7900;
	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/**
	 * @param port 0 = the client's default Foreman port (main hub)
	 * @param theme the studio's look (dev.agentcraft.hq.Theme id), "warm" by default
	 */
	public record Hub(String id, String name, int slot, int port, String theme) {
		public Hub withTheme(String t) {
			return new Hub(id, name, slot, port, t);
		}

		public int originX() {
			return slot * SPACING;
		}

		public boolean isMain() {
			return id.equals(MAIN);
		}
	}

	private static final Hub MAIN_HUB = new Hub(MAIN, "Main", 0, 0, "warm");
	private static volatile List<Hub> hubs = List.of(MAIN_HUB);
	private static volatile long revision;

	private HubRegistry() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (HqWorld.isHq(server)) {
				load(server);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> set(List.of(MAIN_HUB)));
	}

	public static List<Hub> all() {
		return hubs;
	}

	/** Increases whenever the hub list changes (clients poll it to connect new hubs). */
	public static long revision() {
		return revision;
	}

	public static @Nullable Hub get(String id) {
		for (Hub h : hubs) {
			if (h.id().equals(id)) {
				return h;
			}
		}
		return null;
	}

	/** The hub whose slot column contains world x (within half a spacing of its origin), or null. */
	public static @Nullable Hub at(double x) {
		int slot = (int) Math.floor((x + SPACING / 2.0) / SPACING);
		for (Hub h : hubs) {
			if (h.slot() == slot) {
				return h;
			}
		}
		return null;
	}

	public static boolean validId(String id) {
		return ID.matcher(id).matches();
	}

	/** Adds a hub in the next free slot and saves the list. Server thread. */
	public static Hub create(MinecraftServer server, String id, String name) {
		String key = id.toLowerCase(Locale.ROOT);
		if (!validId(key)) {
			throw new IllegalArgumentException("hub id must be 1-32 of a-z 0-9 _ - (got '" + id + "')");
		}
		if (get(key) != null) {
			throw new IllegalArgumentException("hub '" + key + "' already exists");
		}
		int slot = hubs.stream().mapToInt(Hub::slot).max().orElse(0) + 1;
		// the port belongs to the project (profile), machine-wide: another world's hub never shares it
		String profile = HubProfiles.profileFor(key);
		java.util.Set<Integer> inWorld = new java.util.HashSet<>();
		hubs.forEach(h -> inWorld.add(h.port()));
		int port = HubProfiles.portFor(profile == null ? key : profile, inWorld);
		Hub hub = new Hub(key, name.isBlank() ? key : name, slot, port, "warm");
		List<Hub> next = new ArrayList<>(hubs);
		next.add(hub);
		set(List.copyOf(next));
		save(server);
		AgentCraft.LOGGER.info("Created hub '{}' in slot {} (x {}), Foreman port {}", hub.id(), slot, hub.originX(), hub.port());
		return hub;
	}

	/** Changes a hub's theme and saves the list (rebuild its studio to see it). Server thread. */
	public static Hub setTheme(MinecraftServer server, String id, String theme) {
		List<Hub> next = new ArrayList<>();
		Hub changed = null;
		for (Hub h : hubs) {
			if (h.id().equals(id)) {
				changed = h.withTheme(theme);
				next.add(changed);
			} else {
				next.add(h);
			}
		}
		if (changed == null) {
			throw new IllegalArgumentException("no hub '" + id + "'");
		}
		set(List.copyOf(next));
		save(server);
		return changed;
	}

	private static void set(List<Hub> list) {
		hubs = list;
		revision++;
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
	}

	private static void save(MinecraftServer server) {
		JsonArray arr = new JsonArray();
		for (Hub h : hubs) {
			JsonObject o = new JsonObject();
			o.addProperty("id", h.id());
			o.addProperty("name", h.name());
			o.addProperty("slot", h.slot());
			o.addProperty("port", h.port());
			o.addProperty("theme", h.theme());
			arr.add(o);
		}
		JsonObject root = new JsonObject();
		root.add("hubs", arr);
		Path f = file(server);
		try {
			Path tmp = f.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", f, e);
		}
	}

	private static void load(MinecraftServer server) {
		Path f = file(server);
		List<Hub> list = new ArrayList<>();
		list.add(MAIN_HUB);
		if (Files.exists(f)) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
				for (JsonElement e : root.getAsJsonArray("hubs")) {
					JsonObject o = e.getAsJsonObject();
					String id = o.get("id").getAsString();
					String theme = o.has("theme") ? o.get("theme").getAsString() : "warm";
					if (id.equals(MAIN)) {
						list.set(0, MAIN_HUB.withTheme(theme));
						continue;
					}
					if (!validId(id)) {
						continue;
					}
					list.add(new Hub(id, o.has("name") ? o.get("name").getAsString() : id, o.get("slot").getAsInt(),
						o.has("port") ? o.get("port").getAsInt() : PORT_BASE + o.get("slot").getAsInt(), theme));
				}
			} catch (IOException | RuntimeException e) {
				AgentCraft.LOGGER.warn("Could not read {}; only the main hub is known", f, e);
			}
		}
		set(List.copyOf(list));
		// hubs made before ports.json existed: their projects own the ports they already use
		for (Hub h : list) {
			String profile = HubProfiles.profileFor(h.id());
			if (!h.isMain() && profile != null) {
				HubProfiles.claim(profile, h.port());
			}
		}
		AgentCraft.LOGGER.info("Hubs: {}", hubs.stream().map(Hub::id).toList());
	}
}
