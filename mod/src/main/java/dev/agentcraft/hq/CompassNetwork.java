package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.layout.HubRegistry;
import dev.agentcraft.layout.Placement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The compass town's ground: a road ring hugging the fronts of the eight ring-1 studios, the plaza
 * inside it (fountain, garden beds, benches and the hub wall), and once a ring-2 hub exists, an outer
 * road ring between the two rings of studios with eight spokes between the ring-1 studios. Its
 * measurements follow the world's spacing ({@link Geo}).
 *
 * <p>Built in pieces that never overlap a studio's grounds, each a sparse {@link Plan} with its own
 * record ({@code net-c-<piece>}), so rebuilds are diffs that keep the player's changes and a first
 * build only writes the network's own cells.
 */
final class CompassNetwork {
	static final int GROUND = StudioHqBuilder.GROUND;
	static final int Y0 = GROUND - 4;
	static final int Y1 = GROUND + 12;
	/**
	 * The town's measurements at one spacing {@code s} (distances from the centre unless named
	 * otherwise). Towns at {@link HubRegistry#LINE_SPACING} keep their first shape (27-wide roads,
	 * spokes at s / 2) so a rebuild there changes nothing; tighter towns have 15-wide roads, and the
	 * spokes between a corner studio and the side studio next to it sit halfway across that gap.
	 *
	 * @param w        road width
	 * @param inOut    inner road ring, outer edge (hugging the ring-1 fronts at s - 54)
	 * @param inIn     inner road ring, inner edge (the plaza is inside it)
	 * @param reach    how far the ring-1 corner studios reach along the north and south roads
	 * @param outIn    outer road ring, inner edge (between ring 1 and the ring-2 fronts)
	 * @param spokeX   x offset of the spokes between a side's middle and corner studios
	 * @param spokeZ   z offset of the spokes between a corner studio and the east or west studio
	 * @param bed      offset of the plaza's garden beds on each axis
	 */
	record Geo(int s, int cx, int cz, int w, int inOut, int inIn, int reach, int outIn, int outOut, int spokeX, int spokeZ, int bed) {
		static Geo of(int s) {
			boolean first = s >= HubRegistry.LINE_SPACING;
			int w = first ? 27 : 15;
			int front = s - HubRegistry.STUDIO_BOX[3];
			// the outer ring runs between ring 1 and the ring-2 fronts (2s - 54): first towns centre it on the
			// side studios' backs (s + 36), tighter ones on the corner studios' flanks (s + 46), which reach further
			int ring1Out = first ? s - HubRegistry.STUDIO_BOX[1] : s + HubRegistry.STUDIO_BOX[2];
			int outMid = (ring1Out + 2 * s - HubRegistry.STUDIO_BOX[3]) / 2;
			int spokeZ = first ? s / 2 : (HubRegistry.STUDIO_BOX[2] + front) / 2;
			return new Geo(s, HubRegistry.CENTER_X, HubRegistry.centerZ(), w, front - 1, front - w, s - HubRegistry.STUDIO_BOX[0],
				outMid - w / 2, outMid + w / 2, s / 2, spokeZ, first ? 42 : 32);
		}
	}

	private static final BlockState STONE = Blocks.STONE_BRICKS.defaultBlockState();
	private static final BlockState MOSSY = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
	private static final BlockState EDGE = Blocks.POLISHED_ANDESITE.defaultBlockState();
	private static final BlockState BAND = Blocks.SMOOTH_STONE.defaultBlockState();
	private static final BlockState CHISELED = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
	private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();

	/** A rectangle of ground with its own record, drawn in world coordinates. */
	record Piece(String key, int x0, int z0, int x1, int z1, Consumer<Plan> draw) {
	}

	private CompassNetwork() {
	}

