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
	/** hub id -> project folder to connect once the new hub's Foreman is up. */
	private static final java.util.Map<String, String> PENDING_REPOS = new java.util.concurrent.ConcurrentHashMap<>();
	private static int tick;

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
			if (++tick % 20 == 0) {
				connectPendingRepos(mc);
			}
		});
		DevBridge.registerScreen("newhub", mc -> new NewHubScreen(null));
		DevBridge.register("dev.hubsettings", 10_000, "{hub, add?, press?: default|remove|fetch|pull|push|publish|visibility|autopush|add, repo?} - drive a hub's settings screen",
			(req, mc) -> {
				dev.agentcraft.client.dev.Fields f = dev.agentcraft.client.dev.Fields.of(req);
				String hubId = f.nonBlank("hub");
				String addText = f.optStr("add", null);
				String press = f.optStr("press", null);
				String repo = f.optStr("repo", "");
				return DevBridge.onClient(mc, () -> {
					Hub hub = Hubs.get(hubId);
					if (hub == null) {
						throw new DevBridge.DevException("no hub '" + hubId + "'");
					}
					if (!(mc.gui.screen() instanceof HubSettingsScreen s0) || s0.hub() != hub) {
						mc.gui.setScreen(new HubSettingsScreen(hub, null));
					}
					HubSettingsScreen s = (HubSettingsScreen) mc.gui.screen();
					if (addText != null) {
						s.setAdd(addText);
					}
					if (press != null) {
						s.press(press, repo);
					}
					com.google.gson.JsonObject o = new com.google.gson.JsonObject();
					o.addProperty("note", s.note());
					com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
					for (var r : hub.state().repos().values()) {
						com.google.gson.JsonObject j = new com.google.gson.JsonObject();
						j.addProperty("id", r.id());
						j.addProperty("remote", r.remote());
						j.addProperty("ahead", r.ahead());
						j.addProperty("behind", r.behind());
						j.addProperty("isDefault", r.isDefault());
						j.addProperty("autoPush", r.autoPush());
						arr.add(j);
					}
					o.add("repos", arr);
					return o;
				});
			});
		DevBridge.register("dev.newhub", 10_000, "{hub?, name?, folder?, press?: theme|create|back, arg?:0} - drive the new-hub form (opens it first)",
			(req, mc) -> {
				dev.agentcraft.client.dev.Fields f = dev.agentcraft.client.dev.Fields.of(req);
				String id = f.optStr("hub", null);
				String name = f.optStr("name", "");
				String folder = f.optStr("folder", null);
				String press = f.optStr("press", null);
				int arg = f.optInt("arg", 0, 0, 20);
				return DevBridge.onClient(mc, () -> {
					if (!(mc.gui.screen() instanceof NewHubScreen)) {
						mc.gui.setScreen(new NewHubScreen(null));
					}
					NewHubScreen s = (NewHubScreen) mc.gui.screen();
					if (id != null) {
						s.fill(id, name, folder);
					}
					if (press != null) {
						s.press(press, arg);
					}
					com.google.gson.JsonObject o = new com.google.gson.JsonObject();
					o.addProperty("screen", mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
					o.addProperty("pending", PENDING_REPOS.toString());
					return o;
				});
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
		DevBridge.register("dev.screen.click", 5_000, "{x, y, button?:1} - click the open screen at GUI coordinates (as the mouse would; 1 = left)",
			(req, mc) -> {
				dev.agentcraft.client.dev.Fields f = dev.agentcraft.client.dev.Fields.of(req);
				double x = f.optLong("x", -1, 0, 100_000);
				double y = f.optLong("y", -1, 0, 100_000);
				// the real mouse numbering (26.x/SDL: left 1, middle 2, right 3), so a dev click takes the same path as the player's
				int button = f.optInt("button", com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 1, 3);
				return DevBridge.onClient(mc, () -> {
					com.google.gson.JsonObject o = new com.google.gson.JsonObject();
					var screen = mc.gui.screen();
					o.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
					o.addProperty("handled", screen != null && screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false));
					return o;
				});
			});
	}

	/**
	 * A new hub from the GUI: created in the next free spot with {@code theme} (so the studio is built
	 * once, already themed), built, and the player taken there; {@code folder} (optional) is connected
	 * to the hub's Foreman once that is up, made a git repo first if it is not one. Client thread.
	 */
	public static void createHub(String id, String name, String theme, @Nullable String folder) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		if (folder != null) {
			PENDING_REPOS.put(id, folder);
		}
		mc.player.sendSystemMessage(Component.literal("Creating hub '" + id + "': building its studio..."));
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) {
			// on a server: the commands (they need operator rights there)
			mc.player.connection.sendCommand("agentcraft hub create " + id + (name.isBlank() ? "" : " " + name));
			if (!"warm".equals(theme)) {
				mc.player.connection.sendCommand("agentcraft hub theme " + id + " " + theme);
			}
			return;
		}
		UUID who = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer p = server.getPlayerList().getPlayer(who);
			if (p == null) {
				return;
			}
			try {
				dev.agentcraft.layout.HubRegistry.create(server, id, name);
				if (!"warm".equals(theme)) {
					dev.agentcraft.layout.HubRegistry.setTheme(server, id, theme);
				}
			} catch (IllegalArgumentException e) {
				PENDING_REPOS.remove(id);
				p.sendSystemMessage(Component.literal("Couldn't create the hub: " + e.getMessage()).withStyle(net.minecraft.ChatFormatting.RED));
				return;
			}
			// the world's owner builds through the same commands as /ac hub create, cheats on or off
			var src = p.createCommandSourceStack().withMaximumPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
			server.getCommands().performPrefixedCommand(src, "agentcraft hub build " + id);
			server.getCommands().performPrefixedCommand(src, "agentcraft hub tp " + id);
			dev.agentcraft.AgentCraft.LOGGER.info("Hubs overview: created hub '{}' (theme {}, project {})", id, theme, folder);
		});
	}

	/** New hubs whose Foreman is up: connect their project folder (making it a git repo first). Client thread. */
	private static void connectPendingRepos(Minecraft mc) {
		for (var e : java.util.List.copyOf(PENDING_REPOS.entrySet())) {
			Hub hub = Hubs.get(e.getKey());
			if (hub == null || !hub.connected() || !hub.state().hasData()) {
				continue;
			}
			PENDING_REPOS.remove(e.getKey());
			String folder = e.getValue();
			if (HubSettingsScreen.isUrl(folder)) {
				// a GitHub URL: the hub's Foreman clones it next to the other projects
				String dest = NewHubScreen.cloneTarget(folder);
				say(mc, "Cloning " + HubSettingsScreen.shortRemote(folder) + " for '" + hub.id() + "'...");
				dev.agentcraft.client.foreman.Foreman.cloneRepo(hub, folder, dest).whenComplete((ack, err) -> say(mc, err != null || !ack.ok()
					? "Couldn't clone " + folder + ": " + (err != null ? err.getMessage() : ack.error())
					: "Hub '" + hub.id() + "' is working on " + dest + " (cloned from " + HubSettingsScreen.shortRemote(folder) + "). Give its team a goal in the console."));
				continue;
			}
			java.util.concurrent.CompletableFuture.supplyAsync(() -> prepareRepo(folder, hub.name())).whenComplete((made, err) -> mc.execute(() -> {
				if (err != null) {
					say(mc, "Couldn't set up " + folder + ": " + (err.getCause() != null ? err.getCause().getMessage() : err.getMessage()));
					return;
				}
				dev.agentcraft.client.foreman.Foreman.addRepoTo(hub, folder).whenComplete((ack, err2) -> say(mc, err2 != null || !ack.ok()
					? "Couldn't connect " + folder + " to '" + hub.id() + "': " + (err2 != null ? err2.getMessage() : ack.error())
					: "Hub '" + hub.id() + "' is working on " + folder + (made ? " (a new git repo)" : "") + ". Give its team a goal in the console."));
			}));
		}
	}

	private static void say(Minecraft mc, String text) {
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal(text));
		}
	}

	/** Makes {@code folder} a git repo with a first commit unless it is one; true when it did. Any thread. */
	static boolean prepareRepo(String folder, String title) {
		try {
			java.nio.file.Path dir = java.nio.file.Path.of(folder);
			if (java.nio.file.Files.isDirectory(dir.resolve(".git"))) {
				return false;
			}
			java.nio.file.Files.createDirectories(dir);
			if (!java.nio.file.Files.exists(dir.resolve("README.md"))) {
				java.nio.file.Files.writeString(dir.resolve("README.md"), "# " + title + "\n");
			}
			git(dir, "init", "-b", "main");
			git(dir, "add", "-A");
			boolean named = !gitOut(dir, "config", "--get", "user.name").isBlank();
			if (named) {
				git(dir, "commit", "-m", "Initial commit");
			} else {
				git(dir, "-c", "user.name=AgentCraft", "-c", "user.email=agentcraft@localhost", "commit", "-m", "Initial commit");
			}
			return true;
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	private static void git(java.nio.file.Path dir, String... args) throws java.io.IOException, InterruptedException {
		String out = gitOut(dir, args);
		dev.agentcraft.AgentCraft.LOGGER.debug("git {}: {}", String.join(" ", args), out);
	}

	private static String gitOut(java.nio.file.Path dir, String... args) throws java.io.IOException, InterruptedException {
		java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("git"));
		cmd.addAll(java.util.List.of(args));
		Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		int code = p.waitFor();
		// config --get exits 1 when unset: that is an answer, not a failure
		if (code != 0 && !(args.length > 0 && args[0].equals("config"))) {
			throw new java.io.IOException("git " + String.join(" ", args) + " failed: " + out.trim());
		}
		return code == 0 ? out.trim() : "";
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
			dev.agentcraft.AgentCraft.LOGGER.info("Hubs overview: '{}' has no spawn anchor (studio not built)", hub.id());
			mc.player.sendSystemMessage(Component.literal("Hub '" + hub.id() + "' has no studio yet: /ac hub build " + hub.id()));
			return;
		}
		answerOnArrival = answer ? hub.id() : null;
		MinecraftServer server = mc.getSingleplayerServer();
		mc.player.sendSystemMessage(Component.literal("Travelling to " + hub.name() + "..."));
		dev.agentcraft.AgentCraft.LOGGER.info("Hubs overview: travelling to '{}' at {} {} {} (singleplayer server: {})", hub.id(), spawn.x(), spawn.y(),
			spawn.z(), server != null);
		if (server == null) {
			mc.player.connection.sendCommand("agentcraft hub tp " + hub.id());
			return;
		}
		UUID who = mc.player.getUUID();
		String name = hub.name();
		server.execute(() -> {
			ServerPlayer p = server.getPlayerList().getPlayer(who);
			if (p == null) {
				dev.agentcraft.AgentCraft.LOGGER.warn("Hubs overview: no server player {} to teleport", who);
				return;
			}
			boolean ok = p.teleportTo(server.overworld(), spawn.x(), spawn.y(), spawn.z(), Set.of(), spawn.yaw(), 0f, true);
			dev.agentcraft.AgentCraft.LOGGER.info("Hubs overview: teleport to '{}' {} (player now at {} {} {})", name, ok ? "done" : "REFUSED", p.getX(),
				p.getY(), p.getZ());
			if (!ok) {
				// the plain /tp path as a fallback, with the player's own permission
				server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f 0",
					spawn.x(), spawn.y(), spawn.z(), spawn.yaw()));
			}
		});
	}
}
