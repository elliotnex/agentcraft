package dev.agentcraft.client.foreman;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Starts a hub's Foreman when the game cannot reach it, so a world with several hubs needs no
 * launcher: walking up to (or creating) a hub brings its team online. Configured in
 * {@code ~/.agentcraft/config.json} (or {@code $AGENTCRAFT_HOME/config.json}):
 * <pre>
 * "hubs": {
 *   "autoStart": true,                       // default false: nothing is started unless asked for
 *   "checkout": "C:\\path\\to\\mc-agent",     // the AgentCraft checkout (tools\launch.ps1 lives there)
 *   "claudeConfigDir": "C:\\...",            // optional: CLAUDE_CONFIG_DIR for the Foreman (the claude login to use)
 *   "profiles": { "main": "claude" }         // optional: hub id -> Foreman profile (default: the hub id; main: "claude")
 * }
 * </pre>
 * The Foreman runs through {@code tools\launch.ps1 -NoGame -Profile <p> -Port <n>} (first-run npm
 * install, logs, reuse of a running Foreman), started through WMI so it does not belong to the game's
 * process tree: it keeps working when the game (or its launcher) closes. Windows only for now.
 */
final class ForemanStarter {
	/** Wait this long after a start before trying again (a first launch installs npm packages). */
	private static final long RETRY_MS = 120_000;
	private static final Map<String, Long> STARTED = new HashMap<>();

	private ForemanStarter() {
	}

	/** Called for each hub that is not connected; starts its Foreman when configured and due. Client thread. */
	static void maybeStart(Hub hub) {
		if (Hubs.mismatch(hub) != null) {
			return; // another project's Foreman holds the port
		}
		LinkStatus l = hub.state().link();
		if (l.phase() != LinkStatus.Phase.WAITING_RETRY || l.attempt() < 2) {
			return; // still connecting, or the first failure (it may just be starting)
		}
		long now = System.currentTimeMillis();
		Long last = STARTED.get(hub.id());
		if (last != null && now - last < RETRY_MS) {
			return;
		}
		Config cfg = Config.load();
		if (cfg == null || !cfg.autoStart) {
			return;
		}
		STARTED.put(hub.id(), now);
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			AgentCraft.LOGGER.warn("Hub '{}': starting a Foreman from the game is Windows-only for now (run tools/mac.mjs launch --no-game)", hub.id());
			return;
		}
		Path launch = cfg.checkout.resolve("tools").resolve("launch.ps1");
		if (!Files.isRegularFile(launch)) {
			AgentCraft.LOGGER.warn("Hub '{}': no {} (check hubs.checkout in config.json)", hub.id(), launch);
			return;
		}
		String mapped = dev.agentcraft.layout.HubProfiles.profileFor(hub.id());
		String profile = mapped != null ? mapped : "claude";
		start(hub, launch, profile, cfg.claudeConfigDir);
	}

	private static void start(Hub hub, Path launch, String profile, @Nullable String claudeConfigDir) {
		// inner: what the detached PowerShell runs; outer: create it through WMI (outside our job/tree)
		StringBuilder inner = new StringBuilder();
		if (claudeConfigDir != null) {
			inner.append("$env:CLAUDE_CONFIG_DIR=").append(psQuote(claudeConfigDir)).append("; ");
		}
		inner.append("& ").append(psQuote(launch.toString())).append(" -NoGame -Profile ").append(psQuote(profile)).append(" -Port ")
			.append(hub.port());
		// a WMI-created process gets the user's default environment, not ours: pass a non-default home on
		String home = System.getenv("AGENTCRAFT_HOME");
		if (home != null && !home.isBlank()) {
			inner.append(" -Home ").append(psQuote(home));
		}
		String child = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command \"" + inner.toString().replace("\"", "\\\"")
			+ "\"";
		String outer = "$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = " + psQuote(child)
			+ "; CurrentDirectory = " + psQuote(launch.getParent().getParent().toString()) + " }; exit $r.ReturnValue";
		try {
			Process p = new ProcessBuilder(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", outer))
				.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			AgentCraft.LOGGER.info("Hub '{}': starting its Foreman (profile '{}', port {})", hub.id(), profile, hub.port());
			p.onExit().thenAccept(done -> {
				if (done.exitValue() != 0) {
					AgentCraft.LOGGER.warn("Hub '{}': starting the Foreman failed (WMI create returned {})", hub.id(), done.exitValue());
				}
			});
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("Hub '{}': could not start its Foreman", hub.id(), e);
		}
	}

	/** A PowerShell single-quoted literal. */
	private static String psQuote(String s) {
		return "'" + s.replace("'", "''") + "'";
	}

	private record Config(boolean autoStart, Path checkout, @Nullable String claudeConfigDir, Map<String, String> profiles) {
		static @Nullable Config load() {
			String home = System.getenv("AGENTCRAFT_HOME");
			Path file = (home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".agentcraft")).resolve("config.json");
			if (!Files.isRegularFile(file)) {
				return null;
			}
			try {
				JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				if (!root.has("hubs") || !root.get("hubs").isJsonObject()) {
					return null;
				}
				JsonObject h = root.getAsJsonObject("hubs");
				if (!h.has("checkout")) {
					return null;
				}
				Map<String, String> profiles = new HashMap<>();
				if (h.has("profiles") && h.get("profiles").isJsonObject()) {
					for (Map.Entry<String, JsonElement> e : h.getAsJsonObject("profiles").entrySet()) {
						profiles.put(e.getKey(), e.getValue().getAsString());
					}
				}
				return new Config(h.has("autoStart") && h.get("autoStart").getAsBoolean(), Path.of(h.get("checkout").getAsString()),
					h.has("claudeConfigDir") ? h.get("claudeConfigDir").getAsString() : null, profiles);
			} catch (Exception e) {
				AgentCraft.LOGGER.warn("Could not read the hubs section of {}", file, e);
				return null;
			}
		}
	}
}