	static String build(ServerLevel level) {
		List<HubRegistry.Hub> hubs = new ArrayList<>(HubRegistry.all());
		hubs.removeIf(h -> Anchors.of(h.id()).isEmpty());
		hubs.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
		Geo g = Geo.of(HubRegistry.spacing());
		List<int[]> gates = gates(hubs);
		HubRegistry.Hub main = HubRegistry.get(HubRegistry.MAIN);
		Theme theme = Theme.byId(main == null ? null : main.theme());
		boolean ring2 = hubs.stream().anyMatch(h -> {
			HubRegistry.Spot s = HubRegistry.spotAt(h.x(), h.z(), h.facing());
			return s != null && s.ring() == 2;
		});
		List<Piece> pieces = new ArrayList<>();
		// ---- ring 1: four road strips along the studio fronts, the plaza inside
		pieces.add(new Piece("n", g.cx() - g.reach(), g.cz() - g.inOut(), g.cx() + g.reach(), g.cz() - g.inIn(),
			p -> road(p, g.cx() - g.reach(), g.cz() - g.inOut(), g.cx() + g.reach(), g.cz() - g.inIn(), true, gates, g.w())));
		pieces.add(new Piece("s", g.cx() - g.reach(), g.cz() + g.inIn(), g.cx() + g.reach(), g.cz() + g.inOut(),
			p -> road(p, g.cx() - g.reach(), g.cz() + g.inIn(), g.cx() + g.reach(), g.cz() + g.inOut(), true, gates, g.w())));
		pieces.add(new Piece("w", g.cx() - g.inOut(), g.cz() - g.inIn() + 1, g.cx() - g.inIn(), g.cz() + g.inIn() - 1,
			p -> road(p, g.cx() - g.inOut(), g.cz() - g.inIn() + 1, g.cx() - g.inIn(), g.cz() + g.inIn() - 1, false, gates, g.w())));
		pieces.add(new Piece("e", g.cx() + g.inIn(), g.cz() - g.inIn() + 1, g.cx() + g.inOut(), g.cz() + g.inIn() - 1,
			p -> road(p, g.cx() + g.inIn(), g.cz() - g.inIn() + 1, g.cx() + g.inOut(), g.cz() + g.inIn() - 1, false, gates, g.w())));
		int pz0 = g.cz() - g.inIn() + 1, pz1 = g.cz() + g.inIn() - 1, px0 = g.cx() - g.inIn() + 1, px1 = g.cx() + g.inIn() - 1;
		pieces.add(new Piece("plaza", px0, pz0, px1, pz1, p -> plaza(p, g, px0, pz0, px1, pz1, hubs.size())));
		// ---- ring 2: the outer ring and the spokes between the ring-1 studios
		if (ring2) {
			pieces.add(new Piece("on", g.cx() - g.outOut(), g.cz() - g.outOut(), g.cx() + g.outOut(), g.cz() - g.outIn(),
				p -> road(p, g.cx() - g.outOut(), g.cz() - g.outOut(), g.cx() + g.outOut(), g.cz() - g.outIn(), true, gates, g.w())));
			pieces.add(new Piece("os", g.cx() - g.outOut(), g.cz() + g.outIn(), g.cx() + g.outOut(), g.cz() + g.outOut(),
				p -> road(p, g.cx() - g.outOut(), g.cz() + g.outIn(), g.cx() + g.outOut(), g.cz() + g.outOut(), true, gates, g.w())));
			pieces.add(new Piece("ow", g.cx() - g.outOut(), g.cz() - g.outIn() + 1, g.cx() - g.outIn(), g.cz() + g.outIn() - 1,
				p -> road(p, g.cx() - g.outOut(), g.cz() - g.outIn() + 1, g.cx() - g.outIn(), g.cz() + g.outIn() - 1, false, gates, g.w())));
			pieces.add(new Piece("oe", g.cx() + g.outIn(), g.cz() - g.outIn() + 1, g.cx() + g.outOut(), g.cz() + g.outIn() - 1,
				p -> road(p, g.cx() + g.outIn(), g.cz() - g.outIn() + 1, g.cx() + g.outOut(), g.cz() + g.outIn() - 1, false, gates, g.w())));
			// spokes between neighbouring ring-1 studios
			int half = g.w() / 2;
			for (int sgn : new int[] {-1, 1}) {
				int sx = g.cx() + sgn * g.spokeX();
				pieces.add(new Piece("sn" + (sgn > 0 ? "e" : "w"), sx - half, g.cz() - g.outIn() + 1, sx + half, g.cz() - g.inOut() - 1,
					p -> road(p, sx - half, g.cz() - g.outIn() + 1, sx + half, g.cz() - g.inOut() - 1, false, gates, g.w())));
				pieces.add(new Piece("ss" + (sgn > 0 ? "e" : "w"), sx - half, g.cz() + g.inOut() + 1, sx + half, g.cz() + g.outIn() - 1,
					p -> road(p, sx - half, g.cz() + g.inOut() + 1, sx + half, g.cz() + g.outIn() - 1, false, gates, g.w())));
				int sz = g.cz() + sgn * g.spokeZ();
				pieces.add(new Piece("se" + (sgn > 0 ? "s" : "n"), g.cx() + g.inOut() + 1, sz - half, g.cx() + g.outIn() - 1, sz + half,
					p -> road(p, g.cx() + g.inOut() + 1, sz - half, g.cx() + g.outIn() - 1, sz + half, true, gates, g.w())));
				pieces.add(new Piece("sw" + (sgn > 0 ? "s" : "n"), g.cx() - g.outIn() + 1, sz - half, g.cx() - g.inOut() - 1, sz + half,
					p -> road(p, g.cx() - g.outIn() + 1, sz - half, g.cx() - g.inOut() - 1, sz + half, true, gates, g.w())));
			}
		}
		int changed = 0;
		for (Piece pc : pieces) {
			changed += buildPiece(level, pc, theme);
		}
		String report = String.format(Locale.ROOT, "compass town for %d hub%s%s: %d blocks updated", hubs.size(), hubs.size() == 1 ? "" : "s",
			ring2 ? " (two rings)" : "", changed);
		AgentCraft.LOGGER.info("Hub network: {}", report);
		return report;
	}

