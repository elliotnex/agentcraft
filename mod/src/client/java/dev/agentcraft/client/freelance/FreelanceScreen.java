package dev.agentcraft.client.freelance;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Hub;
import dev.agentcraft.client.foreman.Hubs;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.ForemanStatus;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.hq.FreelancePavilion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The freelancer's terminal (the console terminal in the plaza pavilion): pick a model (any id the
 * endpoint knows; a few presets one click away), a repository from any hub's project, task or
 * question, type the request and send it to Scout. Below: Scout's recent jobs with their status, and
 * the latest answer. Scout's own Foreman runs the open backend (OpenRouter by default).
 */
public class FreelanceScreen extends Screen {
	private static final int W = 360;
	/** One click away; any other OpenRouter (or local) model id can be typed in the field. */
	static final List<String> PRESETS = List.of("openai/gpt-5-mini", "anthropic/claude-sonnet-4.5", "google/gemini-2.5-flash", "deepseek/deepseek-chat-v3.1",
		"qwen/qwen3-coder");
	private static @Nullable String lastRepoPath;
	private static boolean lastAsk;

	private record Btn(String action, int arg, int x, int y, int w, int h) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** A repository some hub's project has, by path. */
	record RepoChoice(String path, String name, String hubName) {
	}

	private final List<Btn> buttons = new ArrayList<>();
	private @Nullable EditBox model;
	private @Nullable EditBox request;
	private boolean ask = lastAsk;
	private int repoIndex;
	private String note = "";
	private boolean noteBad;
	private boolean sending;
	private int px, py, inner;

	public FreelanceScreen() {
		super(Component.literal("Freelancer"));
	}

	static @Nullable Hub hub() {
		return Hubs.get(FreelancePavilion.HUB);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		Kit.Padding pad = Kit.padding("panel_paper");
		inner = W - pad.left() - pad.right();
		px = (width - W) / 2;
		py = Math.max(4, (height - 236) / 2);
		int x = px + pad.left();
		int y = py + pad.top();
		String current = model != null ? model.getValue() : currentModel();
		model = addRenderableWidget(field(x + 54, y + 25, inner - 58, current, 200, "Model"));
		String req = request != null ? request.getValue() : "";
		request = addRenderableWidget(field(x + 4, y + 110, inner - 8, req, 2000, "Request"));
		setFocused(request);
		request.setFocused(true);
		List<RepoChoice> repos = repos();
		repoIndex = 0;
		for (int i = 0; i < repos.size(); i++) {
			if (repos.get(i).path().equals(lastRepoPath)) {
				repoIndex = i;
			}
		}
	}

	private EditBox field(int x, int y, int w, String value, int max, String label) {
		EditBox f = new EditBox(font, x, y, w, 10, Component.literal(label));
		f.setBordered(false);
		f.setTextShadow(false);
		f.setTextColor(UiStyle.color("paper.text"));
		f.setMaxLength(max);
		f.setValue(value);
		return f;
	}

	static String currentModel() {
		Hub h = hub();
		ForemanStatus st = h == null ? null : h.state().status();
		return st != null && st.model() != null ? st.model() : PRESETS.getFirst();
	}

	/** Every repository of every hub's project (by path, first hub wins), the freelancer's own included. */
	static List<RepoChoice> repos() {
		Map<String, RepoChoice> out = new LinkedHashMap<>();
		Hub fl = hub();
		List<Hub> order = new ArrayList<>(Hubs.all());
		order.remove(fl);
		if (fl != null) {
			order.add(fl);
		}
		for (Hub h : order) {
			for (Repo r : h.state().repos().values()) {
				out.putIfAbsent(norm(r.path()), new RepoChoice(r.path(), r.name(), h == fl ? "freelancer" : h.name()));
			}
		}
		return new ArrayList<>(out.values());
	}

