package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.HubRegistry;
import dev.agentcraft.layout.Placement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/**
 * Turns a line-layout world into a compass town ({@code /agentcraft hub layout compass}): the old
 * promenade segments are cleared through their records, every hub already on a compass spot stays,
 * the others move to the next free spots (old studio cleared, rebuilt at the new spot turned to face
 * the plaza), then the compass roads and plaza are built. Run in a compass town from before the
 * town shrank, it tightens it ({@link #tighten}). The player's own blocks are kept throughout:
 * clearing only touches cells still as a build left them.
 */
final class TownLayout {
	private TownLayout() {
	}

	static List<String> toCompass(ServerLevel level) {
		List<String> report = new ArrayList<>();
		if (HubRegistry.COMPASS.equals(HubRegistry.layout())) {
			if (HubRegistry.spacing() != HubRegistry.COMPASS_SPACING) {
				return tighten(level);
			}
			report.add("This world already uses the compass layout.");
			return report;
		}
		// 1. the line layout's promenade, spurs and courtyard
		int cleared = 0;
		for (HubRegistry.Hub h : HubRegistry.all()) {
			cleared += CompassNetwork.erase(level, "net-" + h.id(), Placement.IDENTITY);
		}
		report.add("Cleared the old promenade (" + cleared + " blocks).");
		// 2. hubs already on a compass spot stay; 3. the rest move to free spots (at the new spacing)
		HubRegistry.setSpacing(level.getServer(), HubRegistry.COMPASS_SPACING);
		List<HubRegistry.Hub> placed = new ArrayList<>();
		List<HubRegistry.Hub> toMove = new ArrayList<>();
		for (HubRegistry.Hub h : HubRegistry.all()) {
			(HubRegistry.spotAt(h.x(), h.z(), h.facing()) != null ? placed : toMove).add(h);
		}
		toMove.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
		for (HubRegistry.Hub h : toMove) {
			HubRegistry.Spot spot = HubRegistry.freeSpot(placed);
			if (spot == null) {
				report.add("No free spot for '" + h.id() + "': the town is full.");
				continue;
			}
			HubRegistry.Hub moved = move(level, h, spot);
			placed.add(moved);
			report.add("Moved '" + h.id() + "' to (" + spot.x() + ", " + spot.z() + "), facing " + facingName(spot.facing()) + ".");
		}
		// 4. the compass from now on; 5. its roads and plaza
		HubRegistry.setLayout(level.getServer(), HubRegistry.COMPASS);
		report.add(CompassNetwork.build(level));
		return report;
	}

	/**
	 * Pulls a compass town built at an older, wider spacing in to {@link HubRegistry#COMPASS_SPACING}:
	 * the roads and plaza are cleared, every hub keeps its place in the compass (the east studio stays
	 * east) at the new spacing, main never moves, then the network is built again. The new grounds
	 * overlap other studios' old ones, so every moving studio is cleared before any is rebuilt.
	 */
	static List<String> tighten(ServerLevel level) {
		List<String> report = new ArrayList<>();
		int from = HubRegistry.spacing();
		List<HubRegistry.Spot> before = HubRegistry.spotsFor(from);
		report.add("Cleared the old roads and plaza (" + CompassNetwork.eraseAll(level) + " blocks).");
		HubRegistry.setSpacing(level.getServer(), HubRegistry.COMPASS_SPACING);
		List<HubRegistry.Spot> after = HubRegistry.spots();
		// where each hub goes: the same spot at the new spacing; a hub off the old grid takes a free spot
		List<HubRegistry.Hub> placed = new ArrayList<>();
		List<HubRegistry.Hub> moving = new ArrayList<>();
		List<HubRegistry.Spot> targets = new ArrayList<>();
		List<HubRegistry.Hub> stray = new ArrayList<>();
		for (HubRegistry.Hub h : HubRegistry.all()) {
			int i = indexOf(before, h);
			HubRegistry.Spot to = i < 0 ? null : after.get(i);
			if (to == null) {
				stray.add(h);
			} else if (to.x() == h.x() && to.z() == h.z() && to.facing() == h.facing()) {
				placed.add(h);
			} else {
				moving.add(h);
				targets.add(to);
				placed.add(h.at(to.x(), to.z(), to.facing()));
			}
		}
		stray.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
		for (HubRegistry.Hub h : stray) {
			HubRegistry.Spot to = HubRegistry.freeSpot(placed);
			if (to == null) {
				report.add("No free spot for '" + h.id() + "': the town is full; it stays where it is.");
				continue;
			}
			moving.add(h);
			targets.add(to);
			placed.add(h.at(to.x(), to.z(), to.facing()));
		}
		int cleared = 0;
		for (HubRegistry.Hub h : moving) {
			cleared += CompassNetwork.erase(level, h.id(), h.placement());
		}
		report.add("Cleared " + moving.size() + " studio" + (moving.size() == 1 ? "" : "s") + " where they stood (" + cleared + " blocks).");
		for (int i = 0; i < moving.size(); i++) {
			HubRegistry.Hub h = moving.get(i);
			HubRegistry.Spot to = targets.get(i);
			HubRegistry.Hub moved = HubRegistry.relocate(level.getServer(), h.id(), to.x(), to.z(), to.facing());
			HqFeature.buildAndPublish(level, HqBuilders.get(HqBuilders.defaultId()), HqBuilder.Options.DEFAULT, moved);
			report.add("Moved '" + h.id() + "' to (" + to.x() + ", " + to.z() + "), facing " + facingName(to.facing()) + ".");
		}
		report.add(CompassNetwork.build(level));
		report.add("Spots are " + HubRegistry.COMPASS_SPACING + " apart now (were " + from + ").");
		return report;
	}

	private static int indexOf(List<HubRegistry.Spot> spots, HubRegistry.Hub h) {
		for (int i = 0; i < spots.size(); i++) {
			HubRegistry.Spot s = spots.get(i);
			if (s.x() == h.x() && s.z() == h.z() && s.facing() == h.facing()) {
				return i;
			}
		}
		return -1;
	}

	/** Clears {@code h}'s studio where it stands and builds it again at {@code spot}. */
	static HubRegistry.Hub move(ServerLevel level, HubRegistry.Hub h, HubRegistry.Spot spot) {
		int cleared = CompassNetwork.erase(level, h.id(), h.placement());
		AgentCraft.LOGGER.info("Moving hub '{}': cleared {} blocks at ({}, {})", h.id(), cleared, h.x(), h.z());
		HubRegistry.Hub moved = HubRegistry.relocate(level.getServer(), h.id(), spot.x(), spot.z(), spot.facing());
		HqFeature.buildAndPublish(level, HqBuilders.get(HqBuilders.defaultId()), HqBuilder.Options.DEFAULT, moved);
		return moved;
	}

	static String facingName(char f) {
		return switch (f) {
			case 'N' -> "north";
			case 'E' -> "east";
			case 'W' -> "west";
			default -> "south";
		};
	}
}
