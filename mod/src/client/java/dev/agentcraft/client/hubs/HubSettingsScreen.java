package dev.agentcraft.client.hubs;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.Hub;
import dev.agentcraft.client.foreman.Protocol.Ack;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * One hub's settings (from its card in the hubs overview). Projects: the hub's repos, which one new
 * goals go to, remove one, add a folder or clone a GitHub URL. GitHub, per project: origin and how far
 * ahead/behind the branch is, Fetch / Pull / Push, Publish (gh repo create) when it has no remote, and
 * auto-push after every approved merge. Remote operations run in the hub's Foreman with the user's own
 * git/gh login, only when pressed here; agents never get network git.
 */
public class HubSettingsScreen extends Screen {
	private static final int W = 380;
	private static final int CARD_H = 62;
	private final Hub hub;
	private final @Nullable Screen back;
	private final List<Btn> buttons = new ArrayList<>();
	private @Nullable EditBox add;
	private @Nullable String busy;
	private String note = "";
	private boolean noteBad;
	private @Nullable String armedRemove;
	private long armedAt;
	private boolean publishPublic;
	private int scroll;
	private int maxScroll;
	private int px, py, inner;

	private record Btn(String action, String repo, int x, int y, int w, int h) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	public HubSettingsScreen(Hub hub, @Nullable Screen back) {
		super(Component.literal(hub.name() + " settings"));
		this.hub = hub;
		this.back = back;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int listRows() {
		return Math.max(1, Math.min(3, (height - 150) / CARD_H));
	}

	@Override
	protected void init() {
		Kit.Padding pad = Kit.padding("panel_paper");
		inner = W - pad.left() - pad.right();
		px = (width - W) / 2;
		int h = pad.top() + 22 + listRows() * CARD_H + 64 + pad.bottom();
		py = Math.max(4, (height - h) / 2);
		int addY = py + pad.top() + 22 + listRows() * CARD_H + 18;
		String old = add == null ? "" : add.getValue();
		EditBox f = new EditBox(font, px + pad.left() + 4, addY + 4, inner - 70, 10, Component.literal("Add a project"));
		f.setBordered(false);
		f.setTextShadow(false);
		f.setTextColor(UiStyle.color("paper.text"));
		f.setMaxLength(400);
		f.setValue(old);
		add = addRenderableWidget(f);
	}

	private List<Repo> repos() {
		return new ArrayList<>(hub.state().repos().values());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		extractTransparentBackground(g);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		buttons.clear();
		if (armedRemove != null && System.currentTimeMillis() - armedAt > 4000) {
			armedRemove = null;
		}
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		Kit.Padding pad = Kit.padding("panel_paper");
		int rows = listRows();
		int h = pad.top() + 22 + rows * CARD_H + 64 + pad.bottom();
		Panels.panel(g, px, py, W, h);
		int x = px + pad.left();
		int y = py + pad.top();
		boolean live = hub.connected() && hub.state().hasData();
		Panels.header(g, font, hub.name() + "  ·  settings" + (live ? "" : "  ·  Foreman offline"), x, y, inner);
		y += 22;
		// ---- projects
		List<Repo> repos = repos();
		maxScroll = Math.max(0, repos.size() - rows);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		if (!live) {
			Panels.text(g, font, TextUtil.ellipsize(font, "Its Foreman isn't connected: settings need it running (it starts when you visit the hub).", inner), x, y + 4,
				muted);
		} else if (repos.isEmpty()) {
			Panels.text(g, font, "No project yet: add a folder or a GitHub URL below.", x, y + 4, muted);
		}
		for (int i = scroll; i < Math.min(repos.size(), scroll + rows); i++) {
			card(g, repos.get(i), x, y + (i - scroll) * CARD_H, inner, mouseX, mouseY, repos.size() > 1);
		}
		if (maxScroll > 0) {
			String more = (scroll + 1) + "-" + Math.min(repos.size(), scroll + rows) + " of " + repos.size() + "  (scroll)";
			Panels.text(g, font, more, x + inner - font.width(more), py + pad.top() + 4, muted);
		}
		y += rows * CARD_H;
		// ---- add a project: a folder or a git URL
		Panels.text(g, font, "Add a project (a folder, or a GitHub URL to clone)", x, y + 2, muted);
		y += 14;
		g.fill(x, y + 1, x + inner - 62, y + 16, UiStyle.withAlpha(UiStyle.WALNUT, 24));
		super.extractRenderState(g, mouseX, mouseY, partial);
		button(g, "add", "", "Add", x + inner - UiBits.buttonWidth(font, "Add", 0), y - 2, true, mouseX, mouseY, live && busy == null);
		y += 20;
		String hint = !note.isEmpty() ? note : addHint();
		Panels.text(g, font, TextUtil.ellipsize(font, hint, inner), x, y + 2, noteBad ? UiStyle.CLAY_DARK : muted);
	}

