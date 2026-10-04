package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.ClientEnv;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.ForemanStatus;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.layout.HubRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Wires the Foreman link: creates the state model and the WebSocket client at startup, starts it
 * when the client has started, stops it on shutdown, and exposes it to the DevBridge
 * ({@code dev.state.foreman}, {@code dev.foreman}).
 *
 * <pre>
 * AGENTCRAFT_PORT     the main hub's Foreman port (default 7878), always 127.0.0.1
 * AGENTCRAFT_FOREMAN  0 disables the links (the HUD then says so)
 * </pre>
 *
 * Further hubs are added at runtime ({@link Hubs#add}); the DevBridge handlers act on the active hub.
 */
public final class ForemanFeature {
	private ForemanFeature() {
	}

	public static void init() {
		int port = ClientEnv.intValue("AGENTCRAFT_PORT", 7878);
		boolean enabled = ClientEnv.flag("AGENTCRAFT_FOREMAN", true);
		String modVersion = FabricLoader.getInstance().getModContainer(AgentCraft.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
		Hubs.init(modVersion);
		Hubs.add(Hub.MAIN, "Main", port, enabled);
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> Hubs.startAll());
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Hubs.stopAll());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> followWorldHubs(mc, enabled));

		DevBridge.addStateContributor((mc, o) -> o.add("foreman", stateJson()));
		DevBridge.register("dev.foreman", 10_000,
			"{reconnect?:false} -> foreman link status, backend/auth and counts; reconnect:true drops and reconnects now",
			(req, mc) -> {
				boolean reconnect = Fields.of(req).optBool("reconnect", false);
				if (reconnect) {
					Foreman.link().reconnectNow();
				}
				return DevBridge.onClient(mc, ForemanFeature::stateJson);
			});
		DevBridge.register("dev.hubs", 5_000, "{} -> every hub's link: {active, hubs:[{id, name, port, link, connected, agents, tasks}]}",
			(req, mc) -> DevBridge.onClient(mc, ForemanFeature::hubsJson));
		DevBridge.register("dev.hub.use", 5_000, "{hub} - make that hub active (HUD, console and screens follow it)",
			(req, mc) -> {
				String id = Fields.of(req).nonBlank("hub");
				return DevBridge.onClient(mc, () -> {
					Hub hub = Hubs.get(id);
					if (hub == null) {
						throw new DevBridge.DevException("no hub '" + id + "'");
					}
					Hubs.setActive(hub);
					return hubsJson();
				});
			});
		DevBridge.register("dev.hub.add", 5_000, "{hub, port, name?} - connect another hub's Foreman (link only; no studio is built)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String id = f.nonBlank("hub");
				int hubPort = f.optInt("port", 0, 1024, 65535);
				if (hubPort == 0) {
					throw new DevBridge.DevException("field 'port' is required (1024-65535)");
				}
				String name = f.optStr("name", id);
				return DevBridge.onClient(mc, () -> {
					Hubs.add(id, name, hubPort, true);
					return hubsJson();
				});
			});
		if (ClientEnv.flag("AGENTCRAFT_DEV_TEST", false)) {
			// TEST ONLY: feed a Foreman message into the state model as if the Foreman sent it (UI states
			// that are hard to reach for real, e.g. a failed claude login banner).
			DevBridge.register("dev.test.foremanMessage", 5_000, "{message:{type, ...}} - TEST ONLY: apply as if received from the Foreman",
				(req, mc) -> {
					JsonObject msg = Fields.of(req).obj("message").json();
					String type = Fields.of(msg).nonBlank("type");
					return DevBridge.onClient(mc, () -> {
						JsonObject o = new JsonObject();
						o.addProperty("applied", Foreman.state().apply(type, msg));
						return o;
					});
				});
		}
		// Video choreography (always available): inject messages as if the Foreman sent them, hold the live stream.
		DevBridge.register("dev.foreman.inject", 5_000,
			"{message:{type,...}} | {patch:{agent|task:id, set:{field:value...}}} | {say:{agent, text, to?}}, hub? - apply to the mod's Foreman"
				+ " model as if received (bypasses the hold queue); patch copies the current agent/task and replaces a few wire fields",
			(req, mc) -> {
				Fields f = Fields.of(req);
				JsonObject msg = injectMessage(f);
				String hubId = f.optStr("hub", null);
				return DevBridge.onClient(mc, () -> {
					if (hubId == null) {
						return applyInjected(msg);
					}
					Hub hub = Hubs.get(hubId);
					if (hub == null) {
						throw new DevBridge.DevException("no hub '" + hubId + "'");
					}
					return applyInjected(msg, hub.state());
				});
			});
		DevBridge.register("dev.foreman.hold", 10_000,
			"{on:bool, release?:reconnect|replay|drop} - hold live Foreman messages (queued, not applied) so a shot shows only what it"
				+ " injects; on:false releases them: reconnect (default, fresh snapshot), replay (apply the queue) or drop",
			(req, mc) -> {
				Fields f = Fields.of(req);
				boolean on = f.bool("on");
				String release = f.optStr("release", "reconnect");
				if (!java.util.Set.of("reconnect", "replay", "drop").contains(release)) {
					throw new DevBridge.DevException("field 'release' must be reconnect|replay|drop (got '" + release + "')");
				}
				return DevBridge.onClient(mc, () -> {
					ForemanState state = Foreman.state();
					JsonObject o = new JsonObject();
					o.addProperty("wasHeld", state.isHeld());
					if (on) {
						state.setHold(true);
					} else if (state.isHeld()) {
						o.addProperty("released", state.releaseHold(release.equals("replay")));
						if (release.equals("reconnect")) {
							Foreman.link().reconnectNow();
						}
					}
					o.addProperty("held", state.isHeld());
					o.addProperty("queued", state.heldCount());
					return o;
				});
			});
		DevBridge.register("dev.foreman.send", 25_000,
			"{message:{type, ...payload}} -> {ack:{re, ok, error?, result?}} - send a client message (docs/protocol.md Mod -> Foreman) through"
				+ " the mod's own link, e.g. {message:{type:'goal.submit', text:'...'}} or {message:{type:'decision.answer', decisionId:'d3', option:'Merge'}}",
			(req, mc) -> {
				Fields f = Fields.of(req);
				JsonObject msg = f.obj("message").json();
				Fields.of(msg).nonBlank("type");
				return DevBridge.onClient(mc, () -> Foreman.link().send(msg.deepCopy())).thenCompose(fut -> fut).thenApply(ack -> {
					JsonObject o = new JsonObject();
					o.add("ack", ForemanJson.GSON.toJsonTree(ack));
					return o;
				});
			});
	}

	/**
	 * Validates an injection request ({@code message} | {@code patch} | {@code say}) and returns it as
	 * {@code {kind, ...}} for {@link #applyInjected}. Any thread.
	 */
	public static JsonObject injectMessage(Fields f) {
		int kinds = (f.has("message") ? 1 : 0) + (f.has("patch") ? 1 : 0) + (f.has("say") ? 1 : 0);
		if (kinds != 1) {
			throw new DevBridge.DevException("give exactly one of message | patch | say");
		}
		JsonObject out = new JsonObject();
		if (f.has("message")) {
			JsonObject msg = f.obj("message").json().deepCopy();
			Fields.of(msg).nonBlank("type");
			out.addProperty("kind", "message");
			out.add("message", msg);
		} else if (f.has("patch")) {
			Fields p = f.obj("patch");
			if (p.has("agent") == p.has("task")) {
				throw new DevBridge.DevException("field 'patch' needs exactly one of agent | task");
			}
			out.addProperty("kind", "patch");
			out.addProperty("of", p.has("agent") ? "agent" : "task");
			out.addProperty("id", p.has("agent") ? p.nonBlank("agent") : p.nonBlank("task"));
			out.add("set", p.obj("set").json().deepCopy());
		} else {
			Fields s = f.obj("say");
			JsonObject msg = new JsonObject();
			msg.addProperty("v", Protocol.VERSION);
			msg.addProperty("type", "agent.say");
			msg.addProperty("agentId", s.nonBlank("agent"));
			msg.addProperty("text", s.str("text"));
			String to = s.optStr("to", null);
			if (to != null) {
				msg.addProperty("to", to);
			}
			out.addProperty("kind", "message");
			out.add("message", msg);
		}
		return out;
	}

	/** Applies a validated injection (see {@link #injectMessage}). Client thread. */
	public static JsonObject applyInjected(JsonObject inj) {
		return applyInjected(inj, Foreman.state());
	}

	/** Applies a validated injection to one hub's model (the "hub" field of dev.foreman.inject). Client thread. */
	public static JsonObject applyInjected(JsonObject inj, ForemanState st) {
		JsonObject o = new JsonObject();
		if (inj.get("kind").getAsString().equals("patch")) {
			try {
				JsonObject applied = st.patch(inj.get("of").getAsString(), inj.get("id").getAsString(), inj.getAsJsonObject("set"));
				o.addProperty("applied", true);
				o.add("message", applied);
			} catch (IllegalArgumentException e) {
				throw new DevBridge.DevException(e.getMessage());
			}
			return o;
		}
		JsonObject msg = inj.getAsJsonObject("message").deepCopy();
		String type = msg.get("type").getAsString();
		if (type.equals("agent.say") && !msg.has("ts")) {
			// the bubble is fresh: stamped now
			msg.addProperty("ts", System.currentTimeMillis());
		}
		o.addProperty("applied", st.inject(type, msg));
		o.add("message", msg);
		return o;
	}

	private static long seenHubsRevision = -1;
	private static int hubTick;
	/** The hub plot the player stood in at the last check (null: outside every hub). */
	private static @Nullable String lastHereId;
	/** The hub that was active before the player walked into the freelancer's pavilion. */
	private static @Nullable String beforePavilion;

	/**
	 * Keeps the links in step with the world's hubs ({@link HubRegistry}: a new hub gets its link) and
	 * makes the hub the player walks into the active one. Only crossing into a hub's plot switches, so
	 * a hub picked by hand (console {@code /hub}) holds until the player enters another plot. Client
	 * thread, every tick (cheap; the position check runs twice a second).
	 */
	private static boolean inPavilion(Minecraft mc) {
		var b = dev.agentcraft.layout.Anchors.of(dev.agentcraft.hq.FreelancePavilion.HUB).bounds();
		return b != null && b.contains(mc.player.getBlockX(), Math.max(b.minY(), Math.min(b.maxY(), mc.player.getBlockY())), mc.player.getBlockZ());
	}

	private static void followWorldHubs(Minecraft mc, boolean enabled) {
		long rev = HubRegistry.revision();
		if (rev != seenHubsRevision) {
			seenHubsRevision = rev;
			for (HubRegistry.Hub h : HubRegistry.all()) {
				if (!h.isMain() && Hubs.get(h.id()) == null) {
					Hubs.add(h.id(), h.name(), h.port(), enabled);
				}
			}
		}
		// the freelancer (Scout, any model) once the world has its pavilion: a hub without a registry entry
		String fl = dev.agentcraft.hq.FreelancePavilion.HUB;
		if (Hubs.get(fl) == null && hubTick % 20 == 0 && !dev.agentcraft.layout.Anchors.of(fl).isEmpty()) {
			Hubs.add(fl, "Freelancer", dev.agentcraft.layout.HubProfiles.portFor(fl, java.util.Set.of(Hub.MAIN.equals(Hubs.active().id()) ? Hubs.active().port() : 7878)), enabled);
		}
		if (++hubTick % 10 != 0) {
			return;
		}
		if (enabled) {
			for (Hub h : Hubs.all()) {
				if (!h.connected()) {
					ForemanStarter.maybeStart(h);
				}
			}
			if (hubTick % 600 == 0) {
				Hubs.recheckMismatched();
			}
		}
		if (mc.player == null) {
			lastHereId = null;
			return;
		}
		HubRegistry.Hub here = HubRegistry.at(mc.player.getX(), mc.player.getZ());
		String hereId = here == null ? null : here.id();
		if (hereId == null && inPavilion(mc)) {
			hereId = dev.agentcraft.hq.FreelancePavilion.HUB;
		}
		if (java.util.Objects.equals(hereId, lastHereId)) {
			return;
		}

		Hub hub = hereId == null ? null : Hubs.get(hereId);
		if (hub != null) {
			if (fl.equals(hereId) && !fl.equals(Hubs.active().id())) {
				beforePavilion = Hubs.active().id();
			}
			Hubs.setActive(hub);
			lastHereId = hereId;
		} else if (hereId == null) {
			// stepping out of the pavilion gives the HUD back to the hub you came from
			if (fl.equals(lastHereId) && fl.equals(Hubs.active().id()) && beforePavilion != null && Hubs.get(beforePavilion) != null) {
				Hubs.setActive(Hubs.get(beforePavilion));
			}
			lastHereId = null;
		}
	}

	/** Every hub's link at a glance (dev.hubs). Client thread. */
	public static JsonObject hubsJson() {
		JsonObject o = new JsonObject();
		o.addProperty("active", Foreman.hub().id());
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (Hub h : Hubs.all()) {
			JsonObject j = new JsonObject();
			j.addProperty("hub", h.id());
			j.addProperty("name", h.name());
			j.addProperty("port", h.port());
			j.addProperty("link", h.state().link().phaseName());
			j.addProperty("connected", h.connected());
			j.addProperty("agents", h.state().agents().size());
			j.addProperty("tasks", h.state().tasks().size());
			arr.add(j);
		}
		o.add("hubs", arr);
		return o;
	}

	/** Compact JSON view of the link + model (for dev.state / dev.foreman). Client thread. */
	public static JsonObject stateJson() {
		ForemanState s = Foreman.state();
		LinkStatus l = s.link();
		JsonObject o = new JsonObject();
		o.addProperty("hub", Foreman.hub().id());
		o.addProperty("link", l.phaseName());
		o.addProperty("connected", l.synced());
		o.addProperty("url", l.url());
		o.addProperty("attempt", l.attempt());
		o.addProperty("lastError", l.lastError());
		o.addProperty("phaseForMs", System.currentTimeMillis() - l.sinceMs());
		o.addProperty("everSynced", l.everSynced());
		o.addProperty("snapshots", s.snapshotCount());
		o.addProperty("messages", Foreman.link().messageCount());
		o.addProperty("lastMessageAgoMs", s.lastMessageAt() == 0 ? -1 : System.currentTimeMillis() - s.lastMessageAt());
		o.addProperty("stale", s.isStale());
		o.addProperty("held", s.isHeld());
		o.addProperty("heldQueued", s.heldCount());
		ForemanStatus fs = s.status();
		o.addProperty("backend", fs == null ? null : fs.backend().wire());
		o.addProperty("auth", fs == null ? null : fs.auth().wire());
		o.addProperty("message", fs == null ? null : fs.message());
		o.addProperty("version", fs == null ? null : fs.version());
		JsonObject counts = new JsonObject();
		counts.addProperty("agents", s.agents().size());
		counts.addProperty("activeAgents", s.agents().values().stream().filter(Protocol.Agent::isActive).count());
		counts.addProperty("tasks", s.tasks().size());
		int open = 0;
		for (Task t : s.tasks().values()) {
			if (t.status() != TaskStatus.DONE && t.status() != TaskStatus.CANCELLED) {
				open++;
			}
		}
		counts.addProperty("openTasks", open);
		counts.addProperty("decisions", s.decisions().size());
		counts.addProperty("openDecisions", s.openDecisions().size());
		counts.addProperty("repos", s.repos().size());
		counts.addProperty("memory", s.memory().size());
		counts.addProperty("goals", s.goals().size());
		counts.addProperty("feed", s.feed().size());
		o.add("counts", counts);
		Goal g = s.goal();
		if (g != null) {
			JsonObject go = new JsonObject();
			go.addProperty("id", g.id());
			go.addProperty("text", g.text());
			go.addProperty("progress", g.progress());
			go.addProperty("status", g.status().wire());
			o.add("goal", go);
		}
		Decision first = s.openDecisions().isEmpty() ? null : s.openDecisions().getFirst();
		o.addProperty("oldestOpenDecision", first == null ? null : first.id());
		return o;
	}
}
