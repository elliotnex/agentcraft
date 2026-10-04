package dev.agentcraft.hq;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * A hub's look: material swaps applied to every block a studio plan sets ({@link Plan#set}), so the
 * same builder makes a cherry, birch, crimson or warped studio. A theme maps whole material families
 * ("dark_oak" -> "cherry": planks, stairs, slab, fence, log, leaves, ...), brick families and the
 * copper's weathering stage; shape properties (facing, half, axis, waterlogged, ...) are copied by
 * name. A variant that does not exist (crimson has no leaves) is left as it was, so a theme never
 * places a missing block. AgentCraft's own textured blocks are never swapped.
 *
 * <p>Themes are fixed presets ({@link #ALL}); the default {@link #WARM} changes nothing.
 */
public final class Theme {
	/** Wood block suffixes swapped as a family. */
	private static final List<String> WOOD = List.of("planks", "stairs", "slab", "fence", "fence_gate", "door", "trapdoor", "button",
		"pressure_plate", "log", "wood", "leaves", "sapling");
	/** Copper blocks swapped by weathering stage ("cut_copper" -> "waxed_weathered_cut_copper"). */
	private static final List<String> COPPER = List.of("cut_copper", "cut_copper_slab", "cut_copper_stairs", "copper_bulb", "copper_chain",
		"lightning_rod", "copper_lantern", "copper_trapdoor", "copper_door", "copper_grate", "chiseled_copper");

	public static final Theme WARM = new Theme("warm", "Warm Studio", 0xFFD97757).build();
	public static final Theme CHERRY = new Theme("cherry", "Cherry Blossom", 0xFFE79AB5)
		.wood("dark_oak", "cherry").wood("spruce", "cherry").wood("birch", "pale_oak")
		.leaves("cherry", "oak", "spruce", "birch", "azalea", "flowering_azalea", "orange_poplar", "yellow_poplar")
		.logs("cherry", "oak", "spruce", "birch", "poplar")
		.copper("weathered").bricks("mud_brick", "brick")
		.swap("poppy", "pink_tulip").swap("dandelion", "pink_tulip").swap("cornflower", "allium").swap("wildflowers", "pink_petals")
		.swap("rose_bush", "peony").build();
	public static final Theme BIRCH = new Theme("birch", "Birch & Sage", 0xFF8FA98B)
		.wood("dark_oak", "birch").wood("spruce", "pale_oak").wood("pale_oak", "birch")
		.leaves("birch", "oak", "spruce", "azalea", "orange_poplar", "yellow_poplar")
		.logs("birch", "oak", "spruce", "poplar")
		.copper("weathered").bricks("mud_brick", "tuff_brick")
		.swap("poppy", "lily_of_the_valley").swap("rose_bush", "lilac").build();
	public static final Theme EMBER = new Theme("ember", "Ember", 0xFFB4553A)
		.wood("dark_oak", "crimson").wood("spruce", "mangrove").wood("pale_oak", "acacia").wood("birch", "acacia")
		.leaves("orange_poplar", "oak", "spruce", "birch", "azalea", "flowering_azalea", "yellow_poplar")
		.logs("dark_oak", "oak", "spruce", "birch", "poplar")
		.copper("exposed").bricks("mud_brick", "red_nether_brick")
		.swap("cornflower", "orange_tulip").swap("azure_bluet", "red_tulip").swap("lily_of_the_valley", "torchflower").build();
	public static final Theme MIDNIGHT = new Theme("midnight", "Midnight", 0xFF2FA3A0)
		// dark oak frame stays; warped teal on the spruce accents and trim
		.wood("spruce", "warped").wood("pale_oak", "dark_oak").wood("birch", "dark_oak")
		.leaves("dark_oak", "oak", "spruce", "birch", "orange_poplar", "yellow_poplar")
		.logs("dark_oak", "oak", "spruce", "birch", "poplar")
		.copper("oxidized").bricks("mud_brick", "deepslate_brick")
		.swap("poppy", "blue_orchid").swap("dandelion", "cornflower").swap("rose_bush", "lilac").build();

	public static final List<Theme> ALL = List.of(WARM, CHERRY, BIRCH, EMBER, MIDNIGHT);

	public final String id;
	public final String name;
	/** ARGB accent for the HUD and the hubs overview. */
	public final int accent;
	/** Block path -> replacement path, before resolution. */
	private final Map<String, String> paths = new LinkedHashMap<>();
	/** Resolved swaps (only blocks that exist). */
	private final Map<Block, Block> blocks = new HashMap<>();
	private final Map<BlockState, BlockState> cache = new HashMap<>();

	private Theme(String id, String name, int accent) {
		this.id = id;
		this.name = name;
		this.accent = accent;
	}

	public static Theme byId(@Nullable String id) {
		for (Theme t : ALL) {
			if (t.id.equals(id)) {
				return t;
			}
		}
		return WARM;
	}

	public static @Nullable Theme find(String id) {
		for (Theme t : ALL) {
			if (t.id.equals(id)) {
				return t;
			}
		}
		return null;
	}

	/** The block {@code state} becomes in this theme, with its shape properties. */
	public BlockState apply(BlockState state) {
		if (blocks.isEmpty()) {
			return state;
		}
		Block to = blocks.get(state.getBlock());
		if (to == null) {
			return state;
		}
		return cache.computeIfAbsent(state, s -> copyProperties(s, to.defaultBlockState()));
	}

	// ------------------------------------------------------------------ building a theme

	/** Every wood variant of {@code from} becomes {@code to}'s (crimson/warped: stem, hyphae). */
	private Theme wood(String from, String to) {
		for (String suffix : WOOD) {
			if (suffix.equals("leaves") || suffix.equals("sapling") || suffix.equals("log")) {
				continue; // trees: leaves() / logs()
			}
			paths.put(from + "_" + suffix, woodName(to, suffix));
		}
		paths.put("stripped_" + from + "_log", "stripped_" + woodName(to, "log"));
		paths.put("stripped_" + from + "_wood", "stripped_" + woodName(to, "wood"));
		// the studio's own beams are dark oak / spruce logs: they follow the frame
		paths.put(from + "_log", woodName(to, "log"));
		return this;
	}

	/** Leaves of every listed kind become {@code to} leaves (trees around the studio). */
	private Theme leaves(String to, String... from) {
		for (String f : from) {
			paths.put(f + "_leaves", to + "_leaves");
		}
		paths.put("azalea", to + "_sapling");
		paths.put("flowering_azalea", to + "_sapling");
		return this;
	}

	/** Tree trunks of every listed kind become {@code to} logs (only where wood() did not already map them). */
	private Theme logs(String to, String... from) {
		for (String f : from) {
			paths.putIfAbsent(f + "_log", woodName(to, "log"));
		}
		return this;
	}

	/**
	 * Copper becomes this weathering stage, waxed so it does not keep ageing between builds. The
	 * studio uses waxed copper already ("waxed_cut_copper_stairs"); plain copper maps the same way.
	 */
	private Theme copper(String stage) {
		String prefix = "waxed_" + stage + "_";
		for (String c : COPPER) {
			paths.put(c, prefix + c);
			paths.put("waxed_" + c, prefix + c);
		}
		paths.put("copper_block", prefix + "copper");
		paths.put("waxed_copper_block", prefix + "copper");
		return this;
	}

	/** A brick family: "mud_brick" -> "deepslate_brick" maps mud_bricks, mud_brick_slab, _stairs, _wall. */
	private Theme bricks(String from, String to) {
		paths.put(from + "s", to + "s");
		for (String s : List.of("_slab", "_stairs", "_wall")) {
			paths.put(from + s, to + s);
		}
		return this;
	}

	private Theme swap(String from, String to) {
		paths.put(from, to);
		return this;
	}

	private Theme build() {
		for (Map.Entry<String, String> e : paths.entrySet()) {
			Optional<Block> from = block(e.getKey());
			Optional<Block> to = block(e.getValue());
			if (from.isPresent() && to.isPresent() && from.get() != to.get()) {
				blocks.put(from.get(), to.get());
			}
		}
		return this;
	}

	private static String woodName(String wood, String suffix) {
		boolean stem = wood.equals("crimson") || wood.equals("warped");
		if (stem && suffix.equals("log")) {
			return wood + "_stem";
		}
		if (stem && suffix.equals("wood")) {
			return wood + "_hyphae";
		}
		if (wood.equals("bamboo") && suffix.equals("planks")) {
			return "bamboo_planks";
		}
		return wood + "_" + suffix;
	}

	private static Optional<Block> block(String path) {
		Identifier id = Identifier.withDefaultNamespace(path);
		return BuiltInRegistries.BLOCK.containsKey(id) ? Optional.of(BuiltInRegistries.BLOCK.getValue(id)) : Optional.empty();
	}

	/** {@code to} with every property {@code from} has under the same name and a valid value. */
	private static BlockState copyProperties(BlockState from, BlockState to) {
		BlockState out = to;
		for (Property<?> p : from.getProperties()) {
			out = copy(from, out, p);
		}
		return out;
	}

	private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<T> p) {
		for (Property<?> q : to.getProperties()) {
			if (q.getName().equals(p.getName())) {
				return setByName(to, q, p.getName(from.getValue(p)));
			}
		}
		return to;
	}

	private static <V extends Comparable<V>> BlockState setByName(BlockState s, Property<V> q, String value) {
		Optional<V> v = q.getValue(value);
		return v.isPresent() ? s.setValue(q, v.get()) : s;
	}
}