	private void card(GuiGraphicsExtractor g, Repo r, int x, int y, int w, int mx, int my, boolean several) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		boolean isDefault = Boolean.TRUE.equals(r.isDefault()) || (!anyDefault() && isLast(r));
		boolean working = busy != null && busy.equals(r.id());
		// name, branch, default badge
		int nx = x;
		Panels.text(g, font, r.name(), nx, y + 1, ink);
		nx += font.width(r.name()) + 6;
		Panels.text(g, font, r.branch() + (Boolean.TRUE.equals(r.dirty()) ? " (uncommitted changes)" : ""), nx, y + 1, muted);
		if (isDefault && several) {
			String b = "new goals go here";
			int bw = UiBits.dotPillWidth(font, b);
			UiBits.dotPill(g, font, "done", b, x + w - bw, y - 1, UiStyle.SAGE);
		}
		Panels.text(g, font, TextUtil.ellipsize(font, r.path(), w), x, y + 12, muted);
		// GitHub line
		String gh;
		int ghColor = muted;
		if (r.remote() == null) {
			gh = "No GitHub remote yet";
		} else {
			StringBuilder b = new StringBuilder(shortRemote(r.remote()));
			if (r.upstream() == null) {
				b.append("  ·  not pushed yet");
			} else {
				int ahead = r.ahead() == null ? 0 : r.ahead();
				int behind = r.behind() == null ? 0 : r.behind();
				if (ahead == 0 && behind == 0) {
					b.append("  ·  up to date (as of the last fetch)");
				}
				if (ahead > 0) {
					b.append("  ·  ").append(ahead).append(ahead == 1 ? " commit" : " commits").append(" to push");
					ghColor = UiStyle.CLAY_DARK;
				}
				if (behind > 0) {
					b.append("  ·  ").append(behind).append(" to pull");
					ghColor = UiStyle.CLAY_DARK;
				}
			}
			gh = b.toString();
		}
		int chipW = 0;
		if (r.remote() != null) {
			// auto-push: a small toggle at the end of the GitHub line
			boolean on = Boolean.TRUE.equals(r.autoPush());
			String label = on ? "auto-push: on" : "auto-push: off";
			chipW = font.width(label) + 8;
			int cx = x + w - chipW;
			boolean hover = busy == null && mx >= cx && mx < cx + chipW && my >= y + 22 && my < y + 34;
			g.fill(cx, y + 22, cx + chipW, y + 34, on ? UiStyle.withAlpha(UiStyle.TEAL, 90) : UiStyle.withAlpha(UiStyle.WALNUT, hover ? 60 : 30));
			Panels.text(g, font, label, cx + 4, y + 24, on ? ink : muted);
			if (busy == null && hub.connected()) {
				buttons.add(new Btn("autopush", r.id(), cx, y + 22, chipW, 12));
			}
			chipW += 6;
		}
		Panels.text(g, font, TextUtil.ellipsize(font, (working ? "Working...  " : "") + gh, w - chipW), x, y + 24, working ? ink : ghColor);
		// buttons: project on the left, GitHub on the right
		int by = y + 36;
		boolean ok = busy == null && hub.connected();
		int bx = x;
		if (!isDefault && several) {
			bx += button(g, "default", r.id(), "Make default", bx, by, false, mx, my, ok) + 4;
		}
		boolean armed = r.id().equals(armedRemove);
		bx += button(g, "remove", r.id(), armed ? "Really remove?" : "Remove", bx, by, armed, mx, my, ok) + 4;
		int rx = x + w;
		if (r.remote() == null) {
			String pub = "Publish to GitHub";
			rx -= UiBits.buttonWidth(font, pub, 0);
			button(g, "publish", r.id(), pub, rx, by, true, mx, my, ok);
			String vis = publishPublic ? "public" : "private";
			rx -= UiBits.buttonWidth(font, vis, 0) + 4;
			button(g, "visibility", r.id(), vis, rx, by, false, mx, my, ok);
		} else {
			for (String[] a : new String[][] {{"push", "Push"}, {"pull", "Pull"}, {"fetch", "Fetch"}}) {
				rx -= UiBits.buttonWidth(font, a[1], 0);
				button(g, a[0], r.id(), a[1], rx, by, a[0].equals("push") && r.ahead() != null && r.ahead() > 0, mx, my, ok);
				rx -= 4;
			}
		}
		Panels.divider(g, x, y + CARD_H - 5, w);
	}