	private static int buildPiece(ServerLevel level, Piece pc, Theme theme) {
		int[] box = {pc.x0(), Y0, pc.z0(), pc.x1(), Y1, pc.z1()};
		Plan p = meadow(box);
		p.theme(theme);
		p.sparse(true);
		pc.draw().accept(p);
		String key = "net-c-" + pc.key();
		var previous = PlanStore.load(level.getServer(), key, NetworkBuilder.ID, box, p.size());
		Plan.Stats st = p.apply(level, previous, false);
		PlanStore.save(level.getServer(), key, NetworkBuilder.ID, box, p.cells());
		return st.changed() + st.connected();
	}

	static Plan meadow(int[] box) {
		return new Plan(box[0], box[1], box[2], box[3], box[4], box[5], GROUND, y -> y > GROUND ? StudioHqBuilder.AIR
			: y == GROUND ? GRASS : y >= GROUND - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
	}

	/** Where each hub's path leaves its grounds (world x, z): no posts or trees go there. */
	private static List<int[]> gates(List<HubRegistry.Hub> hubs) {
		List<int[]> out = new ArrayList<>();
		for (HubRegistry.Hub h : hubs) {
			Placement pl = h.placement();
			out.add(new int[] {pl.worldX(NetworkBuilder.SPUR_X, HubRegistry.STUDIO_BOX[3]), pl.worldZ(NetworkBuilder.SPUR_X, HubRegistry.STUDIO_BOX[3])});
		}
		return out;
	}

	private static boolean nearGate(List<int[]> gates, int x, int z, int r, int w) {
		for (int[] g : gates) {
			if (Math.abs(g[0] - x) <= r && Math.abs(g[1] - z) <= r + w) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A stone road on the rectangle, running east-west ({@code alongX}) or north-south: polished
	 * edges, a smooth centre line, cross bands every 9, lantern posts along both edges every 16
	 * (none in front of a studio's gate).
	 */
	private static void road(Plan p, int x0, int z0, int x1, int z1, boolean alongX, List<int[]> gates, int w) {
		int mid = alongX ? (z0 + z1) / 2 : (x0 + x1) / 2;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				int across = alongX ? z : x;
				int along = alongX ? x : z;
				int lo = alongX ? z0 : x0, hi = alongX ? z1 : x1;
				BlockState s;
				if (across == lo || across == hi) {
					s = EDGE;
				} else if (across == mid || Math.floorMod(along, 9) == 0) {
					s = BAND;
				} else {
					s = Math.floorMod(x * 7 + z * 3, 13) == 0 ? MOSSY : STONE;
				}
				p.set(x, GROUND, z, s);
				for (int y = GROUND + 1; y <= GROUND + 5; y++) {
					p.set(x, y, z, StudioHqBuilder.AIR);
				}
			}
		}
		for (int a = (alongX ? x0 : z0) + 4; a <= (alongX ? x1 : z1) - 4; a += 16) {
			for (int edge : new int[] {(alongX ? z0 : x0) + 1, (alongX ? z1 : x1) - 1}) {
				int px = alongX ? a : edge, pz = alongX ? edge : a;
				if (!nearGate(gates, px, pz, 5, w)) {
					NetworkBuilder.post(p, px, pz);
				}
			}
		}
	}

	/**
	 * The plaza: smooth stone in a stone-brick border, a large fountain in the middle, four garden beds
	 * with trees and benches facing the fountain, and the hub wall south of the fountain looking north
	 * at it.
	 */
	private static void plaza(Plan p, Geo g, int x0, int z0, int x1, int z1, int hubCount) {
		int cx = g.cx(), cz = g.cz();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean border = x == x0 || x == x1 || z == z0 || z == z1;
				boolean grid = Math.floorMod(x - cx, 13) == 0 || Math.floorMod(z - cz, 13) == 0;
				p.set(x, GROUND, z, border ? STONE : grid ? EDGE : BAND);
				for (int y = GROUND + 1; y <= GROUND + 7; y++) {
					p.set(x, y, z, StudioHqBuilder.AIR);
				}
			}
		}
		// fountain: an 11 x 11 basin, a raised 5 x 5 inner basin, a chiseled column with a lantern
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				boolean rim = Math.abs(dx) == 5 || Math.abs(dz) == 5;
				p.set(cx + dx, GROUND, cz + dz, STONE);
				p.set(cx + dx, GROUND + 1, cz + dz, rim ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : WATER);
			}
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				p.set(cx + dx, GROUND + 1, cz + dz, STONE);
				p.set(cx + dx, GROUND + 2, cz + dz, rim ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : WATER);
			}
		}
		for (int y = GROUND + 2; y <= GROUND + 4; y++) {
			p.set(cx, y, cz, CHISELED);
		}
		p.set(cx, GROUND + 5, cz, StudioHqBuilder.LANTERN);
		// benches around the fountain
		for (int d = -3; d <= 3; d++) {
			if (Math.abs(d) <= 1) {
				continue; // a gap in the middle of each side
			}
			p.set(cx + d, GROUND + 1, cz - 8, St.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, false));
			p.set(cx + d, GROUND + 1, cz + 8, St.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
			p.set(cx - 8, GROUND + 1, cz + d, St.stairs(Blocks.SPRUCE_STAIRS, Direction.WEST, false));
			p.set(cx + 8, GROUND + 1, cz + d, St.stairs(Blocks.SPRUCE_STAIRS, Direction.EAST, false));
		}
		// four garden beds on the diagonals, each with trees, a hedge and benches facing the fountain
		for (int sx : new int[] {-1, 1}) {
			for (int sz : new int[] {-1, 1}) {
				int bx = cx + sx * g.bed(), bz = cz + sz * g.bed();
				for (int dx = -10; dx <= 10; dx++) {
					for (int dz = -10; dz <= 10; dz++) {
						boolean edge = Math.abs(dx) == 10 || Math.abs(dz) == 10;
						p.set(bx + dx, GROUND, bz + dz, GRASS);
						if (edge && !(Math.abs(dx) <= 1 || Math.abs(dz) <= 1)) {
							p.set(bx + dx, GROUND + 1, bz + dz, St.leaves(Blocks.AZALEA_LEAVES));
						}
					}
				}
				for (int[] t : new int[][] {{-5, -5}, {5, -5}, {-5, 5}, {5, 5}}) {
					NetworkBuilder.tree(p, bx + t[0], bz + t[1]);
				}
				NetworkBuilder.post(p, bx, bz);
				// a bench on the bed's side facing the fountain
				for (int d = -2; d <= 2; d++) {
					if (Math.abs(d) <= 0) {
						continue;
					}
					p.set(bx + d, GROUND + 1, bz - sz * 12, St.stairs(Blocks.SPRUCE_STAIRS, sz > 0 ? Direction.SOUTH : Direction.NORTH, false));
				}
			}
		}
		// lantern posts round the plaza's edge
		for (int a = x0 + 6; a <= x1 - 6; a += 13) {
			NetworkBuilder.post(p, a, z0 + 1);
			NetworkBuilder.post(p, a, z1 - 1);
		}
		for (int a = z0 + 6; a <= z1 - 6; a += 13) {
			NetworkBuilder.post(p, x0 + 1, a);
			NetworkBuilder.post(p, x1 - 1, a);
		}
		// the hub wall, between the fountain and the south road, looking north at the fountain
		NetworkBuilder.hubWall(p, cx, cz + 22, Math.max(1, hubCount));
		// help boards on the other three sides, facing the fountain: keys, console, hubs and town
		kiosk(p, cx, cz - 22, Direction.SOUTH, 13, 4, "help:keys");
		kiosk(p, cx - 22, cz, Direction.EAST, 13, 4, "help:console");
		kiosk(p, cx + 22, cz, Direction.WEST, 13, 4, "help:hubs");
	}

	/**
	 * A free-standing board {@code w} x {@code h} blocks centred on (cx, cz), facing {@code facing},
	 * bound to {@code binding} (the client draws it), in a stone-brick frame with a slab hood, a solid
	 * back and a lantern post either side.
	 */
	static void kiosk(Plan p, int cx, int cz, Direction facing, int w, int h, String binding) {
		Direction along = facing.getClockWise();
		int ax = along.getStepX(), az = along.getStepZ();
		int bxo = -facing.getStepX(), bzo = -facing.getStepZ();
		int sx = cx - ax * (w / 2), sz = cz - az * (w / 2);
		int y0 = GROUND + 2, y1 = y0 + h - 1;
		BlockState board = dev.agentcraft.block.ModBlocks.TASK_BOARD.defaultBlockState().setValue(dev.agentcraft.block.PanelBlock.FACING, facing);
		for (int i = -1; i <= w; i++) {
			int x = sx + ax * i, z = sz + az * i;
			for (int y = GROUND + 1; y <= y1 + 1; y++) {
				p.set(x + bxo, y, z + bzo, STONE);
				boolean frame = i == -1 || i == w || y == GROUND + 1 || y == y1 + 1;
				if (frame) {
					p.set(x, y, z, y == GROUND + 1 ? CHISELED : STONE);
				}
			}
			p.set(x, y1 + 2, z, St.slab(Blocks.STONE_BRICK_SLAB, false));
			p.set(x + bxo, y1 + 2, z + bzo, STONE);
		}
		for (int i = 0; i < w; i++) {
			int x = sx + ax * i, z = sz + az * i;
			for (int y = y0; y <= y1; y++) {
				p.set(x, y, z, board);
				p.bind(x, y, z, binding);
			}
		}
		NetworkBuilder.post(p, sx - ax * 3, sz - az * 3);
		NetworkBuilder.post(p, sx + ax * (w + 2), sz + az * (w + 2));
	}

	/** Every piece's record key, as {@link #build} names them. */
	private static final List<String> PIECES = List.of("n", "s", "w", "e", "plaza", "on", "os", "ow", "oe",
		"sne", "snw", "sse", "ssw", "ses", "sen", "sws", "swn");

	/** Clears the whole network (roads, plaza and spokes) through its records. Server thread. */
	static int eraseAll(ServerLevel level) {
		int n = 0;
		for (String k : PIECES) {
			n += erase(level, "net-c-" + k, Placement.IDENTITY);
		}
		return n;
	}

	/**
	 * Clears a piece of ground a record describes (an old network segment, or a studio that moved):
	 * every cell still as that build left it goes back to meadow, cells the player changed are kept,
	 * and the record is forgotten. Server thread.
	 */
	static int erase(ServerLevel level, String key, Placement at) {
		Object[] stored = PlanStore.stored(level.getServer(), key);
		if (stored == null) {
			return 0;
		}
		int[] box = (int[]) stored[0];
		String builder = (String) stored[1];
		if (box.length != 6) {
			PlanStore.delete(level.getServer(), key);
			return 0;
		}
		Plan p = meadow(box);
		var previous = PlanStore.load(level.getServer(), key, builder, box, p.size());
		Plan.Stats st = p.apply(level, previous, false, at);
		PlanStore.delete(level.getServer(), key);
		return st.changed() + st.connected();
	}
}
