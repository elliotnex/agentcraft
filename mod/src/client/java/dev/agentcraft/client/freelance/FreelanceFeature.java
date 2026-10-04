package dev.agentcraft.client.freelance;

import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.Hub;
import dev.agentcraft.client.foreman.Hubs;
import dev.agentcraft.client.foreman.Protocol.AgentSay;
import dev.agentcraft.hq.FreelancePavilion;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The freelancer in the client: the pavilion's console terminal opens {@link FreelanceScreen}
 * (routed from ConsoleFeature while the freelance hub is active), and what Scout says to the player
 * (answers to questions, problems) also lands in chat, wherever the player is.
 */
public final class FreelanceFeature {
	private static Hub listening;

	private FreelanceFeature() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> attach());
		DevBridge.registerScreen("freelance", mc -> new FreelanceScreen());
		DevBridge.register("dev.freelance", 15_000,
			"{request?, search?, press?: preset|browse|pick|tools|refresh|back|repo|mode|send|newchat, arg?:0 (mode: 0 task, 1 ask, 2 chat)} - drive the open freelancer terminal (opens it first)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String request = f.optStr("request", null);
				String search = f.optStr("search", null);
				String press = f.optStr("press", null);
				int arg = f.optInt("arg", 0, 0, 99);
				return DevBridge.onClient(mc, () -> {
					if (!(mc.gui.screen() instanceof FreelanceScreen)) {
						mc.gui.setScreen(new FreelanceScreen());
					}
					FreelanceScreen s = (FreelanceScreen) mc.gui.screen();
					if (request != null) {
						s.setRequest(request);
					}
					if (press != null) {
						s.press(press, arg);
					}
					if (search != null) {
						s.setSearch(search);
					}
					JsonObject o = new JsonObject();
					Hub h = FreelanceScreen.hub();
					o.addProperty("hub", h == null ? null : h.id());
					o.addProperty("connected", h != null && h.connected());
					o.addProperty("model", FreelanceScreen.currentModel());
					o.addProperty("repos", FreelanceScreen.repos().size());
					o.addProperty("mode", s.mode());
					o.addProperty("browsing", s.browsing());
					o.addProperty("field", s.modelValue());
					o.addProperty("catalog", FreelanceScreen.catalog().size());
					o.addProperty("matches", s.browsing() ? s.filtered().size() : -1);
					return o;
				});
			});
	}

	/** True when the pavilion's hub is the active one (the terminal there opens the freelancer screen). */
	public static boolean active() {
		return FreelancePavilion.HUB.equals(Hubs.active().id());
	}

	public static void openTerminal() {
		Minecraft.getInstance().gui.setScreen(new FreelanceScreen());
	}

	/** Once the freelance hub exists: Scout's words to the player go to chat too. */
	private static void attach() {
		Hub h = Hubs.get(FreelancePavilion.HUB);
		if (h == null || h == listening) {
			return;
		}
		listening = h;
		h.state().addListener(new ForemanListener() {
			@Override
			public void onSay(AgentSay say) {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player == null || !"user".equals(say.to()) || !FreelancePavilion.AGENT.equals(say.agentId())) {
					return;
				}
				mc.player.sendSystemMessage(Component.literal("Scout: ").withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(Component.literal(say.text()).withStyle(ChatFormatting.WHITE)));
			}
		});
	}
}
