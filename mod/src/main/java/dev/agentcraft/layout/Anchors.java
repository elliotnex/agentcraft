package dev.agentcraft.layout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.world.HqWorld;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The single source of named world positions (see {@link AnchorNames} for the naming contract).
 *
 * <p>An HQ builder computes a {@link Layout} and {@link #publish publishes} it on the server thread;
 * it is saved as {@code agentcraft-anchors.json} in the world folder and loaded again whenever the HQ
 * world starts, so the layout survives restarts without rebuilding. Readers on any thread get an
 * immutable snapshot ({@link #current()}); the client (same JVM in singleplayer) reads it directly.
 * Listeners are told about every new layout (on the thread that published it).
 *
 * <p>Every hub ({@link HubRegistry}) has its own layout: {@link #of(String)}. The main hub's is
 * {@link #current()} and keeps the original file name; another hub's is saved as
 * {@code agentcraft-anchors-<hub>.json}. Plain {@link #addListener} listeners hear the main hub only;
 * {@link #addHubListener} hears every hub.
 */
public final class Anchors {
	public static final String FILE = "agentcraft-anchors.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Axis-aligned region the HQ occupies (block coordinates, inclusive). Agent path search stays inside it. */
	public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		public boolean contains(int x, int y, int z) {
			return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
		}
	}

	/** An immutable published layout. {@code revision} increases with every publish (also across loads). */
	public record Layout(String name, long revision, @Nullable Bounds bounds, Map<String, Anchor> anchors) {
		public static final Layout EMPTY = new Layout("none", 0, null, Map.of());

		public @Nullable Anchor get(String anchorName) {
			return anchors.get(anchorName);
		}

		public boolean isEmpty() {
			return anchors.isEmpty();
		}
	}

	private static volatile Layout current = Layout.EMPTY;
	private static final Map<String, Layout> HUB_LAYOUTS = new ConcurrentHashMap<>();
	private static final List<Consumer<Layout>> LISTENERS = new CopyOnWriteArrayList<>();
	private static final List<BiConsumer<String, Layout>> HUB_LISTENERS = new CopyOnWriteArrayList<>();

	private Anchors() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (HqWorld.isHq(server)) {
				load(server, HubRegistry.MAIN);
				for (HubRegistry.Hub h : HubRegistry.all()) {
					if (!h.isMain()) {
						load(server, h.id());
					}
				}
				// the freelancer's pavilion: a hub without a registry entry, known by its layout file
				if (Files.exists(file(server, dev.agentcraft.hq.FreelancePavilion.HUB))) {
					load(server, dev.agentcraft.hq.FreelancePavilion.HUB);
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			for (String hub : List.copyOf(HUB_LAYOUTS.keySet())) {
				set(hub, Layout.EMPTY);
			}
			HUB_LAYOUTS.clear();
			set(HubRegistry.MAIN, Layout.EMPTY);
		});
	}

	/** The layout of {@code hub} (EMPTY until its studio is built). */
	public static Layout of(String hub) {
		return HubRegistry.MAIN.equals(hub) ? current : HUB_LAYOUTS.getOrDefault(hub, Layout.EMPTY);
	}

	/** Every hub that has a layout, with it (main first). */
	public static Map<String, Layout> all() {
		Map<String, Layout> m = new LinkedHashMap<>();
		if (!current.isEmpty()) {
			m.put(HubRegistry.MAIN, current);
		}
		for (Map.Entry<String, Layout> e : HUB_LAYOUTS.entrySet()) {
			if (!e.getValue().isEmpty()) {
				m.put(e.getKey(), e.getValue());
			}
		}
		return m;
	}

	/** Hears every hub's new layouts: (hub id, layout). */
	public static void addHubListener(BiConsumer<String, Layout> listener) {
		HUB_LISTENERS.add(listener);
	}

	public static Layout current() {
		return current;
	}

	public static @Nullable Anchor get(String name) {
		return current.anchors().get(name);
	}

	public static void addListener(Consumer<Layout> listener) {
		LISTENERS.add(listener);
	}

	public static Builder builder(String layoutName) {
		return new Builder(layoutName, HubRegistry.MAIN, Placement.IDENTITY);
	}

	/** A builder for {@code hub}'s studio: studio coordinates are moved to the hub's spot and turned to face its way. */
	public static Builder builder(String layoutName, HubRegistry.Hub hub) {
		return new Builder(layoutName, hub.id(), hub.placement());
	}

	/** Make {@code layout} the main hub's current layout and save it with the world. Call on the server thread. */
	public static void publish(MinecraftServer server, Layout layout) {
		publish(server, HubRegistry.MAIN, layout);
	}

	/** Make {@code layout} {@code hub}'s layout and save it with the world. Call on the server thread. */
	public static void publish(MinecraftServer server, String hub, Layout layout) {
		Layout withRev = new Layout(layout.name(), of(hub).revision() + 1, layout.bounds(), layout.anchors());
		set(hub, withRev);
		save(server, hub, withRev);
		AgentCraft.LOGGER.info("Published layout '{}' of hub '{}' rev {} with {} anchors", withRev.name(), hub, withRev.revision(),
			withRev.anchors().size());
	}

	private static void set(String hub, Layout layout) {
		if (HubRegistry.MAIN.equals(hub)) {
			current = layout;
			for (Consumer<Layout> l : LISTENERS) {
				try {
					l.accept(layout);
				} catch (Throwable t) {
					AgentCraft.LOGGER.warn("Anchor listener failed", t);
				}
			}
		} else {
			HUB_LAYOUTS.put(hub, layout);
		}
		for (BiConsumer<String, Layout> l : HUB_LISTENERS) {
			try {
				l.accept(hub, layout);
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("Hub anchor listener failed", t);
			}
		}
	}

	// ------------------------------------------------------------------ persistence

	private static Path file(MinecraftServer server, String hub) {
		return server.getWorldPath(LevelResource.ROOT).resolve(HubRegistry.MAIN.equals(hub) ? FILE : "agentcraft-anchors-" + hub + ".json");
	}

	private static void save(MinecraftServer server, String hub, Layout layout) {
		JsonObject root = toJson(layout);
		Path f = file(server, hub);
		try {
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", f, e);
		}
	}

	private static void load(MinecraftServer server, String hub) {
		Path f = file(server, hub);
		if (!Files.exists(f)) {
			AgentCraft.LOGGER.info("No {} yet (run /agentcraft hq to build the HQ and its anchors)", f.getFileName());
			set(hub, Layout.EMPTY);
			return;
		}
		try {
			Layout layout = fromJson(JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
			set(hub, layout);
			AgentCraft.LOGGER.info("Loaded layout '{}' of hub '{}' rev {} ({} anchors)", layout.name(), hub, layout.revision(), layout.anchors().size());
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("Could not read {}; run /agentcraft hq again", f, e);
			set(hub, Layout.EMPTY);
		}
	}

	public static JsonObject toJson(Layout layout) {
		JsonObject root = new JsonObject();
		root.addProperty("layout", layout.name());
		root.addProperty("revision", layout.revision());
		if (layout.bounds() != null) {
			Bounds b = layout.bounds();
			JsonObject bj = new JsonObject();
			bj.addProperty("minX", b.minX());
			bj.addProperty("minY", b.minY());
			bj.addProperty("minZ", b.minZ());
			bj.addProperty("maxX", b.maxX());
			bj.addProperty("maxY", b.maxY());
			bj.addProperty("maxZ", b.maxZ());
			root.add("bounds", bj);
		}
		JsonObject anchors = new JsonObject();
		layout.anchors().forEach((name, a) -> anchors.add(name, anchorJson(a)));
		root.add("anchors", anchors);
		return root;
	}

	public static JsonObject anchorJson(Anchor a) {
		JsonObject o = new JsonObject();
		o.addProperty("x", round(a.x()));
		o.addProperty("y", round(a.y()));
		o.addProperty("z", round(a.z()));
		o.addProperty("yaw", round(a.yaw()));
		o.addProperty("pitch", round(a.pitch()));
		return o;
	}

	private static double round(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	static Layout fromJson(JsonObject root) {
		Map<String, Anchor> map = new LinkedHashMap<>();
		JsonObject anchors = root.has("anchors") ? root.getAsJsonObject("anchors") : new JsonObject();
		for (var e : anchors.entrySet()) {
			JsonObject o = e.getValue().getAsJsonObject();
			map.put(e.getKey(), new Anchor(e.getKey(), o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
				o.has("yaw") ? o.get("yaw").getAsFloat() : 0f, o.has("pitch") ? o.get("pitch").getAsFloat() : 0f));
		}
		Bounds bounds = null;
		if (root.has("bounds")) {
			JsonObject b = root.getAsJsonObject("bounds");
			bounds = new Bounds(b.get("minX").getAsInt(), b.get("minY").getAsInt(), b.get("minZ").getAsInt(),
				b.get("maxX").getAsInt(), b.get("maxY").getAsInt(), b.get("maxZ").getAsInt());
		}
		String name = root.has("layout") ? root.get("layout").getAsString() : "unknown";
		long rev = root.has("revision") ? root.get("revision").getAsLong() : 1;
		return new Layout(name, rev, bounds, Collections.unmodifiableMap(map));
	}

	// ------------------------------------------------------------------ builder

	/** Collects anchors while an HQ builder runs. Later puts with the same name replace earlier ones. */
	/**
	 * Collects a layout. Builders work in studio coordinates (gate facing south); the hub's
	 * {@link Placement} moves and turns every anchor (yaw included) and the bounds.
	 */
	public static final class Builder {
		private final String name;
		private final String hub;
		private final Placement placement;
		private final Map<String, Anchor> anchors = new LinkedHashMap<>();
		private @Nullable Bounds bounds;

		private Builder(String name, String hub, Placement placement) {
			this.name = name;
			this.hub = hub;
			this.placement = placement;
		}

		public String hub() {
			return hub;
		}

		public Placement placement() {
			return placement;
		}

		public Builder put(String anchorName, double x, double y, double z, float yaw, float pitch) {
			anchors.put(anchorName, new Anchor(anchorName, placement.worldX(x, z), y, placement.worldZ(x, z), placement.yaw(yaw), pitch));
			return this;
		}

		/** A standing spot: feet at the top centre of block (bx, by, bz) ... i.e. x+0.5, y, z+0.5. */
		public Builder spot(String anchorName, int bx, int feetY, int bz, float yaw) {
			return put(anchorName, bx + 0.5, feetY, bz + 0.5, yaw, 0f);
		}

		/** Camera point: eye position + view direction. */
		public Builder camera(String camName, double x, double y, double z, float yaw, float pitch) {
			String n = camName.startsWith(AnchorNames.CAM_PREFIX) ? camName : AnchorNames.CAM_PREFIX + camName;
			return put(n, x, y, z, yaw, pitch);
		}

		/** Camera point looking at a target position. */
		public Builder cameraLookAt(String camName, double x, double y, double z, double tx, double ty, double tz) {
			double dx = tx - x;
			double dy = ty - y;
			double dz = tz - z;
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			return camera(camName, x, y, z, yaw, pitch);
		}

		public Builder bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
			int[] b = placement.box(Math.min(minX, maxX), Math.min(minZ, maxZ), Math.max(minX, maxX), Math.max(minZ, maxZ));
			this.bounds = new Bounds(b[0], Math.min(minY, maxY), b[1], b[2], Math.max(minY, maxY), b[3]);
			return this;
		}

		public Layout build() {
			return new Layout(name, 0, bounds, Collections.unmodifiableMap(new LinkedHashMap<>(anchors)));
		}
	}
}
