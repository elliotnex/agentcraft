package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
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
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The paths between hubs: a stone promenade running east-west just south of every studio's grounds,
 * a spur from each studio's lychgate path down to it, and a courtyard across the promenade from the
 * main hub (fountain, benches, trees, lanterns and a signboard naming every hub).
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
	static final int Z0 = StudioHqBuilder.SITE[5] + 1;       // 55: just outside the studio grounds
	static final int PROM_Z = Z0 + 9;                         // 64: the promenade's centre line
	static final int YARD_Z0 = PROM_Z + 5;                    // 69: the courtyard's north edge
	static final int YARD_Z1 = YARD_Z0 + 28;                  // 97
	static final int Z1 = YARD_Z1 + 3;
	static final int Y0 = GROUND - 4;
	static final int Y1 = GROUND + 12;
	/** The studio's path leaves its grounds here (relative to the hub origin). */
	static final int SPUR_X = HqLandscape.pathX(HqLandscape.GATE_Z);

	private static final BlockState STONE = Blocks.STONE_BRICKS.defaultBlockState();
	private static final BlockState MOSSY = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
	private static final BlockState EDGE = Blocks.POLISHED_ANDESITE.defaultBlockState();
	private static final BlockState PLAZA = Blocks.SMOOTH_STONE.defaultBlockState();
	private static final BlockState CHISELED = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
	private static final BlockState WALL = Blocks.STONE_BRICK_WALL.defaultBlockState();
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState PATH = Blocks.DIRT_PATH.defaultBlockState();

	private NetworkBuilder() {
	}

	/** Builds (or updates) every built hub's segment. Server thread. */
	static String buildAll(ServerLevel level) {
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
			: y == GROUND ? Blocks.GRASS_BLOCK.defaultBlockState() : y >= GROUND - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
		p.theme(Theme.byId(hub.theme()));
		p.sparse(true);
		boolean first = hubs.getFirst() == hub;
		boolean last = hubs.getLast() == hub;
		boolean hasCourtyard = hub.isMain();
		int west = first ? o - 50 : o - HALF;
		int east = last ? o + 50 : o + HALF - 1;
		int sx = o + SPUR_X;
		promenade(p, west, east, sx, hasCourtyard, first, last);
		spur(p, sx);
		List<BlockPos> signs = new ArrayList<>();
		List<String[]> texts = new ArrayList<>();
		hubSign(p, hub, sx, signs, texts);
		if (hasCourtyard) {
			courtyard(p, sx, hubs, signs, texts);
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

	private static void promenade(Plan p, int west, int east, int sx, boolean courtyardOpening, boolean capWest, boolean capEast) {
		for (int x = west; x <= east; x++) {
			for (int z = PROM_Z - 1; z <= PROM_Z + 1; z++) {
				p.set(x, GROUND, z, Math.floorMod(x * 7 + z * 3, 11) == 0 ? MOSSY : STONE);
			}
			p.set(x, GROUND, PROM_Z - 2, EDGE);
			p.set(x, GROUND, PROM_Z + 2, EDGE);
			for (int z = PROM_Z - 2; z <= PROM_Z + 2; z++) {
				for (int y = GROUND + 1; y <= GROUND + 4; y++) {
					p.set(x, y, z, StudioHqBuilder.AIR);
				}
			}
			boolean spurGap = Math.abs(x - sx) <= 2;
			boolean yardGap = courtyardOpening && Math.abs(x - sx) <= 5;
			// low hedges along both sides, open where the spur and the courtyard join
			if (!spurGap) {
				p.set(x, GROUND + 1, PROM_Z - 4, St.leaves(Blocks.AZALEA_LEAVES));
			}
			if (!yardGap) {
				p.set(x, GROUND + 1, PROM_Z + 4, St.leaves(Blocks.AZALEA_LEAVES));
			}
			// lantern posts, alternating sides every 12 blocks
			if (Math.floorMod(x - sx, 12) == 6 && !spurGap) {
				post(p, x, PROM_Z - 3);
			} else if (Math.floorMod(x - sx, 12) == 0 && !yardGap && !spurGap) {
				post(p, x, PROM_Z + 3);
			}
		}
		// the ends of the promenade: a post either side
		if (capWest) {
			post(p, west, PROM_Z - 3);
			post(p, west, PROM_Z + 3);
		}
		if (capEast) {
			post(p, east, PROM_Z - 3);
			post(p, east, PROM_Z + 3);
		}
	}

	/** From the studio's grounds (z = {@link #Z0}) down to the promenade, three wide. */
	private static void spur(Plan p, int sx) {
		for (int z = Z0; z <= PROM_Z - 3; z++) {
			for (int x = sx - 2; x <= sx + 2; x++) {
				for (int y = GROUND + 1; y <= GROUND + 5; y++) {
					p.set(x, y, z, StudioHqBuilder.AIR);
				}
				if (Math.abs(x - sx) <= 1) {
					p.set(x, GROUND, z, PATH);
				}
			}
		}
		// fence-and-lantern posts in the hub's wood (they follow its theme)
		for (int side : new int[] {-3, 3}) {
			int x = sx + side;
			p.set(x, GROUND + 1, Z0 + 3, Blocks.SPRUCE_FENCE.defaultBlockState());
			p.set(x, GROUND + 2, Z0 + 3, Blocks.SPRUCE_FENCE.defaultBlockState());
			p.set(x, GROUND + 3, Z0 + 3, StudioHqBuilder.LANTERN);
		}
	}

	private static void post(Plan p, int x, int z) {
		p.set(x, GROUND + 1, z, WALL);
		p.set(x, GROUND + 2, z, WALL);
		p.set(x, GROUND + 3, z, StudioHqBuilder.LANTERN);
	}

	/** A sign where the spur meets the promenade, naming the hub. */
	private static void hubSign(Plan p, HubRegistry.Hub hub, int sx, List<BlockPos> signs, List<String[]> texts) {
		int x = sx + 4;
		int z = PROM_Z - 3;
		p.set(x, GROUND + 1, z, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0));
		signs.add(new BlockPos(x, GROUND + 1, z));
		texts.add(new String[] {"", hub.name(), Theme.byId(hub.theme()).name, ""});
	}

	// ------------------------------------------------------------------ courtyard

	private static void courtyard(Plan p, int sx, List<HubRegistry.Hub> hubs, List<BlockPos> signs, List<String[]> texts) {
		int cx = sx;
		int x0 = cx - 13;
		int x1 = cx + 13;
		int cz = (YARD_Z0 + YARD_Z1) / 2;
		// floor: smooth stone in a stone-brick border, cleared above
		for (int x = x0; x <= x1; x++) {
			for (int z = YARD_Z0; z <= YARD_Z1; z++) {
				boolean border = x == x0 || x == x1 || z == YARD_Z0 || z == YARD_Z1;
				p.set(x, GROUND, z, border ? STONE : PLAZA);
				for (int y = GROUND + 1; y <= GROUND + 6; y++) {
					p.set(x, y, z, StudioHqBuilder.AIR);
				}
			}
		}
		// the way in from the promenade
		for (int z = PROM_Z + 3; z < YARD_Z0; z++) {
			for (int x = cx - 2; x <= cx + 2; x++) {
				p.set(x, GROUND, z, STONE);
				for (int y = GROUND + 1; y <= GROUND + 4; y++) {
					p.set(x, y, z, StudioHqBuilder.AIR);
				}
			}
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
		// a small tree in each corner
		for (int tx : new int[] {x0 + 3, x1 - 3}) {
			for (int tz : new int[] {YARD_Z0 + 3, YARD_Z1 - 6}) {
				tree(p, tx, tz);
			}
		}
		// lanterns on posts at the four corners of the plaza
		for (int tx : new int[] {x0 + 1, x1 - 1}) {
			for (int tz : new int[] {YARD_Z0 + 1, YARD_Z1 - 1}) {
				post(p, tx, tz);
			}
		}
		// the hub board along the south edge: a stone wall with a sign per hub
		int bz = YARD_Z1 - 1;
		int perRow = 9;
		int rows = (hubs.size() + perRow - 1) / perRow;
		int bw = Math.min(hubs.size(), perRow);
		int bx0 = cx - bw / 2;
		for (int x = bx0 - 1; x <= bx0 + bw; x++) {
			for (int y = GROUND + 1; y <= GROUND + 1 + rows + 1; y++) {
				p.set(x, y, bz, STONE);
			}
			p.set(x, GROUND + 2 + rows + 1, bz, Blocks.STONE_BRICK_SLAB.defaultBlockState());
		}
		HubRegistry.Hub here = hubs.getFirst();
		for (int i = 0; i < hubs.size(); i++) {
			HubRegistry.Hub h = hubs.get(i);
			int x = bx0 + i % perRow;
			int y = GROUND + 1 + rows - i / perRow;
			BlockPos pos = new BlockPos(x, y, bz - 1);
			p.set(x, y, bz - 1, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.NORTH));
			signs.add(pos);
			int dx = h.originX() - here.originX();
			String where = dx == 0 ? "right here" : (dx > 0 ? "east " : "west ") + Math.abs(dx) + " blocks";
			texts.add(new String[] {h.name(), Theme.byId(h.theme()).name, where, "H to travel"});
		}
	}

	private static void tree(Plan p, int x, int z) {
		p.set(x, GROUND, z, Blocks.GRASS_BLOCK.defaultBlockState());
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

	private static void writeSign(ServerLevel level, BlockPos pos, String[] lines) {
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
