package dev.agentcraft.client.taskwall;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.monitor.DisplayDraw;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.client.ui.WorldUi;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Help boards in the town's plaza: a task_board panel bound to {@code "help:<page>"} shows one page of
 * how-to (keys and clicks, console commands, hub and town commands) on a paper sheet: a title, then a
 * row per command with what it does. Keys are the player's own bindings, so a rebound console key
 * reads right.
 */
final class HelpBoard {
	static final String PREFIX = "help:";
	private static final float Z = DisplayDraw.Z_STEP;

	private record Row(String what, String does) {
	}

	private record Page(String title, String note, List<Row> rows) {
	}

	private HelpBoard() {
	}

	private static String key(KeyMapping k, String fallback) {
		return k == null ? fallback : Keys.label(k);
	}

	private static Page page(String id) {
		return switch (id) {
			case "keys" -> new Page("Keys & clicks", "rebind them in Options, Controls, AgentCraft", List.of(
				new Row(key(Keys.console, "`"), "open the console: type a goal for the team"),
				new Row(key(Keys.decisions, "J"), "answer what's waiting: questions, permissions, merges"),
				new Row(key(Keys.hubs, "H"), "every hub at a glance; Go and Answer travel there"),
				new Row(key(Keys.terminal, "Enter"), "at a console terminal: open the console"),
				new Row("right-click agent", "its card: state, task, log; message, pause, stop"),
				new Row("right-click podium", "the decisions waiting in this studio"),
				new Row("right-click task card", "retry, prioritize, reassign or cancel it"),
				new Row("right-click hub wall", "the hubs overview")));
			case "console" -> new Page("Console commands", "type them in the console (" + key(Keys.console, "`") + ")", List.of(
				new Row("any text", "a new goal for Marlow and the team"),
				new Row("@kit text", "message one agent (@all: everyone)"),
				new Row("/hub [name]  /hubs", "talk to another hub (alone: the next); list them"),
				new Row("/answer [d4] <n> [text]", "answer a decision by button number"),
				new Row("/status", "goal, agents, tasks, decisions, spend"),
				new Row("/diff [@agent]", "review a worktree's changes"),
				new Row("/pause @x  /resume @x", "pause an agent, keep its task"),
				new Row("/stop @x  /spawn @x", "take an agent off shift, bring one on"),
				new Row("/task t3 retry", "also cancel, prioritize, reassign @x"),
				new Row("/repo add <path>  /repos", "connect a git repo, list repos"),
				new Row("/help", "everything else")));
			case "hubs" -> new Page("Hubs & town", "type them in chat (T); /ac works for /agentcraft", List.of(
				new Row("/ac hub list", "every hub: spot, port, theme"),
				new Row("/ac hub create <id> [name]", "a new project in the next free spot"),
				new Row("/ac hub tp <id>", "go to a hub's studio"),
				new Row("/ac hub theme <id> <theme>", "warm, cherry, birch, ember, midnight"),
				new Row("/ac hub build <id> [force]", "repair a studio (force: reset your changes too)"),
				new Row("/ac hub paths", "rebuild the roads and this plaza"),
				new Row("/ac hub layout compass", "build this town from a line of hubs, or tighten an older one"),
				new Row("/ac hq [force]", "repair the main studio")));
			case "freelance" -> new Page("Scout, the freelancer", "any model, one job at a time", List.of(
				new Row("right-click terminal", "pick a model and repo, type the request"),
				new Row("Task", "changes in its own branch; review the merge (J)"),
				new Row("Ask", "a question about the code; the answer comes in chat"),
				new Row("Chat", "no repo needed; sees every hub, read-only"),
				new Row("@scout <text>", "console: carry on the chat"),
				new Row("@scout /model <id>", "console: switch model (e.g. openai/gpt-5-mini)"),
				new Row("OPENROUTER_API_KEY", "your key, set in Windows (never in the world)"),
				new Row("open.baseUrl", "config.json: a local Ollama or LM Studio instead")));
			default -> new Page("AgentCraft", "", List.of());
		};
	}

	static void draw(PoseStack ps, SubmitNodeCollector c, TaskBoard b, int light, String pageId) {
		Font font = Minecraft.getInstance().font;
		Page page = page(pageId);
		float trim = b.ppb * TaskBoard.TRIM / 16f;
		float x0 = trim + 5, y0 = trim + 5, x1 = b.pw - trim - 5, y1 = b.ph - trim - 5;
		TextureAtlasSprite paper = WorldUi.sprite(Kit.card("todo"));
		c.order(0).submitCustomGeometry(ps, WorldUi.guiAtlasSolid(), (pose, vc) ->
			DisplayDraw.nineSlice(pose, vc, paper, x0, y0, x1 - x0, y1 - y0, 2 * Z, 0xFFFFFFFF, light, 1));
		int ink = UiStyle.color("paper.text", 0xFF1F1E1D);
		int muted = UiStyle.color("paper.muted", 0xFF655E55);
		float tx = x0 + 8, tw = x1 - x0 - 16;
		// title band with the note on the right
		DisplayDraw.Rects r = b.lanes.clear();
		r.add(tx, y0 + 16, tx + tw, y0 + 17, 3 * Z, UiStyle.BRASS, light);
		r.submit(ps, c);
		ps.pushPose();
		ps.translate(0, 0, 4 * Z);
		WorldUi.submitText(ps, c, page.title(), tx, y0 + 5, ink, light);
		String note = TextUtil.ellipsize(font, page.note(), (int) (tw - font.width(page.title()) - 12));
		WorldUi.submitText(ps, c, note, tx + tw - font.width(note), y0 + 5, muted, light);
		// rows: the command column as wide as its longest entry (at most 55%)
		int cmdW = 0;
		for (Row row : page.rows()) {
			cmdW = Math.max(cmdW, font.width(row.what()));
		}
		cmdW = (int) Math.min(cmdW, tw * 0.55f);
		float rowH = Math.max(10, Math.min(12, (y1 - y0 - 26) / Math.max(1, page.rows().size())));
		float y = y0 + 22;
		for (Row row : page.rows()) {
			if (y + 9 > y1 - 2) {
				break;
			}
			WorldUi.submitText(ps, c, TextUtil.ellipsize(font, row.what(), cmdW), tx, y, ink, light);
			WorldUi.submitText(ps, c, TextUtil.ellipsize(font, row.does(), (int) (tw - cmdW - 10)), tx + cmdW + 10, y, muted, light);
			y += rowH;
		}
		ps.popPose();
	}
}
