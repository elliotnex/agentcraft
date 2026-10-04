package dev.agentcraft.client.hubs;

import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.foreman.Hub;
import dev.agentcraft.client.foreman.Hubs;
import dev.agentcraft.client.hud.Keys;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The hubs overview ({@link HubsScreen}, key H) and travel between hubs. Going to another hub
 * teleports the player to the hub's studio entrance on the integrated server (not through a chat
 * command: a command the client sends on the player's behalf can be refused without any feedback);
 * "Answer" opens that hub's decisions as soon as the player has arrived (the active hub follows the
 * player, so the decision screen then talks to that hub).
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
		DevBridge.register("dev.screen.click", 5_000, "{x, y, button?:0} - click the open screen at GUI coordinates (as the mouse would)",
			(req, mc) -> {
				dev.agentcraft.client.dev.Fields f = dev.agentcraft.client.dev.Fields.of(req);
				double x = f.optLong("x", -1, 0, 100_000);
				double y = f.optLong("y", -1, 0, 100_000);
				int button = f.optInt("button", 0, 0, 2);
				return DevBridge.onClient(mc, () -> {
					com.google.gson.JsonObject o = new com.google.gson.JsonObject();
					var screen = mc.gui.screen();
					o.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
					o.addProperty("handled", screen != null && screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false));
					return o;
				});
			});
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
		if (mc.player == null) {
			return;
		}
		Anchor spawn = Anchors.of(hub.id()).get(AnchorNames.SPAWN);
		if (spawn == null) {
			mc.player.sendSystemMessage(Component.literal("Hub '" + hub.id() + "' has no studio yet: /agentcraft hub build " + hub.id()));
			return;
		}
		answerOnArrival = answer ? hub.id() : null;
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			mc.player.connection.sendCommand("agentcraft hub tp " + hub.id());
			return;
		}
		UUID who = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer p = server.getPlayerList().getPlayer(who);
			if (p != null) {
				p.teleportTo(server.overworld(), spawn.x(), spawn.y(), spawn.z(), Set.of(), spawn.yaw(), 0f, true);
			}
		});
	}
}
