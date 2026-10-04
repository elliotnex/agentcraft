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
 * bound to one project: its own Foreman (profile = hub id, own port) and its own team, at a spot in
 * the world facing a direction ({@link Placement}).
 *
 * <p>Two town layouts:
 * <ul>
 *   <li><b>compass</b> (new worlds): a plaza centred on {@link #CENTER_X}, {@link #centerZ()}; ring 1 is
 *       eight spots {@link #spacing()} out on every side and corner, all facing in, and ring 2 the
 *       sixteen around those ({@link #spots()}). Main is the north spot (0, 0), where the original
 *       studio stands. New towns use {@link #COMPASS_SPACING}; towns built before it shrank keep
 *       {@link #LINE_SPACING} (saved in the file as {@code spacing}) until
 *       {@code /agentcraft hub layout compass} tightens them.</li>
 *   <li><b>line</b> (worlds from before the compass): each hub {@link #LINE_SPACING} further east, all
 *       facing south. {@code /agentcraft hub layout compass} converts such a world.</li>
 * </ul>
 *
 * <p>Read from any thread ({@link #all()} is an immutable snapshot); changed on the server thread.
 * The client (same JVM in singleplayer) reads it directly, like {@link Anchors}.
 */
public final class HubRegistry {
	public static final String FILE = "agentcraft-hubs.json";
	public static final String MAIN = "main";
	public static final String LINE = "line";
	public static final String COMPASS = "compass";
	/** Distance between hubs in a line, and between compass spots in towns from before they shrank (the studio grounds are 93 x 91). */
	public static final int LINE_SPACING = 160;
	/** Distance between neighbouring compass spots in new towns: 27 blocks between studios side by side, 15-wide roads. */
	public static final int COMPASS_SPACING = 120;
	public static final int CENTER_X = 0;
	/**
	 * Fallback hub port for a hub record without one: {@code PORT_BASE + slot}. New hubs get the port
	 * their project owns on this machine ({@link HubProfiles#portFor}); the main hub uses the client's
	 * AGENTCRAFT_PORT (default 7878).
	 */
	public static final int PORT_BASE = 7900;
	/** A studio's grounds in studio coordinates {x0, z0, x1, z1} (gate facing south). */
	public static final int[] STUDIO_BOX = {-46, -36, 46, 54};
	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** A spot a compass hub can take: origin and the way its gate faces (towards the plaza). */
	public record Spot(int x, int z, char facing, int ring) {
	}

	/** Compass spots {@code s} apart in the order new hubs take them: ring 1 (8), then ring 2 (16). Main is the first. */
	public static List<Spot> spotsFor(int s) {
		List<Spot> out = new ArrayList<>();
		int cz = s; // the plaza is one spacing south of main
		// ring 1: north (main), the north corners, east and west, then the south side
		int[][] r1 = {{0, -1}, {1, -1}, {-1, -1}, {1, 0}, {-1, 0}, {0, 1}, {1, 1}, {-1, 1}};
		for (int[] g : r1) {
			out.add(new Spot(CENTER_X + g[0] * s, cz + g[1] * s, facingIn(g[0], g[1], 1), 1));
		}
		// ring 2: the perimeter of the 5 x 5 grid, north side first, clockwise
		List<int[]> r2 = new ArrayList<>();
		for (int i = -2; i <= 2; i++) {
			r2.add(new int[] {i, -2});
		}
		for (int j = -1; j <= 2; j++) {
			r2.add(new int[] {2, j});
		}
		for (int i = 1; i >= -2; i--) {
			r2.add(new int[] {i, 2});
		}
		for (int j = 1; j >= -1; j--) {
			r2.add(new int[] {-2, j});
		}
		for (int[] g : r2) {
			out.add(new Spot(CENTER_X + g[0] * s, cz + g[1] * s, facingIn(g[0], g[1], 2), 2));
		}
		return List.copyOf(out);
	}

	/** Which way a studio at grid (i, j) of ring r faces so its gate looks at the plaza. */
	private static char facingIn(int i, int j, int r) {
		if (j == -r) {
			return 'S';
		}
		if (j == r) {
			return 'N';
		}
		return i == -r ? 'E' : 'W';
	}

	/**
	 * @param port 0 = the client's default Foreman port (main hub)
	 * @param theme the studio's look (dev.agentcraft.hq.Theme id), "warm" by default
	 * @param x origin x, @param z origin z, @param facing the way its gate faces (S, W, N, E)
	 */
	public record Hub(String id, String name, int slot, int port, String theme, int x, int z, char facing) {
		public Hub withTheme(String t) {
			return new Hub(id, name, slot, port, t, x, z, facing);
		}

		public Hub at(int nx, int nz, char nf) {
			return new Hub(id, name, slot, port, theme, nx, nz, nf);
		}

		public int originX() {
			return x;
		}

		public int originZ() {
			return z;
		}

		public Placement placement() {
			return new Placement(x, z, facing);
		}

		/** The world box {minX, minZ, maxX, maxZ} of its grounds. */
		public int[] grounds() {
			return placement().box(STUDIO_BOX[0], STUDIO_BOX[1], STUDIO_BOX[2], STUDIO_BOX[3]);
		}

		public boolean isMain() {
			return id.equals(MAIN);
		}
	}

	private static final Hub MAIN_HUB = new Hub(MAIN, "Main", 0, 0, "warm", 0, 0, 'S');
	private static volatile List<Hub> hubs = List.of(MAIN_HUB);
	private static volatile String layout = COMPASS;
	private static volatile int spacing = COMPASS_SPACING;
	private static volatile List<Spot> spots = spotsFor(COMPASS_SPACING);
	private static volatile long revision;

	private HubRegistry() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (HqWorld.isHq(server)) {
				load(server);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			layout = COMPASS;
			useSpacing(COMPASS_SPACING);
			set(List.of(MAIN_HUB));
		});
	}

	public static List<Hub> all() {
		return hubs;
	}

	/** {@link #COMPASS} or {@link #LINE}. */
	public static String layout() {
		return layout;
	}

	/** Distance between this world's compass spots ({@link #COMPASS_SPACING}, or {@link #LINE_SPACING} in an older town). */
	public static int spacing() {
		return spacing;
	}

	/** The plaza's centre z (x is {@link #CENTER_X}): one spacing south of main. */
	public static int centerZ() {
		return spacing;
	}

	/** This world's compass spots ({@link #spotsFor} at {@link #spacing()}). */
	public static List<Spot> spots() {
		return spots;
	}

	private static void useSpacing(int s) {
		spacing = s;
		spots = spotsFor(s);
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

	/** The hub whose grounds contain world (x, z), or null (on a road or the plaza). */
	public static @Nullable Hub at(double x, double z) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		for (Hub h : hubs) {
			int[] g = h.grounds();
			if (bx >= g[0] && bx <= g[2] && bz >= g[1] && bz <= g[3]) {
				return h;
			}
		}
		return null;
	}

	public static boolean validId(String id) {
		return ID.matcher(id).matches();
	}

	/** The next free compass spot, or null when both rings are full. */
	public static @Nullable Spot freeSpot(List<Hub> taken) {
		for (Spot s : spots) {
			boolean used = false;
			for (Hub h : taken) {
				if (h.x() == s.x() && h.z() == s.z()) {
					used = true;
					break;
				}
			}
			if (!used) {
				return s;
			}
		}
		return null;
	}

	/** The compass spot at (x, z) facing {@code f}, or null. */
	public static @Nullable Spot spotAt(int x, int z, char f) {
		for (Spot s : spots) {
			if (s.x() == x && s.z() == z && s.facing() == f) {
				return s;
			}
		}
		return null;
	}

	/** Adds a hub at the next free spot of the world's layout and saves the list. Server thread. */
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
		Hub hub;
		if (COMPASS.equals(layout)) {
			Spot spot = freeSpot(hubs);
			if (spot == null) {
				throw new IllegalArgumentException("the town is full (" + spots.size() + " hubs)");
			}
			hub = new Hub(key, name.isBlank() ? key : name, slot, port, "warm", spot.x(), spot.z(), spot.facing());
		} else {
			hub = new Hub(key, name.isBlank() ? key : name, slot, port, "warm", slot * LINE_SPACING, 0, 'S');
		}
		List<Hub> next = new ArrayList<>(hubs);
		next.add(hub);
		set(List.copyOf(next));
		save(server);
		AgentCraft.LOGGER.info("Created hub '{}' at ({}, {}) facing {}, Foreman port {}", hub.id(), hub.x(), hub.z(), hub.facing(), hub.port());
		return hub;
	}

	/** Changes a hub's theme and saves the list (rebuild its studio to see it). Server thread. */
	public static Hub setTheme(MinecraftServer server, String id, String theme) {
		return replace(server, id, h -> h.withTheme(theme));
	}

	/** Moves a hub's record to another spot (the caller rebuilds and clears the old ground). Server thread. */
	public static Hub relocate(MinecraftServer server, String id, int x, int z, char facing) {
		return replace(server, id, h -> h.at(x, z, facing));
	}

	/** Switches the world's layout and saves (the caller moves hubs and rebuilds). Server thread. */
	public static void setLayout(MinecraftServer server, String mode) {
		layout = mode;
		revision++;
		save(server);
	}

	/** Changes the distance between compass spots and saves (the caller moves hubs and rebuilds). Server thread. */
	public static void setSpacing(MinecraftServer server, int s) {
		useSpacing(s);
		revision++;
		save(server);
	}

	private static Hub replace(MinecraftServer server, String id, java.util.function.UnaryOperator<Hub> change) {
		List<Hub> next = new ArrayList<>();
		Hub changed = null;
		for (Hub h : hubs) {
			if (h.id().equals(id)) {
				changed = change.apply(h);
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
			o.addProperty("x", h.x());
			o.addProperty("z", h.z());
			o.addProperty("facing", String.valueOf(h.facing()));
			arr.add(o);
		}
		JsonObject root = new JsonObject();
		root.addProperty("layout", layout);
		root.addProperty("spacing", spacing);
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
		// a world from before hubs.json existed has one studio: it can go straight to the compass
		String mode = COMPASS;
		int s = COMPASS_SPACING;
		if (Files.exists(f)) {
			try {
				JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
				// a hubs file without a layout predates the compass: its hubs stand in a line
				mode = root.has("layout") ? root.get("layout").getAsString() : LINE;
				// a file without a spacing predates the tighter town: its spots are 160 apart
				s = root.has("spacing") ? root.get("spacing").getAsInt() : LINE_SPACING;
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
					int slot = o.get("slot").getAsInt();
					int x = o.has("x") ? o.get("x").getAsInt() : slot * LINE_SPACING;
					int z = o.has("z") ? o.get("z").getAsInt() : 0;
					char facing = o.has("facing") ? o.get("facing").getAsString().charAt(0) : 'S';
					list.add(new Hub(id, o.has("name") ? o.get("name").getAsString() : id, slot,
						o.has("port") ? o.get("port").getAsInt() : PORT_BASE + slot, theme, x, z, facing));
				}
			} catch (IOException | RuntimeException e) {
				AgentCraft.LOGGER.warn("Could not read {}; only the main hub is known", f, e);
			}
		}
		layout = mode;
		useSpacing(s);
		set(List.copyOf(list));
		// hubs made before ports.json existed: their projects own the ports they already use
		for (Hub h : list) {
			String profile = HubProfiles.profileFor(h.id());
			if (!h.isMain() && profile != null) {
				HubProfiles.claim(profile, h.port());
			}
		}
		AgentCraft.LOGGER.info("Hubs ({} layout, spacing {}): {}", layout, spacing, hubs.stream().map(Hub::id).toList());
	}
}
