package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.layout.HubRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ground between hubs: a broad stone promenade (as wide as the courtyard) running east-west just
 * south of every studio's grounds, a short spur from each studio's lychgate path onto it, and, across
 * from the main hub, a courtyard that opens off the promenade at full width: fountain, benches, trees
 * and the hub wall (a live board of every hub's goal, progress, team and waiting decisions).
 *
 * <p>Each hub owns one segment: the strip south of its studio, {@link #HALF} blocks either side of
 * its origin, so adding a hub never moves anything already built. Segments are {@link Plan}s like the
 * studios (built as a diff, the player's changes kept, a record per segment) in the hub's theme, and
 * sparse: the first build only writes the cells the network uses.
 */
final class NetworkBuilder {
	static final String ID = "network";
	/** A segment spans origin - HALF .. origin + HALF - 1 (adjacent segments meet without overlap). */
	static final int HALF = HubRegistry.SPACING / 2;
	static final int GROUND = StudioHqBuilder.GROUND;
	static final int Z0 = StudioHqBuilder.SITE[5] + 1;   // 55: just outside the studio grounds
	/** The courtyard's width; the promenade is as wide. */
	static final int WIDTH = 27;
	/** Hub cards per row on the hub wall (5 x 4 blocks + 1 fits the courtyard). */
	static final int WALL_COLS = 5;
	static final int PROM_Z0 = Z0 + 2;                    // 57
	static final int PROM_Z1 = PROM_Z0 + WIDTH - 1;       // 83
	static final int PROM_MID = (PROM_Z0 + PROM_Z1) / 2;  // 70
	static final int YARD_Z0 = PROM_Z1 + 1;               // 84: opens straight off the promenade
	static final int YARD_Z1 = YARD_Z0 + 28;              // 112
	static final int Z1 = YARD_Z1 + 2;
	static final int Y0 = GROUND - 4;
	static final int Y1 = GROUND + 12;
	/** The studio's path leaves its grounds here (relative to the hub origin). */
	static final int SPUR_X = HqLandscape.pathX(HqLandscape.GATE_Z);

	private static final BlockState STONE = Blocks.STONE_BRICKS.defaultBlockState();
	private static final BlockState MOSSY = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
	private static final BlockState EDGE = Blocks.POLISHED_ANDESITE.defaultBlockState();
	private static final BlockState BAND = Blocks.SMOOTH_STONE.defaultBlockState();
	private static final BlockState PLAZA = Blocks.SMOOTH_STONE.defaultBlockState();
	private static final BlockState CHISELED = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
	private static final BlockState WALL = Blocks.STONE_BRICK_WALL.defaultBlockState();
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState PATH = Blocks.DIRT_PATH.defaultBlockState();
	private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();

	private NetworkBuilder() {
	}

	/** Builds (or updates) the paths of the world's layout (compass or line). Server thread. */
	static String buildAll(ServerLevel level) {
		if (HubRegistry.COMPASS.equals(HubRegistry.layout())) {
			return CompassNetwork.build(level);
		}
		return buildLine(level);
	}

	/** The line layout: one segment per hub. */
	static String buildLine(ServerLevel level) {
		List<HubRegistry.Hub> hubs = new ArrayList<>(HubRegistry.all());
		hubs.removeIf(h -> Anchors.of(h.id()).isEmpty());
		hubs.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
		int changed = 0;
		for (HubRegistry.Hub h : hubs) {
			changed += buildSegment(level, h, hubs);
		}
		String report = String.format(Locale.ROOT, "paths between %d hub%s: %d blocks updated", hubs.size(), hubs.size() == 1 ? "" : "s", changed);
		AgentCraft.LOGGER.info("Hub network: {}", report);
		return report;
	}

	private static int buildSegment(ServerLevel level, HubRegistry.Hub hub, List<HubRegistry.Hub> hubs) {
		int o = hub.originX();
		int[] box = {o - HALF, Y0, Z0, o + HALF - 1, Y1, Z1};
		Plan p = new Plan(box[0], box[1], box[2], box[3], box[4], box[5], GROUND, y -> y > GROUND ? StudioHqBuilder.AIR
			: y == GROUND ? GRASS : y >= GROUND - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
		p.theme(Theme.byId(hub.theme()));
		p.sparse(true);
		boolean first = hubs.getFirst() == hub;
		boolean last = hubs.getLast() == hub;
		int west = first ? o - 50 : o - HALF;
		int east = last ? o + 50 : o + HALF - 1;
		int sx = o + SPUR_X;
		boolean yard = hub.isMain();
		promenade(p, west, east, sx, yard);
		spur(p, sx);
		List<BlockPos> signs = new ArrayList<>();
		List<String[]> texts = new ArrayList<>();
		hubSign(p, hub, sx, signs, texts);
		if (yard) {
			courtyard(p, sx, hubs.size());
		}
		String key = "net-" + hub.id();
		var previous = PlanStore.load(level.getServer(), key, ID, box, p.size());
		Plan.Stats st = p.apply(level, previous, false);
		PlanStore.save(level.getServer(), key, ID, box, p.cells());
		for (int i = 0; i < signs.size(); i++) {
			writeSign(level, signs.get(i), texts.get(i));
		}
		return st.changed() + st.connected();
	}

	// ------------------------------------------------------------------ promenade + spur

	/**
	 * A stone field {@link #WIDTH} blocks wide: polished edges, a smooth centre line and cross bands
	 * every 9 blocks, two rows of planted trees with lanterns between them, hedges along the outside
	 * (open where the spur and the courtyard join), posts at the ends.
	 */
	private static void promenade(Plan p, int west, int east, int sx, boolean yardOpening) {
		int yardX0 = sx - WIDTH / 2;
		int yardX1 = sx + WIDTH / 2;
		for (int x = west; x <= east; x++) {
			for (int z = PROM_Z0; z <= PROM_Z1; z++) {
				BlockState s;
				if (z == PROM_Z0 || z == PROM_Z1) {
					s = EDGE;
				} else if (z == PROM_MID || Math.floorMod(x - sx, 9) == 0) {
					s = BAND;
				} else {
					s = Math.floorMod(x * 7 + z * 3, 13) == 0 ? MOSSY : STONE;
				}
				p.set(x, GROUND, z, s);
				for (int y = GROUND + 1; y <= GROUND + 5; y++) {
					p.set(x, y, z, StudioHqBuilder.AIR);
				}
			}
			boolean spurGap = Math.abs(x - sx) <= 2;
			boolean yardGap = yardOpening && x >= yardX0 && x <= yardX1;
			if (!spurGap) {
				p.set(x, GROUND + 1, PROM_Z0 - 1, St.leaves(Blocks.AZALEA_LEAVES));
			}
			if (!yardGap) {
				p.set(x, GROUND + 1, PROM_Z1 + 1, St.leaves(Blocks.AZALEA_LEAVES));
			}
			// two rows of trees, 16 apart, lanterns halfway between; none in front of the spur
			int k = Math.floorMod(x - sx, 16);
			for (int z : new int[] {PROM_Z0 + 4, PROM_Z1 - 4}) {
				if (k == 8 && !spurGap) {
					planter(p, x, z);
				} else if (k == 0 && !spurGap) {
					post(p, x, z);
				}
			}
		}
		post(p, west, PROM_Z0 + 1);
		post(p, west, PROM_Z1 - 1);
		post(p, east, PROM_Z0 + 1);
		post(p, east, PROM_Z1 - 1);
	}

	/** From the studio's grounds (z = {@link #Z0}) onto the promenade, three wide. */
	private static void spur(Plan p, int sx) {
		for (int z = Z0; z < PROM_Z0; z++) {
			for (int x = sx - 2; x <= sx + 2; x++) {
				for (int y = GROUND + 1; y <= GROUND + 5; y++) {
					p.set(x, y, z, StudioHqBuilder.AIR);
				}
				if (Math.abs(x - sx) <= 1) {
					p.set(x, GROUND, z, PATH);
				}
			}
		}
		// fence-and-lantern posts in the hub's wood where the spur meets the promenade
		for (int side : new int[] {-3, 3}) {
			int x = sx + side;
			p.set(x, GROUND + 1, PROM_Z0, Blocks.SPRUCE_FENCE.defaultBlockState());
			p.set(x, GROUND + 2, PROM_Z0, Blocks.SPRUCE_FENCE.defaultBlockState());
			p.set(x, GROUND + 3, PROM_Z0, StudioHqBuilder.LANTERN);
		}
	}

	static void post(Plan p, int x, int z) {
		p.set(x, GROUND + 1, z, WALL);
		p.set(x, GROUND + 2, z, WALL);
		p.set(x, GROUND + 3, z, StudioHqBuilder.LANTERN);
	}

	/** A 3x3 bed with a small tree, edged in stone-brick slabs. */
	static void planter(Plan p, int x, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				p.set(x + dx, GROUND, z + dz, GRASS);
			}
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
					p.set(x + dx, GROUND + 1, z + dz, Blocks.STONE_BRICK_SLAB.defaultBlockState());
				}
			}
		}
		tree(p, x, z);
	}

	/** A sign where the spur meets the promenade, naming the hub. */
	private static void hubSign(Plan p, HubRegistry.Hub hub, int sx, List<BlockPos> signs, List<String[]> texts) {
		int x = sx + 4;
		int z = PROM_Z0 + 1;
		p.set(x, GROUND + 1, z, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8));
		signs.add(new BlockPos(x, GROUND + 1, z));
		texts.add(new String[] {"", hub.name(), Theme.byId(hub.theme()).name, ""});
	}

	// ------------------------------------------------------------------ courtyard

	private static void courtyard(Plan p, int sx, int hubCount) {
		int cx = sx;
		int x0 = cx - WIDTH / 2;
		int x1 = cx + WIDTH / 2;
		int cz = (YARD_Z0 + YARD_Z1) / 2 - 2;
		// floor: smooth stone, a stone-brick border on three sides (open to the promenade)
		for (int x = x0; x <= x1; x++) {
			for (int z = YARD_Z0; z <= YARD_Z1; z++) {
				boolean border = x == x0 || x == x1 || z == YARD_Z1;
				p.set(x, GROUND, z, border ? STONE : PLAZA);
				for (int y = GROUND + 1; y <= GROUND + 6; y++) {
					p.set(x, y, z, StudioHqBuilder.AIR);
				}
			}
		}
		// hedges along the sides
		for (int z = YARD_Z0; z <= YARD_Z1; z++) {
			p.set(x0 - 1, GROUND + 1, z, St.leaves(Blocks.AZALEA_LEAVES));
			p.set(x1 + 1, GROUND + 1, z, St.leaves(Blocks.AZALEA_LEAVES));
		}
		// fountain: a 7x7 basin, still water, a chiseled column with a lantern
		for (int x = cx - 3; x <= cx + 3; x++) {
			for (int z = cz - 3; z <= cz + 3; z++) {
				boolean rim = Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3;
				p.set(x, GROUND, z, STONE);
				p.set(x, GROUND + 1, z, rim ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : WATER);
			}
		}
		p.set(cx, GROUND + 1, cz, CHISELED);
		p.set(cx, GROUND + 2, cz, CHISELED);
		p.set(cx, GROUND + 3, cz, StudioHqBuilder.LANTERN);
		// benches facing the fountain (spruce: they follow the theme)
		for (int d = -2; d <= 2; d++) {
			p.set(cx + d, GROUND + 1, cz - 6, St.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, false));
			p.set(cx + d, GROUND + 1, cz + 6, St.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
			p.set(cx - 6, GROUND + 1, cz + d, St.stairs(Blocks.SPRUCE_STAIRS, Direction.WEST, false));
			p.set(cx + 6, GROUND + 1, cz + d, St.stairs(Blocks.SPRUCE_STAIRS, Direction.EAST, false));
		}
		// trees in the four corners, lanterns at the open corners
		for (int tx : new int[] {x0 + 3, x1 - 3}) {
			for (int tz : new int[] {YARD_Z0 + 3, YARD_Z1 - 9}) {
				tree(p, tx, tz);
			}
			post(p, tx, YARD_Z0 + 1);
		}
		hubWall(p, cx, hubCount);
	}

	/**
	 * The hub wall on the courtyard's south side: a task_board panel bound to "hubs" (the client draws
	 * every hub on it and opens the hubs overview on a click), in a stone-brick frame with a slab hood
	 * and lanterns either side. Sized for its cards: 4 blocks of width per hub, up to {@link #WALL_COLS}
	 * a row, 3 blocks of height per row (the client lays them out the same way).
	 */
	private static void hubWall(Plan p, int cx, int hubCount) {
		hubWall(p, cx, YARD_Z1 - 1, hubCount);
	}

	/** The hub wall centred on x {@code cx}, its board on line {@code z} facing north, backed at z + 1. */
	static void hubWall(Plan p, int cx, int z, int hubCount) {
		int cols = Math.max(1, Math.min(hubCount, WALL_COLS));
		int rows = (hubCount + WALL_COLS - 1) / WALL_COLS;
		int w = Math.max(7, 4 * cols + 1);
		int bx0 = cx - w / 2;
		int bx1 = bx0 + w - 1;
		int y0 = GROUND + 2;
		int y1 = y0 + 3 * Math.max(1, rows) - 1;
		BlockState board = ModBlocks.TASK_BOARD.defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH);
		for (int x = bx0 - 1; x <= bx1 + 1; x++) {
			for (int y = GROUND + 1; y <= y1 + 1; y++) {
				p.set(x, y, z + 1, STONE); // backing
				boolean frame = x == bx0 - 1 || x == bx1 + 1 || y == GROUND + 1 || y == y1 + 1;
				if (frame) {
					p.set(x, y, z, y == GROUND + 1 ? CHISELED : STONE);
				}
			}
			p.set(x, y1 + 2, z, St.slab(Blocks.STONE_BRICK_SLAB, false));
			p.set(x, y1 + 2, z + 1, STONE);
		}
		for (int x = bx0; x <= bx1; x++) {
			for (int y = y0; y <= y1; y++) {
				p.set(x, y, z, board);
				p.bind(x, y, z, "hubs");
			}
		}
		post(p, bx0 - 3, z);
		post(p, bx1 + 3, z);
	}

	static void tree(Plan p, int x, int z) {
		p.set(x, GROUND, z, GRASS);
		for (int y = GROUND + 1; y <= GROUND + 3; y++) {
			p.set(x, y, z, St.log(Blocks.SPRUCE_LOG, Direction.Axis.Y));
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int y = GROUND + 3; y <= GROUND + 4; y++) {
					if (dx != 0 || dz != 0 || y == GROUND + 4) {
						p.set(x + dx, y, z + dz, St.leaves(Blocks.FLOWERING_AZALEA_LEAVES));
					}
				}
			}
		}
		p.set(x, GROUND + 5, z, St.leaves(Blocks.AZALEA_LEAVES));
	}

	static void writeSign(ServerLevel level, BlockPos pos, String[] lines) {
		if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
			return;
		}
		List<Component> msgs = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			msgs.add(Component.literal(i < lines.length ? lines[i] : ""));
		}
		sign.setText(new SignText(msgs, msgs, DyeColor.BLACK, false), SignTextSlot.FRONT);
		sign.setWaxed(true);
		sign.setChanged();
		BlockState st = level.getBlockState(pos);
		level.sendBlockUpdated(pos, st, st, Block.UPDATE_CLIENTS);
	}
}