	private static String norm(String path) {
		return path.replace('\\', '/').toLowerCase(Locale.ROOT);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractTransparentBackground(g);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		buttons.clear();
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		Kit.Padding pad = Kit.padding("panel_paper");
		Hub hub = hub();
		ForemanState s = hub == null ? null : hub.state();
		boolean live = hub != null && hub.connected() && s.hasData();
		List<Task> jobs = live ? recent(s) : List.of();
		int h = pad.top() + 150 + Math.max(1, jobs.size()) * 12 + 18 + pad.bottom();
		Panels.panel(g, px, py, W, h);
		int x = px + pad.left();
		int y = py + pad.top();
		// ---- header: Scout, the model it runs on, the connection and spend
		ForemanStatus st = s == null ? null : s.status();
		String state = hub == null ? "no pavilion" : !live ? "Foreman " + (hub.state().link().everSynced() ? "reconnecting" : "starting...") : st != null
			&& st.auth().wire().equals("failed") ? "needs setup" : "ready";
		String spend = st != null && st.costUsd() != null && st.costUsd() > 0 ? String.format(Locale.ROOT, "  ·  $%.3f spent", st.costUsd()) : "";
		Panels.header(g, font, "Scout  ·  freelancer  ·  " + state + spend, x, y, inner);
		y += 22;
		// ---- model: free text, presets below
		Panels.text(g, font, "Model", x, y + 4, muted);
		g.fill(x + 50, y + 1, x + inner, y + 16, UiStyle.withAlpha(UiStyle.WALNUT, 24));
		int cx = x + 50;
		int cy = y + 19;
		for (int i = 0; i < PRESETS.size(); i++) {
			String label = shortModel(PRESETS.get(i));
			int bw = font.width(label) + 8;
			if (cx + bw > x + inner) {
				break;
			}
			boolean on = model != null && model.getValue().equals(PRESETS.get(i));
			boolean hover = mouseX >= cx && mouseX < cx + bw && mouseY >= cy && mouseY < cy + 12;
			g.fill(cx, cy, cx + bw, cy + 12, on ? UiStyle.withAlpha(UiStyle.TEAL, 90) : UiStyle.withAlpha(UiStyle.WALNUT, hover ? 60 : 30));
			Panels.text(g, font, label, cx + 4, cy + 2, on ? ink : muted);
			buttons.add(new Btn("preset", i, cx, cy, bw, 12));
			cx += bw + 4;
		}
		y += 38;
		// ---- repository: cycle through every hub's projects
		List<RepoChoice> repos = repos();
		Panels.text(g, font, "Repo", x, y + 6, muted);
		String repoLabel = repos.isEmpty() ? "no project connected in any hub" : repos.get(Math.floorMod(repoIndex, repos.size())).name() + "  ("
			+ repos.get(Math.floorMod(repoIndex, repos.size())).hubName() + ")";
		int rb = button(g, "repo", 0, "<", x + 50, y, false, mouseX, mouseY);
		Panels.text(g, font, TextUtil.ellipsize(font, repoLabel, inner - 50 - 2 * rb - 16), x + 50 + rb + 6, y + 6, repos.isEmpty() ? muted : ink);
		button(g, "repo", 1, ">", x + inner - rb, y, false, mouseX, mouseY);
		y += 24;
		// ---- mode
		Panels.text(g, font, "Mode", x, y + 6, muted);
		int mx = x + 50;
		mx += button(g, "mode", 0, "Task: change code", mx, y, !ask, mouseX, mouseY) + 6;
		button(g, "mode", 1, "Ask: a question", mx, y, ask, mouseX, mouseY);
		y += 26;
		// ---- the request
		g.fill(x, y - 3, x + inner, y + 13, UiStyle.withAlpha(UiStyle.WALNUT, 24));
		if (request != null && request.getValue().isEmpty() && !request.isFocused()) {
			Panels.text(g, font, ask ? "What do you want to know about the code?" : "What should Scout change?", x + 4, y, muted);
		}
		super.extractRenderState(g, mouseX, mouseY, partial);
		y += 18;
		String send = sending ? "Sending..." : ask ? "Ask Scout" : "Send to Scout";
		int sw = UiBits.buttonWidth(font, send, 0);
		button(g, "send", 0, send, x + inner - sw, y, true, mouseX, mouseY);
		String hint = !note.isEmpty() ? note : setupHint(hub, st);
		Panels.text(g, font, TextUtil.ellipsize(font, hint, inner - sw - 8), x, y + 6, noteBad ? UiStyle.CLAY_DARK : muted);
		y += 26;
		// ---- recent jobs
		Panels.divider(g, x, y - 4, inner);
		if (jobs.isEmpty()) {
			Panels.text(g, font, live ? "No jobs yet. Scout takes one at a time." : "Scout's jobs show here once its Foreman is up.", x, y + 2, muted);
		}
		for (Task t : jobs) {
			String status = t.status().wire();
			String fam = switch (status) {
				case "done" -> "done";
				case "doing" -> "working";
				case "review" -> "waiting";
				case "blocked" -> "error";
				default -> "idle";
			};
			Panels.dot(g, fam, x + 1, y + 3, false);
			String line = t.title() + (t.summary() != null && !t.summary().isBlank() ? "  —  " + UiBits.oneLine(t.summary()) : t.blockedReason() != null
				? "  —  " + t.blockedReason() : "");
			String right = status.equals("review") ? "review: J" : status;
			Panels.text(g, font, TextUtil.ellipsize(font, line, inner - 14 - font.width(right) - 8), x + 12, y + 2, ink);
			Panels.text(g, font, right, x + inner - font.width(right), y + 2, muted);
			y += 12;
		}
	}

