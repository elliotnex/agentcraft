package dev.agentcraft.client.wayfinder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.block.ModItems;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/**
 * The Wayfinder item: right-click opens {@link WayfinderScreen}, a teleport menu for the town (plaza,
 * hub wall, help boards, the freelancer's pavilion) and every hub's studio (entrance, task wall,
 * podium, stations, each agent's desk).
 */
public final class WayfinderFeature {
	private WayfinderFeature() {
	}

	public static void init() {
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!player.getItemInHand(hand).is(ModItems.WAYFINDER)) {
				return InteractionResult.PASS;
			}
			if (level.isClientSide()) {
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> mc.gui.setScreen(new WayfinderScreen()));
			}
			return InteractionResult.SUCCESS;
		});
		DevBridge.registerScreen("wayfinder", mc -> new WayfinderScreen());
		DevBridge.register("dev.wayfinder", 10_000, "{place?, spot?} - open the Wayfinder; place (id) selects, spot (index) teleports; lists places and spots",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String place = f.optStr("place", null);
				int spot = f.optInt("spot", -1, -1, 99);
				return DevBridge.onClient(mc, () -> {
					if (!(mc.gui.screen() instanceof WayfinderScreen)) {
						mc.gui.setScreen(new WayfinderScreen());
					}
					WayfinderScreen s = (WayfinderScreen) mc.gui.screen();
					if (place != null) {
						s.select(place);
					}
					JsonObject o = new JsonObject();
					o.addProperty("selected", s.selectedId());
					JsonArray places = new JsonArray();
					for (WayfinderScreen.Place p : WayfinderScreen.places()) {
						JsonObject j = new JsonObject();
						j.addProperty("id", p.id());
						j.addProperty("name", p.name());
						j.addProperty("spots", p.spots().size());
						if (p.why() != null) {
							j.addProperty("why", p.why());
						}
						places.add(j);
					}
					o.add("places", places);
					if (spot >= 0) {
						o.addProperty("went", s.go(spot));
					}
					return o;
				});
			});
	}

	/** Teleport the player (feet at x, y, z, looking yaw/pitch). Singleplayer: on the server thread; else /tp. */
	public static void teleport(double x, double y, double z, float yaw, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			mc.player.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
			return;
		}
		UUID who = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer p = server.getPlayerList().getPlayer(who);
			if (p != null) {
				p.teleportTo(server.overworld(), x, y, z, Set.of(), yaw, pitch, true);
			}
		});
	}
}
