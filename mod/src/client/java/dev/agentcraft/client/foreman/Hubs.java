package dev.agentcraft.client.foreman;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.AgentSay;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.FeedItem;
import dev.agentcraft.client.foreman.Protocol.ForemanStatus;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.LogEntry;
import dev.agentcraft.client.foreman.Protocol.MemoryEntry;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Task;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Every hub's Foreman link, and which one is active (the hub the player is in). Listeners added
 * through {@link Foreman#addListener} hear only the active hub; switching hubs replays the new
 * hub's model to them as a snapshot, exactly like a reconnect, so everything derived from the
 * state is rebuilt. {@link #addAllHubsListener} hears every hub (with the hub passed along).
 *
 * <p>Client thread only, except {@link #all()} and {@link #get} which are safe to read anywhere.
 */
public final class Hubs {
	/** Hears every hub. */
	public interface AllHubsListener {
		void onChange(Hub hub, ForemanState state);
	}

	private static final Map<String, Hub> HUBS = new LinkedHashMap<>();
	private static final List<ForemanListener> ACTIVE_LISTENERS = new CopyOnWriteArrayList<>();
	private static final List<AllHubsListener> ALL_LISTENERS = new CopyOnWriteArrayList<>();
	private static final List<Consumer<Hub>> SWITCH_LISTENERS = new CopyOnWriteArrayList<>();
	private static @Nullable Hub active;
	private static String modVersion = "0";
	private static boolean started;

	private Hubs() {
	}

	static void init(String version) {
		modVersion = version;
	}

	/** Adds a hub (or returns the existing one with that id). Its link starts if the client has started. */
	public static synchronized Hub add(String id, String name, int port, boolean enabled) {
		Hub existing = HUBS.get(id);
		if (existing != null) {
			return existing;
		}
		Hub hub = Hub.create(id, name, port, modVersion, enabled);
		hub.state().addListener(new Forwarder(hub));
		HUBS.put(id, hub);
		if (active == null) {
			active = hub;
		}
		if (started) {
			hub.link().start();
		}
		AgentCraft.LOGGER.info("AgentCraft hub '{}' -> ws://127.0.0.1:{}", id, port);
		return hub;
	}

	/** Stops and forgets a hub (never the last one). */
	public static synchronized void remove(String id) {
		if (HUBS.size() <= 1) {
			return;
		}
		Hub hub = HUBS.remove(id);
		if (hub == null) {
			return;
		}
		hub.link().stop();
		if (active == hub) {
			setActive(HUBS.values().iterator().next());
		}
	}

	public static synchronized List<Hub> all() {
		return new ArrayList<>(HUBS.values());
	}

	public static synchronized @Nullable Hub get(String id) {
		return HUBS.get(id);
	}

	/** The hub the player is in (never null once ForemanFeature has initialised). */
	public static Hub active() {
		return active;
	}

	/** Makes {@code hub} the active one; active listeners get its model as a snapshot. */
	public static void setActive(Hub hub) {
		if (hub == null || hub == active) {
			return;
		}
		active = hub;
		ForemanState s = hub.state();
		for (ForemanListener l : ACTIVE_LISTENERS) {
			try {
				l.onConnection(s.link());
				l.onSnapshot(s);
				if (s.status() != null) {
					l.onStatus(s.status());
				}
				l.onChange(s.revision());
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("hub switch listener {} failed", l.getClass().getName(), t);
			}
		}
		for (Consumer<Hub> l : SWITCH_LISTENERS) {
			l.accept(hub);
		}
	}

	static void addActiveListener(ForemanListener l) {
		ACTIVE_LISTENERS.add(l);
	}

	public static void addAllHubsListener(AllHubsListener l) {
		ALL_LISTENERS.add(l);
	}

	/** Called after the active hub changed. */
	public static void addSwitchListener(Consumer<Hub> l) {
		SWITCH_LISTENERS.add(l);
	}

	static synchronized void startAll() {
		started = true;
		for (Hub h : HUBS.values()) {
			h.link().start();
		}
	}

	static synchronized void stopAll() {
		started = false;
		for (Hub h : HUBS.values()) {
			h.link().stop();
		}
	}

	/** Forwards one hub's events to the active listeners while that hub is active. */
	private record Forwarder(Hub hub) implements ForemanListener {
		private boolean isActive() {
			return active == hub;
		}

		private void each(Consumer<ForemanListener> call) {
			if (!isActive()) {
				return;
			}
			for (ForemanListener l : ACTIVE_LISTENERS) {
				try {
					call.accept(l);
				} catch (Throwable t) {
					AgentCraft.LOGGER.warn("Foreman listener {} failed", l.getClass().getName(), t);
				}
			}
		}

		@Override
		public void onConnection(LinkStatus status) {
			each(l -> l.onConnection(status));
		}

		@Override
		public void onSnapshot(ForemanState state) {
			each(l -> l.onSnapshot(state));
		}

		@Override
		public void onAgent(@Nullable Agent previous, Agent agent) {
			each(l -> l.onAgent(previous, agent));
		}

		@Override
		public void onTask(@Nullable Task previous, Task task) {
			each(l -> l.onTask(previous, task));
		}

		@Override
		public void onDecision(@Nullable Decision previous, Decision decision) {
			each(l -> l.onDecision(previous, decision));
		}

		@Override
		public void onRepo(@Nullable Repo previous, Repo repo) {
			each(l -> l.onRepo(previous, repo));
		}

		@Override
		public void onMemory(@Nullable MemoryEntry previous, MemoryEntry entry) {
			each(l -> l.onMemory(previous, entry));
		}

		@Override
		public void onGoal(@Nullable Goal previous, Goal goal) {
			each(l -> l.onGoal(previous, goal));
		}

		@Override
		public void onFeed(FeedItem item) {
			each(l -> l.onFeed(item));
		}

		@Override
		public void onLog(String agentId, List<LogEntry> entries) {
			each(l -> l.onLog(agentId, entries));
		}

		@Override
		public void onSay(AgentSay say) {
			each(l -> l.onSay(say));
		}

		@Override
		public void onNotify(Notify notify) {
			each(l -> l.onNotify(notify));
		}

		@Override
		public void onStatus(ForemanStatus status) {
			each(l -> l.onStatus(status));
		}

		@Override
		public void onChange(long revision) {
			each(l -> l.onChange(revision));
			for (AllHubsListener l : ALL_LISTENERS) {
				try {
					l.onChange(hub, hub.state());
				} catch (Throwable t) {
					AgentCraft.LOGGER.warn("all-hubs listener {} failed", l.getClass().getName(), t);
				}
			}
		}
	}
}
