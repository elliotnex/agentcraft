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
 * road ring between the two rings of studios with eight spokes between the ring-1 studios.
 *
 * <p>Built in pieces that never overlap a studio's grounds, each a sparse {@link Plan} with its own
 * record ({@code net-c-<piece>}), so rebuilds are diffs that keep the player's changes and a first
 * build only writes the network's own cells.
 */
final class CompassNetwork {
	static final int GROUND = StudioHqBuilder.GROUND;
	static final int Y0 = GROUND - 4;
	static final int Y1 = GROUND + 12;
	static final int CX = HubRegistry.CENTER_X;
	static final int CZ = HubRegistry.CENTER_Z;
	static final int S = HubRegistry.SPACING;
	/** Road width. */
	static final int W = 27;
	/** The ring-1 studios' fronts are this far from the centre (160 - 54). */
	static final int FRONT = S - HubRegistry.STUDIO_BOX[3];
	/** Inner road ring: the band FRONT - W + 1 .. FRONT from the centre (hugging the fronts). */
	static final int IN_OUT = FRONT - 1;          // 105
	static final int IN_IN = FRONT - W;           // 79
	/** The ring-1 corner studios reach this far out along the north and south roads. */
	static final int CORNER_REACH = S - HubRegistry.STUDIO_BOX[0];  // 206
	/** Outer road ring between ring-1 backs (196) and ring-2 fronts (266). */
	static final int OUT_MID = (S - HubRegistry.STUDIO_BOX[1] + 2 * S - HubRegistry.STUDIO_BOX[3]) / 2; // 231
	static final int OUT_IN = OUT_MID - W / 2;    // 218
	static final int OUT_OUT = OUT_MID + W / 2;   // 244

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
		List<int[]> gates = gates(hubs);
		HubRegistry.Hub main = HubRegistry.get(HubRegistry.MAIN);
		Theme theme = Theme.byId(main == null ? null : main.theme());
		boolean ring2 = hubs.stream().anyMatch(h -> {
			HubRegistry.Spot s = HubRegistry.spotAt(h.x(), h.z(), h.facing());
			return s != null && s.ring() == 2;
		});
		List<Piece> pieces = new ArrayList<>();
		// ---- ring 1: four road strips along the studio fronts, the plaza inside
		pieces.add(new Piece("n", CX - CORNER_REACH, CZ - IN_OUT, CX + CORNER_REACH, CZ - IN_IN,
			p -> road(p, CX - CORNER_REACH, CZ - IN_OUT, CX + CORNER_REACH, CZ - IN_IN, true, gates)));
		pieces.add(new Piece("s", CX - CORNER_REACH, CZ + IN_IN, CX + CORNER_REACH, CZ + IN_OUT,
			p -> road(p, CX - CORNER_REACH, CZ + IN_IN, CX + CORNER_REACH, CZ + IN_OUT, true, gates)));
		pieces.add(new Piece("w", CX - IN_OUT, CZ - IN_IN + 1, CX - IN_IN, CZ + IN_IN - 1,
			p -> road(p, CX - IN_OUT, CZ - IN_IN + 1, CX - IN_IN, CZ + IN_IN - 1, false, gates)));
		pieces.add(new Piece("e", CX + IN_IN, CZ - IN_IN + 1, CX + IN_OUT, CZ + IN_IN - 1,
			p -> road(p, CX + IN_IN, CZ - IN_IN + 1, CX + IN_OUT, CZ + IN_IN - 1, false, gates)));
		int pz0 = CZ - IN_IN + 1, pz1 = CZ + IN_IN - 1, px0 = CX - IN_IN + 1, px1 = CX + IN_IN - 1;
		pieces.add(new Piece("plaza", px0, pz0, px1, pz1, p -> plaza(p, px0, pz0, px1, pz1, hubs.size())));
		// ---- ring 2: the outer ring and the spokes between the ring-1 studios
		if (ring2) {
			pieces.add(new Piece("on", CX - OUT_OUT, CZ - OUT_OUT, CX + OUT_OUT, CZ - OUT_IN,
				p -> road(p, CX - OUT_OUT, CZ - OUT_OUT, CX + OUT_OUT, CZ - OUT_IN, true, gates)));
			pieces.add(new Piece("os", CX - OUT_OUT, CZ + OUT_IN, CX + OUT_OUT, CZ + OUT_OUT,
				p -> road(p, CX - OUT_OUT, CZ + OUT_IN, CX + OUT_OUT, CZ + OUT_OUT, true, gates)));
			pieces.add(new Piece("ow", CX - OUT_OUT, CZ - OUT_IN + 1, CX - OUT_IN, CZ + OUT_IN - 1,
				p -> road(p, CX - OUT_OUT, CZ - OUT_IN + 1, CX - OUT_IN, CZ + OUT_IN - 1, false, gates)));
			pieces.add(new Piece("oe", CX + OUT_IN, CZ - OUT_IN + 1, CX + OUT_OUT, CZ + OUT_IN - 1,
				p -> road(p, CX + OUT_IN, CZ - OUT_IN + 1, CX + OUT_OUT, CZ + OUT_IN - 1, false, gates)));
			// spokes, centred halfway between neighbouring ring-1 studios (80 from the centre lines)
			int half = W / 2;
			for (int sgn : new int[] {-1, 1}) {
				int sx = CX + sgn * S / 2;
				pieces.add(new Piece("sn" + (sgn > 0 ? "e" : "w"), sx - half, CZ - OUT_IN + 1, sx + half, CZ - IN_OUT - 1,
					p -> road(p, sx - half, CZ - OUT_IN + 1, sx + half, CZ - IN_OUT - 1, false, gates)));
				pieces.add(new Piece("ss" + (sgn > 0 ? "e" : "w"), sx - half, CZ + IN_OUT + 1, sx + half, CZ + OUT_IN - 1,
					p -> road(p, sx - half, CZ + IN_OUT + 1, sx + half, CZ + OUT_IN - 1, false, gates)));
				int sz = CZ + sgn * S / 2;
				pieces.add(new Piece("se" + (sgn > 0 ? "s" : "n"), CX + IN_OUT + 1, sz - half, CX + OUT_IN - 1, sz + half,
					p -> road(p, CX + IN_OUT + 1, sz - half, CX + OUT_IN - 1, sz + half, true, gates)));
				pieces.add(new Piece("sw" + (sgn > 0 ? "s" : "n"), CX - OUT_IN + 1, sz - half, CX - IN_OUT - 1, sz + half,
					p -> road(p, CX - OUT_IN + 1, sz - half, CX - IN_OUT - 1, sz + half, true, gates)));
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

	private static boolean nearGate(List<int[]> gates, int x, int z, int r) {
		for (int[] g : gates) {
			if (Math.abs(g[0] - x) <= r && Math.abs(g[1] - z) <= r + W) {
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
	private static void road(Plan p, int x0, int z0, int x1, int z1, boolean alongX, List<int[]> gates) {
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
				if (!nearGate(gates, px, pz, 5)) {
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
	private static void plaza(Plan p, int x0, int z0, int x1, int z1, int hubCount) {
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean border = x == x0 || x == x1 || z == z0 || z == z1;
				boolean grid = Math.floorMod(x - CX, 13) == 0 || Math.floorMod(z - CZ, 13) == 0;
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
				p.set(CX + dx, GROUND, CZ + dz, STONE);
				p.set(CX + dx, GROUND + 1, CZ + dz, rim ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : WATER);
			}
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				p.set(CX + dx, GROUND + 1, CZ + dz, STONE);
				p.set(CX + dx, GROUND + 2, CZ + dz, rim ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : WATER);
			}
		}
		for (int y = GROUND + 2; y <= GROUND + 4; y++) {
			p.set(CX, y, CZ, CHISELED);
		}
		p.set(CX, GROUND + 5, CZ, StudioHqBuilder.LANTERN);
		// benches around the fountain
		for (int d = -3; d <= 3; d++) {
			if (Math.abs(d) <= 1) {
				continue; // a gap in the middle of each side
			}
			p.set(CX + d, GROUND + 1, CZ - 8, St.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, false));
			p.set(CX + d, GROUND + 1, CZ + 8, St.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
			p.set(CX - 8, GROUND + 1, CZ + d, St.stairs(Blocks.SPRUCE_STAIRS, Direction.WEST, false));
			p.set(CX + 8, GROUND + 1, CZ + d, St.stairs(Blocks.SPRUCE_STAIRS, Direction.EAST, false));
		}
		// four garden beds on the diagonals, each with trees, a hedge and benches facing the fountain
		for (int sx : new int[] {-1, 1}) {
			for (int sz : new int[] {-1, 1}) {
				int bx = CX + sx * 42, bz = CZ + sz * 42;
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
		NetworkBuilder.hubWall(p, CX, CZ + 22, Math.max(1, hubCount));
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
