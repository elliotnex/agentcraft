package dev.agentcraft.hq;

import dev.agentcraft.block.FacingEntityBlock;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The freelancer's pavilion in the compass plaza, beside the fountain (north-east of it): an open
 * 9 x 9 gazebo with Scout's desk and monitor against the north wall, a console terminal, a help board
 * on the east wall and an amethyst crown on the roof. It is the studio of the {@link #HUB} hub, whose
 * Foreman runs the open backend (Scout on any model): it publishes that hub's anchors, so standing in
 * it makes the freelancer the active hub (HUD, console, decisions, monitor) and Scout walks between its
 * spots like any agent in a studio.
 */
public final class FreelancePavilion {
	/** The hub (and Foreman profile) of the freelancer. */
	public static final String HUB = "freelance";
	public static final String AGENT = "scout";
	static final int GROUND = StudioHqBuilder.GROUND;
	static final int FEET = GROUND + 1;
	/** North-west corner offset from the plaza centre, and the size. */
	static final int DX = 11, DZ = -19, SIZE = 9;

	private static final BlockState PLANKS = Blocks.DARK_OAK_PLANKS.defaultBlockState();
	private static final BlockState PILLAR = St.log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y);

	private FreelancePavilion() {
	}

	/** Draws the pavilion into the plaza's plan (world coordinates; plaza centre cx, cz). */
	static void draw(Plan p, int cx, int cz) {
		int x0 = cx + DX, z0 = cz + DZ, x1 = x0 + SIZE - 1, z1 = z0 + SIZE - 1;
		int px = x0 + SIZE / 2;
		// floor: deepslate tiles round polished deepslate, a step of slabs on the open sides
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean border = x == x0 || x == x1 || z == z0 || z == z1;
				p.set(x, GROUND, z, (border ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_DEEPSLATE).defaultBlockState());
				for (int y = FEET; y <= GROUND + 7; y++) {
					p.set(x, y, z, StudioHqBuilder.AIR);
				}
			}
		}
		// corner pillars
		for (int[] c : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
			for (int y = FEET; y <= GROUND + 4; y++) {
				p.set(c[0], y, c[1], PILLAR);
			}
		}
		// the north wall (the desk's back) and the east wall (the help board's back)
		for (int y = FEET; y <= GROUND + 4; y++) {
			for (int x = x0 + 1; x <= x1 - 1; x++) {
				p.set(x, y, z0, PLANKS);
			}
			for (int z = z0 + 1; z <= z1 - 1; z++) {
				p.set(x1, y, z, PLANKS);
			}
		}
		// Scout's desk: a slab desk with a 3-wide monitor bound to Scout, a status lamp in the wall above,
		// the chair in front, and the console terminal at the desk's east end
		for (int x = px - 1; x <= px + 1; x++) {
			p.set(x, FEET, z0 + 1, St.slab(Blocks.DARK_OAK_SLAB, true));
			for (int y = FEET + 1; y <= FEET + 2; y++) {
				p.set(x, y, z0 + 1, ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, Direction.SOUTH).setValue(MonitorBlock.LIT, true));
				p.bind(x, y, z0 + 1, AGENT);
			}
		}
		p.set(px, FEET + 3, z0, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
		p.bind(px, FEET + 3, z0, "agent:" + AGENT);
		p.set(px, FEET, z0 + 2, St.stairs(Blocks.DARK_OAK_STAIRS, Direction.SOUTH, false));
		p.set(px + 2, FEET, z0 + 1, ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.SOUTH));
		p.set(px - 2, FEET, z0 + 1, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		// help board on the east wall, facing in
		BlockState board = ModBlocks.TASK_BOARD.defaultBlockState().setValue(PanelBlock.FACING, Direction.WEST);
		for (int z = z0 + 1; z <= z0 + 7; z++) {
			for (int y = FEET; y <= FEET + 2; y++) {
				p.set(x1 - 1, y, z, board);
				p.bind(x1 - 1, y, z, "help:freelance");
			}
		}
		// a bench on the west side, looking east at the board
		for (int z = z0 + 4; z <= z0 + 6; z++) {
			p.set(x0 + 1, FEET, z, St.stairs(Blocks.DARK_OAK_STAIRS, Direction.WEST, false));
		}
		// roof: planks rim with top slabs inside, a raised inner ring, an amethyst crown
		for (int x = x0 - 1; x <= x1 + 1; x++) {
			for (int z = z0 - 1; z <= z1 + 1; z++) {
				boolean rim = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1;
				p.set(x, GROUND + 5, z, rim ? St.stairs(Blocks.DARK_OAK_STAIRS, eave(x, z, x0 - 1, z0 - 1, x1 + 1, z1 + 1), false) : PLANKS);
			}
		}
		for (int x = x0 + 1; x <= x1 - 1; x++) {
			for (int z = z0 + 1; z <= z1 - 1; z++) {
				boolean rim = x == x0 + 1 || x == x1 - 1 || z == z0 + 1 || z == z1 - 1;
				p.set(x, GROUND + 6, z, rim ? St.slab(Blocks.DARK_OAK_SLAB, false) : PLANKS);
			}
		}
		p.set(px, GROUND + 7, z0 + SIZE / 2, Blocks.AMETHYST_BLOCK.defaultBlockState());
		p.set(px, GROUND + 8, z0 + SIZE / 2, Blocks.AMETHYST_CLUSTER.defaultBlockState());
		p.set(px, GROUND + 4, z0 + SIZE / 2, St.lantern(Blocks.LANTERN, true));
	}

	/** Eave stairs face away from the roof's middle (corners: along x). */
	private static Direction eave(int x, int z, int x0, int z0, int x1, int z1) {
		if (z == z0) {
			return Direction.SOUTH;
		}
		if (z == z1) {
			return Direction.NORTH;
		}
		return x == x0 ? Direction.EAST : Direction.WEST;
	}

	/** Publishes the freelance hub's anchors (plaza centre cx, cz). Server thread. */
	static void publish(MinecraftServer server, int cx, int cz) {
		int x0 = cx + DX, z0 = cz + DZ, x1 = x0 + SIZE - 1, z1 = z0 + SIZE - 1;
		int px = x0 + SIZE / 2;
		Anchors.Builder a = Anchors.builder("freelance-pavilion");
		// Scout: the chair at the desk, beside the desk at the terminal, the bench to wait
		a.put(AnchorNames.desk(AGENT), px + 0.5, FEET, z0 + 2.5, 180, 0);
		a.put("seat_" + AGENT, px + 0.5, FEET, z0 + 2.5, 180, 0);
		a.put(AnchorNames.monitor(AGENT), px + 0.5, FEET + 2.0, z0 + 1 + 0.25 + 0.002, 0, 0);
		a.put(AnchorNames.TERMINAL, px - 1.5, FEET, z0 + 2.5, 180, 0);
		a.put(AnchorNames.LIBRARY, px - 1.5, FEET, z0 + 2.5, 180, 0);
		a.put(AnchorNames.TESTBENCH, px - 1.5, FEET, z0 + 2.5, 180, 0);
		a.put(AnchorNames.MERGESTATION, px - 1.5, FEET, z0 + 2.5, 180, 0);
		a.put(AnchorNames.LOUNGE, x0 + 1.5, FEET, z0 + 5.5, -90, 0);
		a.put(AnchorNames.MEETING, px + 0.5, FEET, z0 + 5.5, 180, 0);
		// where Scout comes to talk to the player, and where the player arrives
		a.put(AnchorNames.USER, px + 0.5, FEET, z1 - 0.5, 0, 0);
		a.put(AnchorNames.ENTRANCE, px + 0.5, FEET, z1 + 1.5, 180, 0);
		a.put(AnchorNames.SPAWN, x0 - 1.5, FEET, z1 + 2.5, -135, 0);
		a.cameraLookAt("desk_" + AGENT, px + 2.0, FEET + 2.2, z0 + 4.8, px + 0.2, FEET + 1.8, z0 + 1.25);
		// standing anywhere in it, or where Go drops you, talks to the freelancer
		a.bounds(x0 - 3, GROUND, z0 - 1, x1 + 1, GROUND + 8, z1 + 4);
		Anchors.publish(server, HUB, a.build());
	}
}