	private static String setupHint(@Nullable Hub hub, @Nullable ForemanStatus st) {
		if (hub == null) {
			return "Rebuild the plaza: /ac hub paths";
		}
		if (st != null && st.message() != null && st.auth().wire().equals("failed")) {
			return st.message();
		}
		return "Enter sends  ·  answers show in chat  ·  changes come back as a merge (J)";
	}

	private static List<Task> recent(ForemanState s) {
		List<Task> out = new ArrayList<>(s.tasks().values());
		out.sort(Comparator.comparingLong(Task::updatedAt).reversed());
		return out.subList(0, Math.min(5, out.size()));
	}

	static String shortModel(String m) {
		int i = m.indexOf('/');
		return i >= 0 ? m.substring(i + 1) : m;
	}

	/** Draws a button at (x, y); returns its width. */
	private int button(GuiGraphicsExtractor g, String action, int arg, String label, int x, int y, boolean primary, int mx, int my) {
		int bw = UiBits.buttonWidth(font, label, 0);
		boolean hover = mx >= x && mx < x + bw && my >= y && my < y + 20;
		UiBits.button(g, font, label, 0, x, y, bw, primary, hover ? UiBits.ButtonState.HOVER : UiBits.ButtonState.NORMAL, false);
		buttons.add(new Btn(action, arg, x, y, bw, 20));
		return bw;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (Btn b : List.copyOf(buttons)) {
				if (b.hit(event.x(), event.y())) {
					press(b.action(), b.arg());
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && getFocused() == request) {
			press("send", 0);
			return true;
		}
		return super.keyPressed(event);
	}

	/** preset n | repo 0/1 (back/next) | mode 0/1 (task/ask) | send. Also the dev command. */
	void press(String action, int arg) {
		switch (action) {
			case "preset" -> {
				if (model != null && arg >= 0 && arg < PRESETS.size()) {
					model.setValue(PRESETS.get(arg));
				}
			}
			case "repo" -> {
				List<RepoChoice> repos = repos();
				if (!repos.isEmpty()) {
					repoIndex = Math.floorMod(repoIndex + (arg == 0 ? -1 : 1), repos.size());
					lastRepoPath = repos.get(repoIndex).path();
				}
			}
			case "mode" -> {
				ask = arg == 1;
				lastAsk = ask;
			}
			case "send" -> send();
			default -> {
			}
		}
	}

	void setRequest(String text) {
		if (request != null) {
			request.setValue(text);
		}
	}

	private void send() {
		Hub hub = hub();
		String text = request == null ? "" : request.getValue().trim();
		if (sending || text.isEmpty()) {
			return;
		}
		if (hub == null || !hub.connected()) {
			fail("Scout's Foreman isn't connected yet");
			return;
		}
		List<RepoChoice> repos = repos();
		if (repos.isEmpty()) {
			fail("Connect a project in a hub first (console: /repo add <path>)");
			return;
		}
		RepoChoice repo = repos.get(Math.floorMod(repoIndex, repos.size()));
		lastRepoPath = repo.path();
		String m = model == null || model.getValue().isBlank() ? null : model.getValue().trim();
		sending = true;
		note = "";
		// the freelancer's Foreman knows a repository once it has been added there
		String known = null;
		for (Repo r : hub.state().repos().values()) {
			if (norm(r.path()).equals(norm(repo.path()))) {
				known = r.id();
			}
		}
		CompletableFuture<String> repoId = known != null ? CompletableFuture.completedFuture(known)
			: Foreman.addRepoTo(hub, repo.path()).thenApply(ack -> {
				if (!ack.ok() || ack.result() == null || !ack.result().has("repoId")) {
					throw new IllegalStateException(ack.error() != null ? ack.error() : "could not add " + repo.name());
				}
				return ack.result().get("repoId").getAsString();
			});
		repoId.thenCompose(id -> Foreman.submitGoalTo(hub, text, id, m, ask ? "ask" : "task")).whenComplete((Ack ack, Throwable err) -> {
			sending = false;
			if (err != null) {
				fail(rootMessage(err));
			} else if (!ack.ok()) {
				fail(ack.error() != null ? ack.error() : "Scout couldn't take it");
			} else {
				note = (ask ? "Asked Scout" : "Sent to Scout") + " on " + shortModel(m != null ? m : currentModel()) + (ask ? ": the answer comes in chat"
					: ": you'll get a merge to review");
				noteBad = false;
				if (request != null) {
					request.setValue("");
				}
			}
		});
	}

	private void fail(String why) {
		note = why;
		noteBad = true;
		sending = false;
	}

	private static String rootMessage(Throwable t) {
		Throwable c = t;
		while (c.getCause() != null && c.getCause() != c) {
			c = c.getCause();
		}
		return c.getMessage() != null ? c.getMessage() : c.toString();
	}
}