	private boolean anyDefault() {
		for (Repo r : hub.state().repos().values()) {
			if (Boolean.TRUE.equals(r.isDefault())) {
				return true;
			}
		}
		return false;
	}

	private boolean isLast(Repo r) {
		Repo last = null;
		for (Repo o : hub.state().repos().values()) {
			last = o;
		}
		return last != null && last.id().equals(r.id());
	}

	/** "github.com/owner/name" from https or ssh URLs. */
	static String shortRemote(String url) {
		String u = url.trim().replaceFirst("^(https?://)[^@/]+@", "$1");
		var m = java.util.regex.Pattern.compile("^(?:https?://|ssh://git@|git@)([^/:]+)[/:](.+?)(?:\\.git)?/?$").matcher(u);
		return m.matches() ? m.group(1) + "/" + m.group(2) : u;
	}

	static boolean isUrl(String s) {
		return s.trim().matches("^(https?://|ssh://|file://|git@[^:]+:).*");
	}

	static String nameFromUrl(String url) {
		String[] parts = url.trim().replaceAll("/+$", "").split("[/:]");
		String last = parts.length == 0 ? "repo" : parts[parts.length - 1];
		last = last.replaceAll("\\.git$", "");
		return last.isBlank() ? "repo" : last;
	}

	/** Where a clone of {@code url} goes: next to the hub's (or any hub's) projects. */
	String cloneTarget(String url) {
		String name = nameFromUrl(url);
		for (Repo r : hub.state().repos().values()) {
			Path parent = Path.of(r.path()).getParent();
			if (parent != null) {
				return parent.resolve(name).toString();
			}
		}
		Path suggested = Path.of(NewHubScreen.suggestFolder(name));
		return suggested.toString();
	}

	private String addHint() {
		String v = add == null ? "" : add.getValue().trim();
		if (v.isEmpty()) {
			return "Push, Pull and Publish use your own git / gh login; agents never touch GitHub.";
		}
		if (isUrl(v)) {
			return "Clones into " + cloneTarget(v);
		}
		try {
			Path p = Path.of(v);
			return Files.isDirectory(p.resolve(".git")) ? "An existing git repo: added as it is" : Files.isDirectory(p) ? "A folder: it becomes a git repo"
				: "A new folder: created as an empty git repo";
		} catch (RuntimeException e) {
			return "Not a valid path";
		}
	}

