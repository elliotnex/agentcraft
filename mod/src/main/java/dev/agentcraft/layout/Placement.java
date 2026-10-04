package dev.agentcraft.layout;

import net.minecraft.world.level.block.Rotation;

/**
 * Where a studio stands and which way it faces. Builders work in studio coordinates, gate facing
 * south (+z); a placement moves them to the hub's origin and turns them a quarter turn at a time so
 * the gate faces {@code facing}:
 * <pre>
 *   S: (x, z) -> ( x,  z)    W: (x, z) -> (-z,  x)    (clockwise 90: south -> west)
 *   N: (x, z) -> (-x, -z)    E: (x, z) -> ( z, -x)    (counter-clockwise 90: south -> east)
 * </pre>
 * Block cells map to block cells (a cell is the unit square at its integer corner), so a turned
 * studio stays on the block grid; continuous points (anchors at x + 0.5) turn around the same cells.
 */
public record Placement(int ox, int oz, char facing) {
	public static final Placement IDENTITY = new Placement(0, 0, 'S');

	public Placement {
		if ("SWNE".indexOf(facing) < 0) {
			throw new IllegalArgumentException("facing must be S, W, N or E (got " + facing + ")");
		}
	}

	/** Clockwise quarter turns: S 0, W 1, N 2, E 3. */
	public int turns() {
		return "SWNE".indexOf(facing);
	}

	public Rotation rotation() {
		return switch (turns()) {
			case 1 -> Rotation.CLOCKWISE_90;
			case 2 -> Rotation.CLOCKWISE_180;
			case 3 -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	/** World x of studio cell (x, z). */
	public int worldX(int x, int z) {
		return ox + switch (turns()) {
			case 1 -> -z;
			case 2 -> -x;
			case 3 -> z;
			default -> x;
		};
	}

	/** World z of studio cell (x, z). */
	public int worldZ(int x, int z) {
		return oz + switch (turns()) {
			case 1 -> x;
			case 2 -> -z;
			case 3 -> -x;
			default -> z;
		};
	}

	/** Studio x of world cell (wx, wz) (the inverse turn). */
	public int localX(int wx, int wz) {
		int dx = wx - ox, dz = wz - oz;
		return switch (turns()) {
			case 1 -> dz;
			case 2 -> -dx;
			case 3 -> -dz;
			default -> dx;
		};
	}

	/** Studio z of world cell (wx, wz). */
	public int localZ(int wx, int wz) {
		int dx = wx - ox, dz = wz - oz;
		return switch (turns()) {
			case 1 -> -dx;
			case 2 -> -dz;
			case 3 -> dx;
			default -> dz;
		};
	}

	/** World x of a continuous studio point. */
	public double worldX(double x, double z) {
		return ox + switch (turns()) {
			case 1 -> 1 - z;
			case 2 -> 1 - x;
			case 3 -> z;
			default -> x;
		};
	}

	/** World z of a continuous studio point. */
	public double worldZ(double x, double z) {
		return oz + switch (turns()) {
			case 1 -> x;
			case 2 -> 1 - z;
			case 3 -> 1 - x;
			default -> z;
		};
	}

	/** A studio yaw turned with the studio (yaw 0 looks south, 90 west). */
	public float yaw(float yaw) {
		float y = yaw + 90f * turns();
		while (y > 180f) {
			y -= 360f;
		}
		return y;
	}

	/** The world box {minX, minZ, maxX, maxZ} of the studio box x0..x1, z0..z1 (inclusive cells). */
	public int[] box(int x0, int z0, int x1, int z1) {
		int ax = worldX(x0, z0), az = worldZ(x0, z0), bx = worldX(x1, z1), bz = worldZ(x1, z1);
		return new int[] {Math.min(ax, bx), Math.min(az, bz), Math.max(ax, bx), Math.max(az, bz)};
	}
}
