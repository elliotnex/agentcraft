package dev.agentcraft.hq;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.layout.HubRegistry;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /agentcraft hub ...}: several studios in one world, one per project.
 * <pre>
 * /agentcraft hub list                  every hub, its slot, Foreman port and whether it is built
 * /agentcraft hub create &lt;id&gt; [name]    new hub in the next slot: builds its studio and takes you there
 * /agentcraft hub tp &lt;id&gt;               go to a hub's entrance
 * /agentcraft hub build &lt;id&gt; [force]    (re)build a hub's studio
 * </pre>
 */
final class HubCommands {
	private HubCommands() {
	}

	static void register() {
		AgentCraftCommands.sub(root -> root.then(Commands.literal("hub")
			.then(Commands.literal("list").executes(HubCommands::list))
			.then(Commands.literal("create")
				.then(Commands.argument("id", StringArgumentType.word())
					.executes(ctx -> create(ctx, ""))
					.then(Commands.argument("name", StringArgumentType.greedyString())
						.executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "name"))))))
			.then(Commands.literal("tp")
				.then(Commands.argument("id", StringArgumentType.word()).suggests((ctx, b) -> {
					HubRegistry.all().forEach(h -> b.suggest(h.id()));
					return b.buildFuture();
				}).executes(HubCommands::tp)))
			.then(Commands.literal("build")
				.then(Commands.argument("id", StringArgumentType.word()).suggests((ctx, b) -> {
					HubRegistry.all().forEach(h -> b.suggest(h.id()));
					return b.buildFuture();
				})
					.executes(ctx -> build(ctx, false))
					.then(Commands.literal("force").executes(ctx -> build(ctx, true)))))));
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		for (HubRegistry.Hub h : HubRegistry.all()) {
			boolean built = !Anchors.of(h.id()).isEmpty();
			String port = h.port() == 0 ? "default port" : "port " + h.port();
			ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s \"%s\": slot %d (x %d), %s, %s", h.id(), h.name(),
				h.slot(), h.originX(), port, built ? "built" : "not built")), false);
		}
		return HubRegistry.all().size();
	}

	private static int create(CommandContext<CommandSourceStack> ctx, String name) {
		String id = StringArgumentType.getString(ctx, "id");
		HubRegistry.Hub hub;
		try {
			hub = HubRegistry.create(ctx.getSource().getServer(), id, name);
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		if (buildHub(ctx, hub, false) == 0) {
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Hub '" + hub.id() + "' created in slot " + hub.slot() + " (Foreman port "
			+ hub.port() + ")"), true);
		teleport(ctx, hub);
		return 1;
	}

	private static int tp(CommandContext<CommandSourceStack> ctx) {
		HubRegistry.Hub hub = HubRegistry.get(StringArgumentType.getString(ctx, "id"));
		if (hub == null) {
			ctx.getSource().sendFailure(Component.literal("No hub '" + StringArgumentType.getString(ctx, "id") + "' (see /agentcraft hub list)"));
			return 0;
		}
		return teleport(ctx, hub);
	}

	private static int build(CommandContext<CommandSourceStack> ctx, boolean force) {
		HubRegistry.Hub hub = HubRegistry.get(StringArgumentType.getString(ctx, "id"));
		if (hub == null) {
			ctx.getSource().sendFailure(Component.literal("No hub '" + StringArgumentType.getString(ctx, "id") + "'"));
			return 0;
		}
		return buildHub(ctx, hub, force);
	}

	private static int buildHub(CommandContext<CommandSourceStack> ctx, HubRegistry.Hub hub, boolean force) {
		HqBuilder builder = HqBuilders.get(HqBuilders.defaultId());
		Anchors.Layout layout;
		try {
			layout = HqFeature.buildAndPublish(ctx.getSource().getLevel(), builder, new HqBuilder.Options(force), hub);
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.error("Building hub '{}' failed", hub.id(), e);
			ctx.getSource().sendFailure(Component.literal("Building hub '" + hub.id() + "' failed: " + e.getMessage()));
			return 0;
		}
		String report = HqFeature.lastReport();
		ctx.getSource().sendSuccess(() -> Component.literal("Built hub '" + hub.id() + "': " + layout.anchors().size() + " anchors"
			+ (report == null ? "" : ". " + report)), true);
		return 1;
	}

	private static int teleport(CommandContext<CommandSourceStack> ctx, HubRegistry.Hub hub) {
		Anchor spawn = Anchors.of(hub.id()).get(AnchorNames.SPAWN);
		if (spawn == null) {
			ctx.getSource().sendFailure(Component.literal("Hub '" + hub.id() + "' has no studio yet: /agentcraft hub build " + hub.id()));
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFailure(Component.literal("Only a player can be teleported"));
			return 0;
		}
		player.teleportTo(ctx.getSource().getLevel(), spawn.x(), spawn.y(), spawn.z(), java.util.Set.of(), spawn.yaw(), 0f, true);
		return 1;
	}
}
