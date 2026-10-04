package dev.agentcraft.client.foreman;

import java.net.URI;

/**
 * One HQ hub's connection to its own Foreman: every hub is a separate project (Foreman profile +
 * port) with its own team, tasks, decisions and memory. {@link Hubs} holds them all; the
 * {@link Foreman} facade talks to the active one.
 *
 * @param id    hub id (also the Foreman profile it runs), e.g. {@code main}, {@code webv3}
 * @param name  shown in the HUD
 * @param port  the Foreman's WebSocket port, always on 127.0.0.1
 */
public record Hub(String id, String name, int port, ForemanState state, ForemanLink link) {
	public static final String MAIN = "main";

	static Hub create(String id, String name, int port, String modVersion, boolean enabled) {
		URI uri = URI.create("ws://127.0.0.1:" + port);
		long now = System.currentTimeMillis();
		ForemanState state = new ForemanState(new LinkStatus(enabled ? LinkStatus.Phase.WAITING_RETRY : LinkStatus.Phase.DISABLED,
			uri.toString(), 0, null, now, now, false));
		// executor: the client thread. Minecraft.getInstance() is resolved lazily (it may not exist yet).
		ForemanLink link = new ForemanLink(uri, modVersion, state, r -> net.minecraft.client.Minecraft.getInstance().execute(r), enabled);
		return new Hub(id, name, port, state, link);
	}

	public boolean connected() {
		return link.status().synced();
	}
}
