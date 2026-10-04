package dev.agentcraft.client.foreman;

import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Diff;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Static entry point to the Foreman for every client feature.
 *
 * <pre>
 * Foreman.state().agents()                      // read the model (client thread)
 * Foreman.addListener(new ForemanListener() {...}) // change callbacks (client thread)
 * Foreman.submitGoal("Add OAuth", null).thenAccept(ack -> ...)  // intents; futures complete on the client thread
 * Foreman.requestDiff(repoId, worktree).thenAccept(diff -> ...)
 * </pre>
 *
 * Intents fail fast (exceptionally) while the link is not synced; an {@link Ack} with
 * {@code ok=false} carries the Foreman's error text.
 *
 * <p>Everything here talks to the <b>active hub</b> ({@link Hubs#active()}, the hub the player is
 * in). World-bound code that belongs to a specific hub (a monitor, an agent) uses that
 * {@link Hub}'s state and link instead.
 */
public final class Foreman {
	private Foreman() {
	}

	public static Hub hub() {
		return Hubs.active();
	}

	public static ForemanState state() {
		return Hubs.active().state();
	}

	public static ForemanLink link() {
		return Hubs.active().link();
	}

	/**
	 * The active hub's studio layout: the world-bound features (agents, lamps, monitors) live in the
	 * studio the player is in. A hub switch hands out a different Layout instance, so compare layouts
	 * by identity, not by revision (every hub's first build is revision 1).
	 */
	public static dev.agentcraft.layout.Anchors.Layout layout() {
		return dev.agentcraft.layout.Anchors.of(Hubs.active().id());
	}

	/** The layout of the studio at world (x, z) (the active hub's when that is on no hub's grounds). */
	public static dev.agentcraft.layout.Anchors.Layout layoutAt(double x, double z) {
		dev.agentcraft.layout.HubRegistry.Hub h = dev.agentcraft.layout.HubRegistry.at(x, z);
		return h == null ? layout() : dev.agentcraft.layout.Anchors.of(h.id());
	}

	/** A named anchor of the active hub's studio, or null. */
	public static dev.agentcraft.layout.@Nullable Anchor anchor(String name) {
		return layout().get(name);
	}

	/** Hears the active hub; on a hub switch it gets the new hub's model as a snapshot. */
	public static void addListener(ForemanListener l) {
		Hubs.addActiveListener(l);
	}

	public static boolean connected() {
		Hub h = Hubs.active();
		return h != null && h.connected();
	}

	/** Send any client message (type + payload); see docs/protocol.md "Mod -> Foreman". */
	public static CompletableFuture<Ack> send(String type, JsonObject payload) {
		JsonObject m = payload.deepCopy();
		m.addProperty("type", type);
		return link().send(m);
	}

	/** New goal for the lead (console: plain text). {@code repoId} null = the Foreman's default repo. */
	public static CompletableFuture<Ack> submitGoal(String text, @Nullable String repoId) {
		return link().send(ForemanJson.msg("goal.submit").put("text", text).put("repoId", repoId).json());
	}

	/** Message an agent ({@code to} = agent id) or everyone ({@code "all"}; a leading "@name" routes it). */
	public static CompletableFuture<Ack> message(String to, String text) {
		return link().send(ForemanJson.msg("user.message").put("to", to).put("text", text).json());
	}

	/** Answer a decision with an option label (preferred) and/or free text. */
	public static CompletableFuture<Ack> answer(String decisionId, @Nullable String option, @Nullable String text) {
		return link().send(ForemanJson.msg("decision.answer").put("decisionId", decisionId).put("option", option).put("text", text).json());
	}

	/** {@code action}: reassign | cancel | retry | prioritize; {@code arg}: agent id / priority. */
	public static CompletableFuture<Ack> taskAction(String taskId, String action, @Nullable String arg) {
		return link().send(ForemanJson.msg("task.action").put("taskId", taskId).put("action", action).put("arg", arg).json());
	}

	/** {@code action}: pause | resume | stop | spawn; {@code arg}: spawn task id. */
	public static CompletableFuture<Ack> agentAction(String agentId, String action, @Nullable String arg) {
		return link().send(ForemanJson.msg("agent.action").put("agentId", agentId).put("action", action).put("arg", arg).json());
	}

	public static CompletableFuture<Ack> addRepo(String path) {
		return link().send(ForemanJson.msg("repo.add").put("path", path).json());
	}

	/** Structured diff of a worktree (or an agent id: its current worktree) vs its base. */
	public static CompletableFuture<Diff> requestDiff(String repoId, String worktree) {
		return link().requestDiff(repoId, worktree);
	}
}
