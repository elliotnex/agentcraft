package dev.agentcraft.hq;

import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.MergeStationBlock;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.block.entity.StationBlockEntity;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import dev.agentcraft.layout.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The complete desired contents of an HQ site box, built in memory and then applied to the world as
 * a diff. That is what makes an HQ builder deterministic and idempotent:
 * <ul>
 *   <li>every cell of the box has a desired state (unset cells are the meadow: air above the
 *       ground, grass/dirt below), so a second build changes nothing; nothing outside the box is
 *       ever touched;</li>
 *   <li>only cells that differ are written, with no neighbour or shape updates (fast: no physics, no
 *       drops, no fluid flow);</li>
 *   <li>connecting blocks (stairs, panes, fences, walls, AgentCraft panels) are written in a second
 *       pass with their connections computed from the finished neighbours, and compared without
 *       those connection properties in the first pass, so they never flicker on a rebuild;</li>
 *   <li>state driven live by the Foreman (lamp status, podium open, merge station active, monitor
 *       lit, signal bulbs lit) is kept from the world when the block itself is unchanged;</li>
 *   <li>with the previous build's plan at hand ({@link PlanStore}), a cell the player changed since
 *       that build (it matches neither the old plan nor the new one) is <b>kept</b>, unless the
 *       build is forced: rebuilding never wipes the player's own additions.</li>
 * </ul>
 * The ground is a heightmap ({@link #top}): {@code HqLandscape} shapes it before anything is built.
 */
final class Plan {
	/** Write without neighbour updates, shape updates, drops, block-entity side effects or onPlace. */
	static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
		| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SKIP_ON_PLACE;
	private static final Set<String> CONNECTION_PROPS = Set.of("shape", "north", "east", "south", "west", "up", "down", "left", "right",
		"in_wall");

	final int minX;
	final int minY;
	final int minZ;
	final int maxX;
	final int maxY;
	final int maxZ;
	private final int sx;
	private final int sy;
	private final int sz;
	private final BlockState[] cells;
	private final int[] top;
	private final Map<BlockPos, String> bindings = new LinkedHashMap<>();
	/** Material swaps for the hub's look ({@link Theme}); WARM changes nothing. */
	private Theme theme = Theme.WARM;
	/** Cells some builder set explicitly (not left as meadow). */
	private BitSet touched = new BitSet();
	/**
	 * Sparse plans (the hub network) only claim the cells they set: on a first build, with no record
	 * of a previous one, everything else in the box is left as it is (it may be the player's).
	 */
	private boolean sparse;

	/**
	 * @param changed cells written in the first pass; {@code connected}: cells whose connections
	 *     changed in the second pass; {@code kept}: cells the player changed since the last build that
	 *     were left alone; {@code foreign}: changed cells that held something neither the old nor the
	 *     new plan has (a forced build, or no record of the previous build); {@code items}: dropped
	 *     items / orbs removed from the box.
	 */
	record Stats(int cells, int changed, int connected, int bound, int kept, int foreign, int items, long micros, List<String> sample,
		List<String> keptSample) {
	}

	/** @param ground desired state of an unset cell by y (the meadow profile); the ground top is {@code groundTop}. */
	Plan(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int groundTop, IntFunction<BlockState> ground) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
		this.sx = maxX - minX + 1;
		this.sy = maxY - minY + 1;
		this.sz = maxZ - minZ + 1;
		this.cells = new BlockState[sx * sy * sz];
		this.top = new int[sx * sz];
		java.util.Arrays.fill(top, groundTop);
		for (int y = minY; y <= maxY; y++) {
			BlockState g = ground.apply(y);
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					cells[index(x, y, z)] = g;
				}
			}
		}
	}

	int size() {
		return cells.length;
	}

	BlockState[] cells() {
		return cells;
	}

	boolean in(int x, int y, int z) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}

	boolean inXZ(int x, int z) {
		return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
	}

	private int index(int x, int y, int z) {
		return ((y - minY) * sz + (z - minZ)) * sx + (x - minX);
	}

	BlockState get(int x, int y, int z) {
		return in(x, y, z) ? cells[index(x, y, z)] : Blocks.AIR.defaultBlockState();
	}

	boolean isAir(int x, int y, int z) {
		return get(x, y, z).isAir();
	}

	void theme(Theme t) {
		this.theme = t;
	}

	void sparse(boolean s) {
		this.sparse = s;
	}

	void set(int x, int y, int z, BlockState state) {
		if (in(x, y, z)) {
			int i = index(x, y, z);
			cells[i] = theme.apply(state);
			touched.set(i);
		}
	}

	void set(int x, int y, int z, Block block) {
		set(x, y, z, block.defaultBlockState());
	}

	/** Only where the plan currently has air. */
	void setIfAir(int x, int y, int z, BlockState state) {
		if (isAir(x, y, z)) {
			set(x, y, z, state);
		}
	}

	void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
					set(x, y, z, state);
				}
			}
		}
	}

	void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
		fill(x0, y0, z0, x1, y1, z1, block.defaultBlockState());
	}

	/** Ground top (the grass block's y) of column (x, z); outside the box: the meadow default. */
	int top(int x, int z) {
		return inXZ(x, z) ? top[(z - minZ) * sx + (x - minX)] : StudioHqBuilder.GROUND;
	}

	void setTop(int x, int z, int y) {
		if (inXZ(x, z)) {
			top[(z - minZ) * sx + (x - minX)] = y;
		}
	}

	/** Binding for the station block entity at (x, y, z), applied after the blocks. */
	void bind(int x, int y, int z, String binding) {
		bindings.put(new BlockPos(x, y, z), binding);
	}

	/**
	 * Vanilla's grass rule applied to the plan (SpreadingSnowyBlock): grass under a full fluid or a
	 * block that stops light from its top face becomes dirt, so the world never "decays" between two
	 * builds (which would make a rebuild change cells).
	 */
	void settleGrass() {
		BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
		for (int y = minY; y < maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					BlockState s = cells[index(x, y, z)];
					boolean isGrass = s.is(Blocks.GRASS_BLOCK);
					if (!isGrass && !s.is(Blocks.DIRT)) {
						continue;
					}
					BlockState above = cells[index(x, y + 1, z)];
					boolean lives = !above.getFluidState().isFull()
						&& LightEngine.getLightDampeningInto(grass, above, Direction.UP, above.getLightDampening()) < 15;
					// grass that cannot live decays to dirt; dirt that grass could live on is eventually
					// grown over by a neighbour: both are settled here, so a rebuild never finds them changed
					if (isGrass && !lives) {
						cells[index(x, y, z)] = Blocks.DIRT.defaultBlockState();
					} else if (!isGrass && lives) {
						cells[index(x, y, z)] = grass;
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ apply

	private static boolean connecting(BlockState s) {
		Block b = s.getBlock();
		return b instanceof StairBlock || b instanceof CrossCollisionBlock || b instanceof WallBlock || b instanceof FenceGateBlock
			|| b instanceof PanelBlock;
	}

	/** Properties the Foreman drives (or that a redstone-less bulb toggles): never part of the design. */
	private static boolean drivenProp(Block b, Property<?> p) {
		return b instanceof StatusLampBlock && p == StatusLampBlock.STATUS || b instanceof DecisionPodiumBlock && p == DecisionPodiumBlock.OPEN
			|| b instanceof MergeStationBlock && p == MergeStationBlock.ACTIVE || b instanceof MonitorBlock && p == MonitorBlock.LIT
			|| b instanceof CopperBulbBlock && (p == CopperBulbBlock.LIT || p == CopperBulbBlock.POWERED);
	}

	/** Same block and same design: ignores connection properties and Foreman-driven properties. */
	static boolean sameDesign(BlockState a, BlockState b) {
		if (a == b) {
			return true;
		}
		if (a.getBlock() != b.getBlock()) {
			return false;
		}
		Block blk = b.getBlock();
		for (Property<?> p : b.getProperties()) {
			if (CONNECTION_PROPS.contains(p.getName()) || drivenProp(blk, p)) {
				continue;
			}
			if (!a.getValue(p).equals(b.getValue(p))) {
				return false;
			}
		}
		return true;
	}

	/** Same block and same properties, ignoring connection properties. */
	private static boolean sameIgnoringConnections(BlockState a, BlockState b) {
		if (a.getBlock() != b.getBlock()) {
			return false;
		}
		for (Property<?> p : b.getProperties()) {
			if (!CONNECTION_PROPS.contains(p.getName()) && !a.getValue(p).equals(b.getValue(p))) {
				return false;
			}
		}
		return true;
	}

	/** Keep the live, Foreman-driven property values of an unchanged station block. */
	private static BlockState keepDriven(BlockState current, BlockState desired) {
		if (current.getBlock() != desired.getBlock()) {
			return desired;
		}
		Block b = desired.getBlock();
		if (b instanceof StatusLampBlock) {
			return desired.setValue(StatusLampBlock.STATUS, current.getValue(StatusLampBlock.STATUS));
		}
		if (b instanceof DecisionPodiumBlock) {
			return desired.setValue(DecisionPodiumBlock.OPEN, current.getValue(DecisionPodiumBlock.OPEN));
		}
		if (b instanceof MergeStationBlock) {
			return desired.setValue(MergeStationBlock.ACTIVE, current.getValue(MergeStationBlock.ACTIVE));
		}
		if (b instanceof MonitorBlock) {
			return desired.setValue(MonitorBlock.LIT, current.getValue(MonitorBlock.LIT));
		}
		if (b instanceof CopperBulbBlock) {
			return desired.setValue(CopperBulbBlock.LIT, current.getValue(CopperBulbBlock.LIT));
		}
		return desired;
	}

	/**
	 * Applies the plan to the world (server thread).
	 *
	 * @param previous the plan applied by the last build of this builder (same box), or null when
	 *     there is no record of it: then every cell is set to the plan.
	 * @param force set every cell to the plan even where the player changed it.
	 */
	Stats apply(ServerLevel level, BlockState @Nullable [] previous, boolean force) {
		return apply(level, previous, force, Placement.IDENTITY);
	}

	/** {@code cells[i]} as it stands in the world: turned with the studio. */
	private static BlockState turned(BlockState s, Placement pl) {
		return pl.turns() == 0 ? s : s.rotate(pl.rotation());
	}

	/**
	 * Applies the plan placed in the world ({@link Placement}: moved to the hub's origin and turned to
	 * face its way): a hub's studio is the same plan built at the hub's spot. Every world cell of the
	 * placed box maps back to one plan cell; blocks turn with the studio (stairs, logs, facings).
	 */
	Stats apply(ServerLevel level, BlockState @Nullable [] previous, boolean force, Placement pl) {
		long t0 = System.nanoTime();
		int changed = 0;
		int kept = 0;
		int foreign = 0;
		List<String> sample = new ArrayList<>();
		List<String> keptSample = new ArrayList<>();
		BitSet keptCells = new BitSet();
		List<BlockPos> deferred = new ArrayList<>();
		boolean guard = previous != null && previous.length == cells.length && !force;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int[] wb = pl.box(minX, minZ, maxX, maxZ);
		for (int cx = wb[0] >> 4; cx <= wb[2] >> 4; cx++) {
			for (int cz = wb[1] >> 4; cz <= wb[3] >> 4; cz++) {
				LevelChunk chunk = level.getChunk(cx, cz);
				int x0 = Math.max(wb[0], cx << 4);
				int x1 = Math.min(wb[2], (cx << 4) + 15);
				int z0 = Math.max(wb[1], cz << 4);
				int z1 = Math.min(wb[3], (cz << 4) + 15);
				for (int y = minY; y <= maxY; y++) {
					for (int z = z0; z <= z1; z++) {
						for (int x = x0; x <= x1; x++) {
							// x, z: world; the plan cell is the placement's inverse of it
							int i = index(pl.localX(x, z), y, pl.localZ(x, z));
							if (sparse && previous == null && !touched.get(i)) {
								continue; // first build of a sparse plan: only its own cells
							}
							BlockState want = turned(cells[i], pl);
							m.set(x, y, z);
							BlockState cur = chunk.getBlockState(m);
							if (cur != want && !sameDesign(cur, want)) {
								BlockState before = guard ? turned(previous[i], pl) : null;
								if (guard && !sameDesign(cur, before)) {
									// the player changed this cell after the last build: leave it (terrain that only
									// reacted to a player's block, e.g. grass turned to dirt under it, is kept silently)
									if (!(natural(cur) && natural(before))) {
										kept++;
									}
									keptCells.set(i);
									if (keptSample.size() < 8) {
										keptSample.add(m.toShortString() + " " + cur.getBlock().getDescriptionId().replace("block.minecraft.", ""));
									}
									continue;
								}
								if (!cur.isAir() && (previous == null || !sameDesign(cur, turned(previous[i], pl))) && !natural(cur)) {
									foreign++;
								}
							}
							if (connecting(want)) {
								deferred.add(m.immutable());
								want = keepDriven(cur, want);
								if (!sameIgnoringConnections(cur, want)) {
									note(sample, m, cur, want);
									level.setBlock(m, want, FLAGS);
									changed++;
								}
								continue;
							}
							want = keepDriven(cur, want);
							if (cur != want) {
								note(sample, m, cur, want);
								level.setBlock(m, want, FLAGS);
								changed++;
							}
						}
					}
				}
			}
		}
		// connections from the finished neighbours
		int connected = 0;
		for (BlockPos p : deferred) {
			BlockState cur = level.getBlockState(p);
			BlockState planned = turned(cells[index(pl.localX(p.getX(), p.getZ()), p.getY(), pl.localZ(p.getX(), p.getZ()))], pl);
			BlockState want = Block.updateFromNeighbourShapes(keepDriven(cur, planned), level, p);
			if (cur != want) {
				note(sample, p, cur, want);
				level.setBlock(p, want, FLAGS);
				connected++;
			}
		}
		int bound = 0;
		for (var e : bindings.entrySet()) {
			BlockPos p = e.getKey();
			if (in(p.getX(), p.getY(), p.getZ()) && keptCells.get(index(p.getX(), p.getY(), p.getZ()))) {
				continue;
			}
			BlockPos w = new BlockPos(pl.worldX(p.getX(), p.getZ()), p.getY(), pl.worldZ(p.getX(), p.getZ()));
			if (level.getBlockEntity(w) instanceof StationBlockEntity be) {
				if (!be.binding().equals(e.getValue())) {
					bound++;
				}
				be.setBinding(e.getValue());
			}
		}
		int items = clearDrops(level, wb);
		return new Stats(cells.length, changed, connected, bound, kept, foreign, items, (System.nanoTime() - t0) / 1000, sample, keptSample);
	}

	/** Terrain, plants and fluids: replacing them is not "replacing something the player built". */
	private static boolean natural(BlockState s) {
		return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.STONE) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS)
			|| s.is(Blocks.WATER) || s.is(Blocks.DIRT_PATH) || s.getBlock() instanceof net.minecraft.world.level.block.VegetationBlock
			|| s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock;
	}

	/**
	 * Removes dropped items and experience orbs inside the box (a switch from another builder can
	 * leave items behind, e.g. carpets that lost their floor). Players, agents and other mobs stay.
	 */
	private int clearDrops(ServerLevel level, int[] wb) {
		AABB box = new AABB(wb[0], minY, wb[1], wb[2] + 1, maxY + 1, wb[3] + 1);
		int n = 0;
		for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
			e.discard();
			n++;
		}
		for (ExperienceOrb e : level.getEntitiesOfClass(ExperienceOrb.class, box)) {
			e.discard();
			n++;
		}
		return n;
	}

	private static void note(List<String> sample, BlockPos p, BlockState from, BlockState to) {
		if (sample.size() < 6) {
			sample.add(p.toShortString() + " " + from + " -> " + to);
		}
	}
}
