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
 * the plaza), then the compass roads and plaza are built. The player's own blocks are kept
 * throughout: clearing only touches cells still as a build left them.
 */
final class TownLayout {
	private TownLayout() {
	}

	static List<String> toCompass(ServerLevel level) {
		List<String> report = new ArrayList<>();
		if (HubRegistry.COMPASS.equals(HubRegistry.layout())) {
			report.add("This world already uses the compass layout.");
			return report;
		}
		// 1. the line layout's promenade, spurs and courtyard
		int cleared = 0;
		for (HubRegistry.Hub h : HubRegistry.all()) {
			cleared += CompassNetwork.erase(level, "net-" + h.id(), Placement.IDENTITY);
		}
		report.add("Cleared the old promenade (" + cleared + " blocks).");
		// 2. hubs already on a compass spot stay; 3. the rest move to free spots
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
