package dev.agentcraft.client.taskwall;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Hub;
import dev.agentcraft.client.foreman.Hubs;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.monitor.DisplayDraw;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.client.ui.WorldUi;
import dev.agentcraft.hq.Theme;
import dev.agentcraft.layout.HubRegistry;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.FormattedCharSequence;

/**
 * The hub wall: a task_board panel bound to {@code "hubs"} shows every hub instead of tasks (the
 * courtyard's board, {@code NetworkBuilder}). One paper card per hub: the theme's colour down the
 * edge, name, goal (two lines), progress bar and percent, task counts, the team on shift (face +
 * live state dot) and a clay badge when decisions wait there; offline hubs say so. Every hub's link
 * is live ({@link Hubs}), so the wall is current for studios far away. Right-click opens the hubs
 * overview.
 */
final class HubWall {
	static final String BINDING = "hubs";
	/** Coarse pixels so the cards read from the courtyard (the task wall uses ~64 for reading up close). */
	static final int PPB = 36;
	private static final float Z = DisplayDraw.Z_STEP;

	private HubWall() {
	}

	static void draw(PoseStack ps, SubmitNodeCollector c, TaskBoard b, int light) {
		Font font = Minecraft.getInstance().font;
		List<Hub> hubs = Hubs.all();
		if (hubs.isEmpty()) {
			return;
		}
		float trim = b.ppb * TaskBoard.TRIM / 16f;
		float pad = 6;
		float x0 = trim + pad, y0 = trim + pad, x1 = b.pw - trim - pad, y1 = b.ph - trim - pad;
		// the same grid the courtyard builder sized the wall for (NetworkBuilder.WALL_COLS)
		int cols = Math.max(1, Math.min(hubs.size(), 5));
		int rows = (hubs.size() + cols - 1) / cols;
		float gap = 6;
		float cw = ((x1 - x0) - gap * (cols - 1)) / cols;
		float ch = ((y1 - y0) - gap * (rows - 1)) / rows;
		TextureAtlasSprite paper = WorldUi.sprite(Kit.card("todo"));
		// paper cards (one batch)
		c.order(0).submitCustomGeometry(ps, WorldUi.guiAtlasSolid(), (pose, vc) -> {
			for (int i = 0; i < hubs.size(); i++) {
				float cx = x0 + (i % cols) * (cw + gap);
				float cy = y0 + (i / cols) * (ch + gap);
				DisplayDraw.nineSlice(pose, vc, paper, cx, cy, cw, ch, 2 * Z, 0xFFFFFFFF, light, 1);
			}
		});
		DisplayDraw.Rects r = b.lanes.clear();
		int ink = UiStyle.color("paper.text", 0xFF1F1E1D);
		int muted = UiStyle.color("paper.muted", 0xFF655E55);
		for (int i = 0; i < hubs.size(); i++) {
			Hub hub = hubs.get(i);
			float cx = x0 + (i % cols) * (cw + gap);
			float cy = y0 + (i / cols) * (ch + gap);
			HubRegistry.Hub reg = HubRegistry.get(hub.id());
			Theme theme = Theme.byId(reg == null ? null : reg.theme());
			// theme colour down the left edge
			r.add(cx + 2, cy + 3, cx + 5, cy + ch - 4, 3 * Z, theme.accent, light);
			ForemanState s = hub.state();
			boolean live = hub.connected() && s.hasData();
			Goal goal = live ? s.goal() : null;
			float tx = cx + 10;
			float tw = cw - 16;
			float ty = cy + 6;
			// progress bar under the goal
			float barY = cy + 6 + 11 + 20 + 3;
			r.add(tx, barY, tx + tw, barY + 4, 3 * Z, UiStyle.color("palette.ui.edge", 0xFFC9BBA3), light); // solid surface: a paper-tone track
			if (goal != null) {
				float fill = tw * (float) Math.max(0, Math.min(1, goal.progress()));
				r.add(tx, barY, tx + fill, barY + 4, 3.5f * Z, goal.progress() >= 1 ? UiStyle.SAGE : UiStyle.TEAL, light);
			}
			int open = live ? s.openDecisions().size() : 0;
			if (open > 0) {
				String t = open + (open == 1 ? " decision" : " decisions");
				float bw = font.width(t) + 8;
				r.add(cx + cw - 6 - bw, cy + ch - 15, cx + cw - 6, cy + ch - 5, 3 * Z, UiStyle.CLAY_DARK, light);
			}
			ps.pushPose();
			ps.translate(0, 0, 4 * Z);
			// name + where it is
			String name = TextUtil.ellipsize(font, hub.name(), (int) (tw - 4));
			WorldUi.submitText(ps, c, name, tx, ty, ink, light);
			String sub = hub == Hubs.active() ? "you are here" : theme.name;
			WorldUi.submitText(ps, c, TextUtil.ellipsize(font, sub, (int) (tw - font.width(name) - 8)), tx + font.width(name) + 6, ty, muted, light);
			// goal, two lines
			String gtext = goal != null ? goal.text().replace('\n', ' ') : live ? "No goal yet" : Hubs.mismatch(hub) != null ? "Wrong Foreman on its port"
				: "Foreman offline";
			List<FormattedCharSequence> lines = TextUtil.wrap(font, gtext, (int) tw);
			for (int l = 0; l < Math.min(2, lines.size()); l++) {
				WorldUi.submitText(ps, c, lines.get(l), tx, ty + 11 + l * 10, goal != null ? ink : muted, light);
			}
			if (goal != null) {
				String pct = Math.round(goal.progress() * 100) + "%";
				WorldUi.submitText(ps, c, pct, tx + tw - font.width(pct), barY + 6, muted, light);
			}
			// task counts
			if (live) {
				int todo = 0, doing = 0, review = 0, done = 0;
				for (Task t : s.tasks().values()) {
					if (goal != null && t.goalId() != null && !goal.id().equals(t.goalId())) {
						continue;
					}
					switch (t.status()) {
						case TODO, BLOCKED -> todo++;
						case DOING -> doing++;
						case REVIEW -> review++;
						case DONE -> done++;
						default -> {
						}
					}
				}
				String counts = String.format(Locale.ROOT, "%d todo · %d doing · %d review · %d done", todo, doing, review, done);
				WorldUi.submitText(ps, c, TextUtil.ellipsize(font, counts, (int) (tw - 26)), tx, barY + 6, muted, light);
			}
			// team: face + state dot for every agent on shift
			float ay = barY + 18;
			float ax = tx;
			if (live) {
				for (Agent a : s.agents().values()) {
					if (!a.isActive()) {
						continue;
					}
					if (ax + 20 > cx + cw - 6) {
						break;
					}
					DisplayDraw.submitTexture(ps, c, AgentCraft.id("textures/gui/portrait/" + a.id() + ".png"), ax, ay, 8, 8, 0f, 0xFFFFFFFF, light);
					WorldUi.submitSprite(ps, c, WorldUi.Layer.SOLID, DisplayDraw.dot(a.isPaused() ? "idle" : a.state().family(), false), ax + 10, ay + 1,
						7, 7, 0f, 0xFFFFFFFF, light);
					ax += 21;
				}
			}
			if (open > 0) {
				String t = open + (open == 1 ? " decision" : " decisions");
				float bw = font.width(t) + 8;
				WorldUi.submitText(ps, c, t, cx + cw - 6 - bw + 4, cy + ch - 14, UiStyle.CREAM, light);
			}
			ps.popPose();
		}
		r.submit(ps, c);
	}
}
