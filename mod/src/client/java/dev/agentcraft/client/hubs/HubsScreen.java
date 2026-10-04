package dev.agentcraft.client.hubs;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Hub;
import dev.agentcraft.client.foreman.Hubs;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Every hub at a glance: one card per hub with its goal and progress, task counts, its team (face +
 * state) and how many decisions wait there. {@code Go} teleports to the hub's studio; {@code Answer}
 * goes there and opens its decisions. Keys: 1-9 go to that hub, mouse wheel scrolls.
 *
 * <p>Every hub's Foreman link is live all the time ({@link Hubs}), so this shows real data for
 * studios far away too.
 */
public class HubsScreen extends Screen {
	private static final int W = 340;
	private static final int CARD_H = 74;
	private final List<Btn> buttons = new ArrayList<>();
	private int scroll;
	private int maxScroll;

	private record Btn(String action, String hub, int x, int y, int w) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + 20;
		}
	}

	public HubsScreen() {
		super(Component.literal("Hubs"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractTransparentBackground(g);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractRenderState(g, mouseX, mouseY, partial);
		buttons.clear();
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		Kit.Padding pad = Kit.padding("panel_paper");
		int inner = W - pad.left() - pad.right();
		List<Hub> hubs = Hubs.all();
		int listH = Math.min(hubs.size() * CARD_H, Math.max(CARD_H, height - 80));
		maxScroll = Math.max(0, hubs.size() * CARD_H - listH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		int h = pad.top() + 14 + 8 + listH + 26 + pad.bottom();
		int px = (width - W) / 2;
		int py = Math.max(4, (height - h) / 2);
		Panels.panel(g, px, py, W, h);
		int x = px + pad.left();
		int y = py + pad.top();
		int waiting = 0;
		for (Hub hub : hubs) {
			waiting += hub.connected() ? hub.state().openDecisions().size() : 0;
		}
		Panels.header(g, font, "Hubs  ·  " + hubs.size() + (waiting > 0 ? "  ·  " + waiting + " waiting on you" : ""), x, y, inner);
		y += 14 + 8;
		int listTop = y;
		g.enableScissor(x - 6, listTop, x + inner + 2, listTop + listH);
		int i = 0;
		for (Hub hub : hubs) {
			int cy = listTop + i * CARD_H - scroll;
			if (cy + CARD_H > listTop && cy < listTop + listH) {
				card(g, hub, i + 1, x, cy, inner, mouseX, mouseY, listTop, listTop + listH);
			}
			i++;
		}
		g.disableScissor();
		int fy = listTop + listH + 5;
		String keys = "1-" + Math.min(9, hubs.size()) + " go to a hub  ·  N new hub" + (maxScroll > 0 && scroll < maxScroll ? "  ·  scroll for more" : "");
		Panels.text(g, font, keys, x, fy + 6, muted);
		String add = "New hub";
		int aw = UiBits.buttonWidth(font, add, 0);
		boolean hover = mouseX >= x + inner - aw && mouseX < x + inner && mouseY >= fy && mouseY < fy + 20;
		UiBits.button(g, font, add, 0, x + inner - aw, fy, aw, true, hover ? UiBits.ButtonState.HOVER : UiBits.ButtonState.NORMAL, false);
		buttons.add(new Btn("new", "", x + inner - aw, fy, aw));
	}

	private void card(GuiGraphicsExtractor g, Hub hub, int number, int x, int y, int w, int mx, int my, int clipTop, int clipBottom) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		ForemanState s = hub.state();
		boolean here = hub == Hubs.active();
		boolean live = hub.connected() && s.hasData();
		Goal goal = live ? s.goal() : null;
		int open = live ? s.openDecisions().size() : 0;
		// ---- the hub's theme colour down the left edge
		dev.agentcraft.layout.HubRegistry.Hub reg = dev.agentcraft.layout.HubRegistry.get(hub.id());
		int accent = dev.agentcraft.hq.Theme.byId(reg == null ? null : reg.theme()).accent;
		g.fill(x - 5, y - 1, x - 3, y + CARD_H - 12, accent);
		// ---- name row
		String family = !live ? "idle" : open > 0 ? "waiting" : goal != null && goal.progress() < 1 ? "working" : "done";
		Panels.dot(g, family, x + 1, y + 2, false);
		String num = number <= 9 ? number + "  " : "";
		Panels.text(g, font, num, x + 12, y + 1, muted);
		int nx = x + 12 + font.width(num);
		Panels.text(g, font, hub.name(), nx, y + 1, ink);
		String held = Hubs.mismatch(hub);
		String sub = held != null ? held : here ? "you are here" : !live ? "Foreman " + (hub.state().link().everSynced() ? "reconnecting" : "offline") : "";
		if (!s.repos().isEmpty() && live) {
			var repo = goal != null && goal.repoId() != null && s.repos().containsKey(goal.repoId()) ? s.repos().get(goal.repoId())
				: s.repos().values().iterator().next();
			sub = (sub.isEmpty() ? "" : sub + "  ·  ") + repo.name();
		}
		int pillW = 0;
		if (open > 0) {
			String t = open + (open == 1 ? " decision" : " decisions");
			pillW = UiBits.dotPillWidth(font, t) + 6;
			UiBits.dotPill(g, font, "waiting", t, x + w - pillW + 6, y - 1, UiStyle.CLAY_DARK);
		}
		int subX = nx + font.width(hub.name()) + 8;
		Panels.text(g, font, TextUtil.ellipsize(font, sub, Math.max(0, x + w - pillW - subX)), subX, y + 1, muted);
		// ---- goal + progress
		int gy = y + 14;
		String goalText = goal != null ? UiBits.oneLine(goal.text()) : live ? "No goal yet" : "No connection";
		String pct = goal != null ? Math.round(goal.progress() * 100) + "%" : "";
		Panels.text(g, font, TextUtil.ellipsize(font, goalText, w - 12 - font.width(pct) - 8), x + 10, gy, goal != null ? ink : muted);
		Panels.text(g, font, pct, x + w - font.width(pct), gy, muted);
		int by = gy + 12;
		g.fill(x + 10, by, x + w, by + 4, UiStyle.withAlpha(UiStyle.WALNUT, 40));
		if (goal != null) {
			int fill = (int) ((w - 10) * Math.max(0, Math.min(1, goal.progress())));
			g.fill(x + 10, by, x + 10 + fill, by + 4, goal.progress() >= 1 ? UiStyle.SAGE : UiStyle.TEAL);
		}
		// ---- task counts
		int ty = by + 8;
		if (live) {
			int todo = 0, doing = 0, review = 0, done = 0, blocked = 0;
			for (Task t : s.tasks().values()) {
				if (goal != null && t.goalId() != null && !goal.id().equals(t.goalId())) {
					continue;
				}
				switch (t.status()) {
					case TODO -> todo++;
					case DOING -> doing++;
					case REVIEW -> review++;
					case DONE -> done++;
					case BLOCKED -> blocked++;
					default -> {
					}
				}
			}
			String counts = String.format(Locale.ROOT, "%d todo  ·  %d doing  ·  %d review  ·  %d done%s", todo, doing, review, done,
				blocked > 0 ? "  ·  " + blocked + " blocked" : "");
			Panels.text(g, font, TextUtil.ellipsize(font, counts, w - 10), x + 10, ty, muted);
		}
		// ---- team: face + state dot for every agent on shift
		int ay = ty + 13;
		int ax = x + 10;
		if (live) {
			for (Agent a : s.agents().values()) {
				if (!a.isActive()) {
					continue;
				}
				UiBits.face(g, a.id(), ax, ay, 1);
				String fam = a.isPaused() ? "idle" : a.state().family();
				Panels.dot(g, fam, ax + 10, ay + 1, false);
				String n = a.name();
				Panels.text(g, font, n, ax + 19, ay, UiBits.nameOnLight(a.id()));
				ax += 19 + font.width(n) + 10;
				if (ax > x + w - 160) {
					break;
				}
			}
		}
		// ---- buttons (right)
		int bx = x + w;
		if (open > 0) {
			bx = button(g, "answer", hub, "Answer", bx, ay - 6, true, mx, my, clipTop, clipBottom);
		}
		boolean built = !Anchors.of(hub.id()).isEmpty();
		if (!here && built) {
			bx = button(g, "go", hub, "Go", bx, ay - 6, open == 0, mx, my, clipTop, clipBottom);
		}
		if (!dev.agentcraft.hq.FreelancePavilion.HUB.equals(hub.id())) {
			button(g, "settings", hub, "Settings", bx, ay - 6, false, mx, my, clipTop, clipBottom);
		}
		if (number < Hubs.all().size()) {
			Panels.divider(g, x, y + CARD_H - 8, w);
		}
	}

	/** Draws a button ending at {@code right}; returns its left edge minus a gap. */
	private int button(GuiGraphicsExtractor g, String action, Hub hub, String label, int right, int y, boolean primary, int mx, int my, int clipTop,
		int clipBottom) {
		int bw = UiBits.buttonWidth(font, label, 0);
		int bx = right - bw;
		boolean visible = y >= clipTop && y + 20 <= clipBottom;
		boolean hover = visible && mx >= bx && mx < bx + bw && my >= y && my < y + 20;
		UiBits.button(g, font, label, 0, bx, y, bw, primary, hover ? UiBits.ButtonState.HOVER : UiBits.ButtonState.NORMAL, false);
		if (visible) {
			buttons.add(new Btn(action, hub.id(), bx, y, bw));
		}
		return bx - 6;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) { // 26.x numbers buttons from 1 (SDL)
			for (Btn b : List.copyOf(buttons)) {
				if (b.hit(event.x(), event.y())) {
					dev.agentcraft.AgentCraft.LOGGER.info("Hubs overview: click {} {} hit {} '{}'", (int) event.x(), (int) event.y(), b.action(), b.hub());
					press(b.action(), b.hub());
					return true;
				}
			}
			dev.agentcraft.AgentCraft.LOGGER.info("Hubs overview: click {} {} hit no button ({} buttons: {})", (int) event.x(), (int) event.y(), buttons.size(),
				buttons.stream().map(b -> b.action() + ":" + b.hub() + "@" + b.x() + "," + b.y() + "+" + b.w()).toList());
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (dy * 20)));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		if (k == InputConstants.KEY_N) {
			press("new", "");
			return true;
		}
		if (k >= InputConstants.KEY_1 && k <= InputConstants.KEY_9) {
			List<Hub> hubs = Hubs.all();
			int i = k - InputConstants.KEY_1;
			if (i < hubs.size()) {
				press("go", hubs.get(i).id());
				return true;
			}
		}
		return super.keyPressed(event);
	}

	/** go | answer for a hub (also the dev command). */
	public void press(String action, String hubId) {
		if (action.equals("new")) {
			minecraft.gui.setScreen(new NewHubScreen(this));
			return;
		}
		@Nullable Hub hub = Hubs.get(hubId);
		if (hub != null && action.equals("settings")) {
			minecraft.gui.setScreen(new HubSettingsScreen(hub, this));
			return;
		}
		if (hub == null) {
			return;
		}
		HubsFeature.travel(hub, action.equals("answer"));
		onClose();
	}
}