	/** Draws a button at (x, y); returns its width. */
	private int button(GuiGraphicsExtractor g, String action, String repo, String label, int x, int y, boolean primary, int mx, int my, boolean enabled) {
		int bw = UiBits.buttonWidth(font, label, 0);
		boolean hover = enabled && mx >= x && mx < x + bw && my >= y && my < y + 20;
		UiBits.button(g, font, label, 0, x, y, bw, primary, !enabled ? UiBits.ButtonState.DISABLED : hover ? UiBits.ButtonState.HOVER
			: UiBits.ButtonState.NORMAL, false);
		if (enabled) {
			buttons.add(new Btn(action, repo, x, y, bw, 20));
		}
		return bw;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (Btn b : List.copyOf(buttons)) {
				if (b.hit(event.x(), event.y())) {
					press(b.action(), b.repo());
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(dy)));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		if ((k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) && getFocused() == add) {
			press("add", "");
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(back);
	}

	/** default | remove | fetch | pull | push | publish | visibility | autopush | add (also the dev command). */
	void press(String action, String repoId) {
		if (busy != null && !action.equals("visibility")) {
			return;
		}
		Repo r = repoId.isEmpty() ? null : hub.state().repos().get(repoId);
		switch (action) {
			case "default" -> run(repoId, Foreman.setDefaultRepo(hub, repoId), "New goals go to " + (r == null ? repoId : r.name()));
			case "remove" -> {
				if (!repoId.equals(armedRemove)) {
					armedRemove = repoId;
					armedAt = System.currentTimeMillis();
					return;
				}
				armedRemove = null;
				run(repoId, Foreman.removeRepo(hub, repoId), "Removed " + (r == null ? repoId : r.name()) + " (its files stay)");
			}
			case "fetch", "pull", "push" -> run(repoId, Foreman.repoGit(hub, repoId, action, null, null), switch (action) {
				case "fetch" -> "Fetched";
				case "pull" -> "Pulled";
				default -> "Pushed";
			} + " " + (r == null ? repoId : r.name()));
			case "publish" -> run(repoId, Foreman.repoGit(hub, repoId, "publish", r == null ? null : r.name(), publishPublic ? "public" : "private"),
				"Published " + (r == null ? repoId : r.name()) + " to GitHub (" + (publishPublic ? "public" : "private") + ")");
			case "visibility" -> publishPublic = !publishPublic;
			case "autopush" -> {
				boolean on = r == null || !Boolean.TRUE.equals(r.autoPush());
				run(repoId, Foreman.setAutoPush(hub, repoId, on), on ? "Auto-push on: every approved merge goes to GitHub" : "Auto-push off");
			}
			case "add" -> addProject();
			default -> {
			}
		}
	}

	private void addProject() {
		String v = add == null ? "" : add.getValue().trim();
		if (v.isEmpty()) {
			return;
		}
		CompletableFuture<Ack> op;
		if (isUrl(v)) {
			String dest = cloneTarget(v);
			note = "Cloning " + shortRemote(v) + " into " + dest + "...";
			op = Foreman.cloneRepo(hub, v, dest);
		} else {
			note = "Adding " + v + "...";
			op = CompletableFuture.supplyAsync(() -> HubsFeature.prepareRepo(v, hub.name())).thenCompose(made -> Foreman.addRepoTo(hub, v));
		}
		noteBad = false;
		run("+", op, isUrl(v) ? "Cloned " + shortRemote(v) : "Added " + v);
	}

	private void run(String repoId, CompletableFuture<Ack> op, String done) {
		busy = repoId;
		if (note.isEmpty() || !note.endsWith("...")) {
			note = "Working...";
		}
		noteBad = false;
		op.whenComplete((ack, err) -> minecraft.execute(() -> {
			busy = null;
			if (err != null) {
				Throwable c = err.getCause() != null ? err.getCause() : err;
				note = c.getMessage() != null ? c.getMessage() : c.toString();
				noteBad = true;
			} else if (!ack.ok()) {
				note = ack.error() != null ? ack.error() : "refused";
				noteBad = true;
			} else {
				String out = ack.result() != null && ack.result().has("output") ? ack.result().get("output").getAsString().trim() : "";
				String lastLine = out.isEmpty() ? "" : out.substring(out.lastIndexOf('\n') + 1).trim();
				note = done + (lastLine.isEmpty() ? "" : "  ·  " + lastLine);
				noteBad = false;
				if (repoId.equals("+") && add != null) {
					add.setValue("");
				}
			}
		}));
	}

	void setAdd(String text) {
		if (add != null) {
			add.setValue(text);
		}
	}

	String note() {
		return note;
	}

	Hub hub() {
		return hub;
	}
}
