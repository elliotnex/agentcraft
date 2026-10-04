package dev.agentcraft.client.freelance;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Hub;
import dev.agentcraft.client.foreman.Hubs;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.ForemanStatus;
import dev.agentcraft.client.foreman.Protocol.ModelInfo;
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
 * The freelancer's terminal (the console terminal in the plaza pavilion): pick a model (type any id,
 * click a preset, or browse the endpoint's live catalog with prices), a repository from any hub's
 * project, a mode (task, question, or a chat that needs no repo and sees every hub read-only), type
 * the request and send it to Scout. A chat carries on while Scout's last one is recent ("New chat"
 * starts over). Below: Scout's recent jobs with status, model and cost. Scout's own Foreman runs the
 * open backend (OpenRouter by default) and serves the model list ({@code models.list}).
 */
public class FreelanceScreen extends Screen {
	private static final int W = 360;
	private static final int ROW_H = 12;
	/** One click away; any other id can be typed, or picked from the live list. */
	static final List<String> PRESETS = List.of("openai/gpt-5-mini", "anthropic/claude-sonnet-4.5", "google/gemini-2.5-flash", "deepseek/deepseek-chat-v3.1",
		"qwen/qwen3-coder");
	/** Modes, as the Foreman names them (goal.submit {@code mode}). */
	static final List<String> MODES = List.of("task", "ask", "chat");
	/** a chat this recent carries on (the Foreman's own window is two hours; stay inside it) */
	private static final long CHAT_IDLE_MS = 110 * 60_000L;
	private static @Nullable String lastRepoPath;
	private static @Nullable String lastMode;
	/** The live catalog, shared by every open of the screen (refetched after ten minutes). */
	private static List<ModelInfo> catalog = List.of();
	private static long catalogAt;
	private static @Nullable String catalogError;
	private static boolean catalogLoading;
	private static boolean toolsOnly = true;

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
	private @Nullable EditBox search;
	private boolean browsing;
	private int listScroll;
	private int listMax;
	private String mode = lastMode != null ? lastMode : repos().isEmpty() ? "chat" : "task";
	/** index into {@link #choices()} */
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
		py = Math.max(4, (height - 260) / 2);
		int x = px + pad.left();
		int y = py + pad.top();
		loadCatalog(false);
		if (browsing) {
			String q = search != null ? search.getValue() : "";
			search = addRenderableWidget(field(x + 4, y + 25, inner - 8, q, 80, "Search models"));
			setFocused(search);
			search.setFocused(true);
			return;
		}
		String current = model != null ? model.getValue() : currentModel();
		model = addRenderableWidget(field(x + 54, y + 25, inner - 58, current, 200, "Model"));
		String req = request != null ? request.getValue() : "";
		request = addRenderableWidget(field(x + 4, y + 122, inner - 8, req, 2000, "Request"));
		setFocused(request);
		request.setFocused(true);
		pickRepo();
	}

	/** Point at the last repo used (or, in a chat, no repo when none was picked). */
	private void pickRepo() {
		List<@Nullable RepoChoice> choices = choices();
		repoIndex = 0;
		for (int i = 0; i < choices.size(); i++) {
			RepoChoice c = choices.get(i);
			if (c == null ? lastRepoPath == null : c.path().equals(lastRepoPath)) {
				repoIndex = i;
			}
		}
	}

	/** The repos this mode can use: a chat may also have none (null, first). */
	private List<@Nullable RepoChoice> choices() {
		List<@Nullable RepoChoice> out = new ArrayList<>();
		if (mode.equals("chat")) {
			out.add(null);
		}
		out.addAll(repos());
		return out;
	}

	/** Scout's chat that a message carries on: the latest one, if it is recent and not cancelled. */
	static @Nullable Task liveChat(@Nullable ForemanState s) {
		if (s == null) {
			return null;
		}
		Task last = null;
		for (Task t : s.tasks().values()) {
			if (t.title().startsWith("Chat: ") && (last == null || t.createdAt() > last.createdAt())) {
				last = t;
			}
		}
		if (last == null || last.status().wire().equals("cancelled") || System.currentTimeMillis() - last.updatedAt() > CHAT_IDLE_MS) {
			return null;
		}
		return last;
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

	/** Fetch the catalog from Scout's Foreman when it is missing or old (or {@code force}). Client thread. */
	static void loadCatalog(boolean force) {
		Hub h = hub();
		if (catalogLoading || h == null || !h.connected()) {
			return;
		}
		if (!force && !catalog.isEmpty() && System.currentTimeMillis() - catalogAt < 10 * 60_000) {
			return;
		}
		catalogLoading = true;
		Foreman.listModels(h, force).whenComplete((list, err) -> {
			catalogLoading = false;
			if (err != null) {
				catalogError = err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
			} else {
				catalog = List.copyOf(list);
				catalogAt = System.currentTimeMillis();
				catalogError = null;
			}
		});
	}

	static List<ModelInfo> catalog() {
		return catalog;
	}

	static @Nullable ModelInfo info(String id) {
		for (ModelInfo m : catalog) {
			if (m.id().equals(id)) {
				return m;
			}
		}
		return null;
	}

	/** "$0.25 in · $2.00 out per M" | "free" | "" (unknown). */
	static String price(@Nullable ModelInfo m) {
		if (m == null || m.promptUsdPerM() == null || m.completionUsdPerM() == null) {
			return "";
		}
		if (m.free()) {
			return "free";
		}
		return "$" + usd(m.promptUsdPerM()) + " in · $" + usd(m.completionUsdPerM()) + " out per M";
	}

	private static String usd(double v) {
		return v >= 10 ? String.format(Locale.ROOT, "%.0f", v) : v >= 0.1 ? String.format(Locale.ROOT, "%.2f", v) : String.format(Locale.ROOT, "%.3f", v);
	}

	private static String context(@Nullable Integer n) {
		if (n == null) {
			return "";
		}
		return n >= 1_000_000 ? String.format(Locale.ROOT, "%.1fM ctx", n / 1_000_000.0).replace(".0M", "M") : (n / 1000) + "K ctx";
	}

	/** The catalog filtered by the search words (each must appear in the id or name) and the tools switch. */
	List<ModelInfo> filtered() {
		String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
		String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
		List<ModelInfo> out = new ArrayList<>();
		for (ModelInfo m : catalog) {
			if (toolsOnly && Boolean.FALSE.equals(m.tools())) {
				continue;
			}
			String hay = (m.id() + " " + m.name()).toLowerCase(Locale.ROOT);
			boolean all = true;
			for (String w : words) {
				all &= hay.contains(w);
			}
			if (all) {
				out.add(m);
			}
		}
		return out;
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
		if (browsing) {
			drawPicker(g, mouseX, mouseY, partial);
			return;
		}
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		Kit.Padding pad = Kit.padding("panel_paper");
		Hub hub = hub();
		ForemanState s = hub == null ? null : hub.state();
		boolean live = hub != null && hub.connected() && s.hasData();
		List<Task> jobs = live ? recent(s) : List.of();
		int h = pad.top() + 162 + Math.max(1, jobs.size()) * ROW_H + 18 + pad.bottom();
		Panels.panel(g, px, py, W, h);
		int x = px + pad.left();
		int y = py + pad.top();
		// ---- header: Scout, the connection and spend
		ForemanStatus st = s == null ? null : s.status();
		String state = hub == null ? "no pavilion" : !live ? "Foreman " + (hub.state().link().everSynced() ? "reconnecting" : "starting...") : st != null
			&& st.auth().wire().equals("failed") ? "needs setup" : "ready";
		String spend = st != null && st.costUsd() != null && st.costUsd() > 0 ? String.format(Locale.ROOT, "  ·  $%.4f spent", st.costUsd()) : "";
		Panels.header(g, font, "Scout  ·  freelancer  ·  " + state + spend, x, y, inner);
		y += 22;
		// ---- model: free text, its price, presets and the live list
		Panels.text(g, font, "Model", x, y + 4, muted);
		g.fill(x + 50, y + 1, x + inner, y + 16, UiStyle.withAlpha(UiStyle.WALNUT, 24));
		String typed = model == null ? "" : model.getValue().trim();
		ModelInfo cur = info(typed);
		String priceLine = cur != null ? join(cur.name(), price(cur), context(cur.contextLength()), Boolean.FALSE.equals(cur.tools()) ? "no tool calling!" : "")
			: catalog.isEmpty() ? catalogLoading ? "loading the model list..." : catalogError != null ? "model list: " + catalogError : ""
				: typed.isEmpty() ? "" : "not in the list (it may still work)";
		Panels.text(g, font, TextUtil.ellipsize(font, priceLine, inner - 50), x + 50, y + 19, cur != null && Boolean.FALSE.equals(cur.tools()) ? UiStyle.CLAY_DARK : muted);
		int cx = x + 50;
		int cy = y + 31;
		String browse = catalog.isEmpty() ? "Browse" : "Browse " + countTools() + " models";
		int browseW = font.width(browse) + 8;
		for (int i = 0; i < PRESETS.size(); i++) {
			String label = shortModel(PRESETS.get(i));
			int bw = font.width(label) + 8;
			if (cx + bw > x + inner - browseW - 4) {
				break;
			}
			boolean on = typed.equals(PRESETS.get(i));
			chip(g, label, cx, cy, bw, on, "preset", i, mouseX, mouseY);
			cx += bw + 4;
		}
		chip(g, browse, x + inner - browseW, cy, browseW, false, "browse", 0, mouseX, mouseY);
		y += 50;
		// ---- repository: cycle through every hub's projects (a chat may have none)
		List<@Nullable RepoChoice> choices = choices();
		Panels.text(g, font, "Repo", x, y + 6, muted);
		RepoChoice picked = choices.isEmpty() ? null : choices.get(Math.floorMod(repoIndex, choices.size()));
		String repoLabel = picked != null ? picked.name() + "  (" + picked.hubName() + ")" : choices.isEmpty() ? "no project connected in any hub"
			: "none: Scout looks across every hub";
		int rb = button(g, "repo", 0, "<", x + 50, y, false, mouseX, mouseY);
		Panels.text(g, font, TextUtil.ellipsize(font, repoLabel, inner - 50 - 2 * rb - 16), x + 50 + rb + 6, y + 6, picked == null ? muted : ink);
		button(g, "repo", 1, ">", x + inner - rb, y, false, mouseX, mouseY);
		y += 24;
		// ---- mode
		Panels.text(g, font, "Mode", x, y + 6, muted);
		int mx = x + 50;
		mx += button(g, "mode", 0, "Task", mx, y, mode.equals("task"), mouseX, mouseY) + 4;
		mx += button(g, "mode", 1, "Ask", mx, y, mode.equals("ask"), mouseX, mouseY) + 4;
		mx += button(g, "mode", 2, "Chat", mx, y, mode.equals("chat"), mouseX, mouseY) + 8;
		String what = switch (mode) {
			case "ask" -> "a question about one repo";
			case "chat" -> "anything; sees every hub, read-only";
			default -> "changes code in its own branch";
		};
		Panels.text(g, font, TextUtil.ellipsize(font, what, x + inner - mx), mx, y + 6, muted);
		y += 26;
		// ---- the request
		Task chat = mode.equals("chat") && live ? liveChat(s) : null;
		g.fill(x, y - 3, x + inner, y + 13, UiStyle.withAlpha(UiStyle.WALNUT, 24));
		if (request != null && request.getValue().isEmpty() && !request.isFocused()) {
			String placeholder = switch (mode) {
				case "ask" -> "What do you want to know about the code?";
				case "chat" -> chat != null ? "Reply to Scout..." : "Ask anything: how are the hubs doing? what have we spent?";
				default -> "What should Scout change?";
			};
			Panels.text(g, font, placeholder, x + 4, y, muted);
		}
		super.extractRenderState(g, mouseX, mouseY, partial);
		y += 18;
		String send = sending ? "Sending..." : switch (mode) {
			case "ask" -> "Ask Scout";
			case "chat" -> chat != null ? "Reply" : "Chat with Scout";
			default -> "Send to Scout";
		};
		int sw = UiBits.buttonWidth(font, send, 0);
		button(g, "send", 0, send, x + inner - sw, y, true, mouseX, mouseY);
		int left = inner - sw - 8;
		if (chat != null) {
			int nw = button(g, "newchat", 0, "New chat", x + inner - sw - 4 - UiBits.buttonWidth(font, "New chat", 0), y, false, mouseX, mouseY);
			left -= nw + 4;
		}
		String hint = !note.isEmpty() ? note : chat != null ? "Carrying on " + chat.id() + (chat.model() != null ? " on " + shortModel(chat.model()) : "")
			: setupHint(hub, st);
		Panels.text(g, font, TextUtil.ellipsize(font, hint, left), x, y + 6, noteBad ? UiStyle.CLAY_DARK : muted);
		y += 26;
		// ---- recent jobs: status, model, cost
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
			String right = join(t.model() != null ? shortModel(t.model()) : "", t.costUsd() != null && t.costUsd() > 0 ? String.format(Locale.ROOT, "$%.4f",
				t.costUsd()) : "", status.equals("review") ? "review: J" : status);
			Panels.text(g, font, TextUtil.ellipsize(font, line, inner - 14 - font.width(right) - 8), x + 12, y + 2, ink);
			Panels.text(g, font, right, x + inner - font.width(right), y + 2, muted);
			y += ROW_H;
		}
	}

	/** The live model list: search, the tools switch, rows with name, price and context; a click picks one. */
	private void drawPicker(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		Kit.Padding pad = Kit.padding("panel_paper");
		int h = Math.min(height - 8, 300);
		int top = Math.max(4, (height - h) / 2);
		py = top;
		Panels.panel(g, px, top, W, h);
		int x = px + pad.left();
		int y = top + pad.top();
		List<ModelInfo> list = filtered();
		String src = hub() != null && hub().state().status() != null && hub().state().status().account() != null ? hub().state().status().account() : "the endpoint";
		Panels.header(g, font, "Models  ·  " + list.size() + (toolsOnly ? " that can use tools" : "") + "  ·  " + src, x, y, inner);
		y += 22;
		// search field (the widget sits at init's y + 25: keep the panel's top where init put it)
		if (search != null) {
			search.setY(y + 3);
		}
		g.fill(x, y, x + inner, y + 15, UiStyle.withAlpha(UiStyle.WALNUT, 24));
		if (search != null && search.getValue().isEmpty()) {
			Panels.text(g, font, "search: gpt, claude sonnet, free, qwen coder...", x + 4, y + 4, muted);
		}
		super.extractRenderState(g, mouseX, mouseY, partial);
		y += 20;
		int listTop = y;
		int footer = 26;
		int listH = top + h - pad.bottom() - footer - listTop;
		int rows = Math.max(1, listH / ROW_H);
		listMax = Math.max(0, list.size() - rows);
		listScroll = Math.max(0, Math.min(listScroll, listMax));
		if (list.isEmpty()) {
			String why = catalogLoading ? "Loading the model list..." : catalogError != null ? "Couldn't load the list: " + catalogError
				: catalog.isEmpty() ? "No list yet (is Scout's Foreman running?)" : "Nothing matches.";
			Panels.text(g, font, TextUtil.ellipsize(font, why, inner), x, y + 2, muted);
		}
		String typed = model == null ? "" : model.getValue().trim();
		for (int i = listScroll; i < Math.min(list.size(), listScroll + rows); i++) {
			ModelInfo m = list.get(i);
			int ry = listTop + (i - listScroll) * ROW_H;
			boolean hover = mouseX >= x && mouseX < x + inner && mouseY >= ry && mouseY < ry + ROW_H;
			if (hover || m.id().equals(typed)) {
				g.fill(x - 2, ry, x + inner + 2, ry + ROW_H, UiStyle.withAlpha(m.id().equals(typed) ? UiStyle.TEAL : UiStyle.WALNUT, hover ? 60 : 40));
			}
			String right = join(price(m), context(m.contextLength()));
			int rw = font.width(right);
			Panels.text(g, font, TextUtil.ellipsize(font, m.name(), inner - rw - 10), x + 2, ry + 2, Boolean.FALSE.equals(m.tools()) ? muted : ink);
			Panels.text(g, font, right, x + inner - rw, ry + 2, m.free() ? UiStyle.SAGE : muted);
			buttons.add(new Btn("pick", i, x - 2, ry, inner + 4, ROW_H));
		}
		int fy = top + h - pad.bottom() - 20;
		int bx = x;
		bx += button(g, "tools", 0, toolsOnly ? "Tools only: on" : "Tools only: off", bx, fy, false, mouseX, mouseY) + 6;
		bx += button(g, "refresh", 0, catalogLoading ? "Loading..." : "Refresh", bx, fy, false, mouseX, mouseY) + 6;
		String back = "Back";
		button(g, "back", 0, back, x + inner - UiBits.buttonWidth(font, back, 0), fy, true, mouseX, mouseY);
		String more = listMax > 0 ? (listScroll + 1) + "-" + Math.min(list.size(), listScroll + rows) + " of " + list.size() : "";
		Panels.text(g, font, more, bx + 4, fy + 6, muted);
	}

	private int countTools() {
		int n = 0;
		for (ModelInfo m : catalog) {
			if (!Boolean.FALSE.equals(m.tools())) {
				n++;
			}
		}
		return n;
	}

	private void chip(GuiGraphicsExtractor g, String label, int x, int y, int w, boolean on, String action, int arg, int mx, int my) {
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + 12;
		g.fill(x, y, x + w, y + 12, on ? UiStyle.withAlpha(UiStyle.TEAL, 90) : UiStyle.withAlpha(UiStyle.WALNUT, hover ? 60 : 30));
		Panels.text(g, font, label, x + 4, y + 2, on ? UiBits.ink() : UiBits.muted());
		buttons.add(new Btn(action, arg, x, y, w, 12));
	}

	private static String join(String... parts) {
		StringBuilder b = new StringBuilder();
		for (String p : parts) {
			if (p != null && !p.isEmpty()) {
				b.append(b.length() == 0 ? "" : "  ·  ").append(p);
			}
		}
		return b.toString();
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
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (browsing) {
			listScroll = Math.max(0, Math.min(listMax, listScroll - (int) Math.signum(dy) * 3));
			return true;
		}
		return super.mouseScrolled(mx, my, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		if (browsing) {
			if (k == InputConstants.KEY_ESCAPE) {
				press("back", 0);
				return true;
			}
			if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) {
				// Enter picks the first match
				press("pick", listScroll);
				return true;
			}
			boolean handled = super.keyPressed(event);
			listScroll = 0;
			return handled;
		}
		if ((k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) && getFocused() == request) {
			press("send", 0);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		boolean r = super.charTyped(event);
		if (browsing) {
			listScroll = 0;
		}
		return r;
	}

	/** preset n | browse | pick n | tools | refresh | back | repo 0/1 | mode 0/1 | send. Also the dev command. */
	void press(String action, int arg) {
		switch (action) {
			case "preset" -> {
				if (model != null && arg >= 0 && arg < PRESETS.size()) {
					model.setValue(PRESETS.get(arg));
				}
			}
			case "browse" -> {
				browsing = true;
				listScroll = 0;
				loadCatalog(false);
				rebuildWidgets();
			}
			case "pick" -> {
				List<ModelInfo> list = filtered();
				if (arg >= 0 && arg < list.size() && model != null) {
					model.setValue(list.get(arg).id());
					browsing = false;
					rebuildWidgets();
				}
			}
			case "tools" -> {
				toolsOnly = !toolsOnly;
				listScroll = 0;
			}
			case "refresh" -> loadCatalog(true);
			case "back" -> {
				browsing = false;
				rebuildWidgets();
			}
			case "repo" -> {
				List<@Nullable RepoChoice> choices = choices();
				if (!choices.isEmpty()) {
					repoIndex = Math.floorMod(repoIndex + (arg == 0 ? -1 : 1), choices.size());
					RepoChoice c = choices.get(repoIndex);
					lastRepoPath = c == null ? null : c.path();
				}
			}
			case "mode" -> {
				if (arg >= 0 && arg < MODES.size()) {
					mode = MODES.get(arg);
					lastMode = mode;
					note = "";
					pickRepo();
				}
			}
			case "newchat" -> send(true);
			case "send" -> send(false);
			default -> {
			}
		}
	}

	void setRequest(String text) {
		if (request != null) {
			request.setValue(text);
		}
	}

	void setSearch(String text) {
		if (search != null) {
			search.setValue(text);
			listScroll = 0;
		}
	}

	boolean browsing() {
		return browsing;
	}

	@Nullable String modelValue() {
		return model == null ? null : model.getValue();
	}

	String mode() {
		return mode;
	}

	/** Send the request: a new goal, or (a chat, unless {@code fresh}) the next message of Scout's live chat. */
	private void send(boolean fresh) {
		Hub hub = hub();
		String text = request == null ? "" : request.getValue().trim();
		if (sending || text.isEmpty()) {
			return;
		}
		if (hub == null || !hub.connected()) {
			fail("Scout's Foreman isn't connected yet");
			return;
		}
		boolean chat = mode.equals("chat");
		if (chat && !fresh && liveChat(hub.state()) != null) {
			sending = true;
			note = "";
			Foreman.messageTo(hub, FreelancePavilion.AGENT, text).whenComplete((ack, err) -> done(err, ack, "Sent to Scout: the reply comes in chat"));
			return;
		}
		List<@Nullable RepoChoice> choices = choices();
		if (choices.isEmpty()) {
			fail("Connect a project in a hub first (console: /repo add <path>), or Chat");
			return;
		}
		RepoChoice repo = choices.get(Math.floorMod(repoIndex, choices.size()));
		lastRepoPath = repo == null ? null : repo.path();
		String m = model == null || model.getValue().isBlank() ? null : model.getValue().trim();
		sending = true;
		note = "";
		CompletableFuture<@Nullable String> repoId;
		if (repo == null) {
			repoId = CompletableFuture.completedFuture(null);
		} else {
			// the freelancer's Foreman knows a repository once it has been added there
			String known = null;
			for (Repo r : hub.state().repos().values()) {
				if (norm(r.path()).equals(norm(repo.path()))) {
					known = r.id();
				}
			}
			repoId = known != null ? CompletableFuture.completedFuture(known) : Foreman.addRepoTo(hub, repo.path()).thenApply(ack -> {
				if (!ack.ok() || ack.result() == null || !ack.result().has("repoId")) {
					throw new IllegalStateException(ack.error() != null ? ack.error() : "could not add " + repo.name());
				}
				return ack.result().get("repoId").getAsString();
			});
		}
		String on = shortModel(m != null ? m : currentModel());
		ModelInfo mi = info(m != null ? m : currentModel());
		String priced = on + (mi != null && !price(mi).isEmpty() ? " (" + price(mi) + ")" : "");
		String sent = switch (mode) {
			case "ask" -> "Asked Scout on " + priced + ": the answer comes in chat";
			case "chat" -> "Chatting with Scout on " + priced + ": replies come in chat";
			default -> "Sent to Scout on " + priced + ": you'll get a merge to review";
		};
		repoId.thenCompose(id -> Foreman.submitGoalTo(hub, text, id, m, mode)).whenComplete((ack, err) -> done(err, ack, sent));
	}

	private void done(@Nullable Throwable err, @Nullable Ack ack, String sent) {
		sending = false;
		if (err != null) {
			fail(rootMessage(err));
		} else if (ack == null || !ack.ok()) {
			fail(ack != null && ack.error() != null ? ack.error() : "Scout couldn't take it");
		} else {
			note = sent;
			noteBad = false;
			if (request != null) {
				request.setValue("");
			}
		}
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
