package dev.agentcraft.client.hubs;

import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.foreman.Hub;
import dev.agentcraft.client.foreman.Hubs;
import dev.agentcraft.client.hud.Keys;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The hubs overview ({@link HubsScreen}, key H) and travel between hubs. Going to another hub runs
 * {@code /agentcraft hub tp <id>}; "Answer" opens that hub's decisions as soon as the player has
 * arrived (the active hub follows the player, so the decision screen then talks to that hub).
 */
public final class HubsFeature {
	private static @Nullable String answerOnArrival;

	private HubsFeature() {
	}

	public static void init() {
		Keys.ensureRegistered();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null) {
				return;
			}
			while (Keys.hubs.consumeClick()) {
				if (mc.gui.screen() == null) {
					mc.gui.setScreen(new HubsScreen());
				}
			}
		});
		Hubs.addSwitchListener(hub -> {
			if (hub.id().equals(answerOnArrival)) {
				answerOnArrival = null;
				Minecraft mc = Minecraft.getInstance();
				if (mc.gui.screen() == null && !hub.state().openDecisions().isEmpty()) {
					DecisionsFeature.openQueue(null, null);
				}
			}
		});
		DevBridge.registerScreen("hubs", mc -> new HubsScreen());
	}

	/** Go to {@code hub}'s studio; {@code answer}: open its decisions there. Client thread. */
	public static void travel(Hub hub, boolean answer) {
		Minecraft mc = Minecraft.getInstance();
		if (hub == Hubs.active()) {
			if (answer) {
				// closing the overview first, then the decisions open on the next tick
				mc.execute(() -> DecisionsFeature.openQueue(null, null));
			}
			return;
		}
		answerOnArrival = answer ? hub.id() : null;
		if (mc.player != null) {
			mc.player.connection.sendCommand("agentcraft hub tp " + hub.id());
		}
	}
}
