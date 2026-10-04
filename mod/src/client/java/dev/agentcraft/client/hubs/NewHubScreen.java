package dev.agentcraft.client.hubs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.foreman.Hub;
import dev.agentcraft.client.foreman.Hubs;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.hq.FreelancePavilion;
import dev.agentcraft.hq.Theme;
import dev.agentcraft.layout.HubRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A new hub from the hubs overview: id, name, a theme and where its project comes from - a GitHub
 * repository (pick one of yours, listed with {@code gh}, or paste any URL; it is cloned next to the
 * other projects) or a local folder (an existing git repo, or a folder that becomes one). Create
 * builds the studio in the next free spot with that theme, takes you there, and connects the project
 * to the hub's Foreman once it is up ({@link HubsFeature#createHub}).
 */
public class NewHubScreen extends Screen {
	private static final int W = 360;
	private static final int REPO_ROWS = 6;
	private static final int ROW = 12;
	/** The last source picked (GitHub first: starting from an existing repository is the common case). */
	private static boolean lastGithub = true;
	/** Your GitHub repositories (gh repo list), fetched once per session. */
	private static List<GhRepo> ghRepos = List.of();
	private static @Nullable String ghError;
	private static boolean ghLoading;
	private static boolean ghLoaded;

	private final @Nullable Screen back;
	private final List<Btn> buttons = new ArrayList<>();
	private @Nullable EditBox id;
	private @Nullable EditBox name;
	private @Nullable EditBox folder;
	private @Nullable EditBox url;
	private boolean github = lastGithub;
	private String theme = "warm";
	private boolean folderTouched;
	private boolean idTouched;
	private boolean settingId;
	private int repoScroll;
	private int repoMax;
	private String note = "";
	private int px, py, inner;

	record GhRepo(String nameWithOwner, String url, boolean isPrivate, String description) {
		String repoName() {
			int i = nameWithOwner.indexOf('/');
			return i >= 0 ? nameWithOwner.substring(i + 1) : nameWithOwner;
		}
	}

	private record Btn(String action, int arg, int x, int y, int w, int h) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	public NewHubScreen(@Nullable Screen back) {
		super(Component.literal("New hub"));
		this.back = back;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int panelHeight() {
		Kit.Padding pad = Kit.padding("panel_paper");
		return pad.top() + 22 + (github ? 150 + REPO_ROWS * ROW : 140) + 26 + pad.bottom();
	}

	@Override
	protected void init() {
		Kit.Padding pad = Kit.padding("panel_paper");
		inner = W - pad.left() - pad.right();
		px = (width - W) / 2;
		py = Math.max(4, (height - panelHeight()) / 2);
		int x = px + pad.left() + 70;
		int y = py + pad.top() + 22;
		int fw = inner - 74;
		id = addRenderableWidget(field(x, y + 4, fw, id, 32, "Id"));
		id.setResponder(v -> {
			// ids are a-z 0-9 _ - (also the project's Foreman profile): clean up what is typed or pasted
			String clean = slug(v);
			if (!clean.equals(v)) {
				id.setValue(clean);
				return;
			}
			if (!settingId) {
				idTouched = !v.isEmpty();
			}
			if (!folderTouched && folder != null) {
				folder.setValue(suggestFolder(v));
				folderTouched = false;
			}
		});
		name = addRenderableWidget(field(x, y + 26, fw, name, 40, "Name"));
		if (github) {
			url = addRenderableWidget(field(x, y + 116, fw, url, 400, "GitHub repository"));
			url.setResponder(v -> repoScroll = 0);
			loadGhRepos();
		} else {
			folder = addRenderableWidget(field(x, y + 116, fw, folder, 400, "Project folder"));
			folder.setResponder(v -> folderTouched = true);
		}
		setFocused(id);
		id.setFocused(true);
	}

	private EditBox field(int x, int y, int w, @Nullable EditBox old, int max, String label) {
		EditBox f = new EditBox(font, x, y, w, 10, Component.literal(label));
		f.setBordered(false);
		f.setTextShadow(false);
		f.setTextColor(UiStyle.color("paper.text"));
		f.setMaxLength(max);
		f.setValue(old == null ? "" : old.getValue());
		return f;
	}

	static String slug(String v) {
		return v.toLowerCase(Locale.ROOT).replace(' ', '-').replace('.', '-').replaceAll("[^a-z0-9_-]", "");
	}

	/** Next to the other projects: the folder holding the first hub repo, plus the id. */
	static String suggestFolder(String hubId) {
		if (hubId.isBlank()) {
			return "";
		}
		for (Hub h : Hubs.all()) {
			if (FreelancePavilion.HUB.equals(h.id())) {
				continue;
			}
			for (Repo r : h.state().repos().values()) {
				Path parent = Path.of(r.path()).getParent();
				if (parent != null) {
					return parent.resolve(hubId).toString();
				}
			}
		}
		return Path.of(System.getProperty("user.home"), "projects", hubId).toString();
	}

	/** Where a clone of {@code url} goes for a new hub: next to the other projects. */
	static String cloneTarget(String url) {
		return suggestFolder(HubSettingsScreen.nameFromUrl(url));
	}

	/** Drops terminal colour codes (ESC [ ... letter), in case a tool colours its output anyway. */
	static String stripAnsi(String s) {
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == 27 && i + 1 < s.length() && s.charAt(i + 1) == '[') {
				int j = i + 2;
				while (j < s.length() && !Character.isLetter(s.charAt(j))) {
					j++;
				}
				i = j;
				continue;
			}
			b.append(c);
		}
		return b.toString();
	}

	/** Your repositories via the GitHub CLI (once per session; any thread works, results land on the client thread). */
	static void loadGhRepos() {
		if (ghLoading || ghLoaded) {
			return;
		}
		ghLoading = true;
		CompletableFuture.supplyAsync(() -> {
			try {
				ProcessBuilder pb = new ProcessBuilder("gh", "repo", "list", "--limit", "100", "--json", "nameWithOwner,url,isPrivate,description");
				// plain JSON: no colours even when the user's environment forces them (FORCE_COLOR, CLICOLOR_FORCE)
				var env = pb.environment();
				env.keySet().removeAll(List.of("FORCE_COLOR", "CLICOLOR_FORCE", "GH_FORCE_TTY"));
				env.put("NO_COLOR", "1");
				env.put("CLICOLOR", "0");
				env.put("GH_PROMPT_DISABLED", "1");
				Process p = pb.start();
				p.getOutputStream().close();
				String out = stripAnsi(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
				String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
				if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
					throw new IllegalStateException(err.isBlank() ? "gh repo list failed" : err.trim().split("\n")[0]);
				}
				List<GhRepo> list = new ArrayList<>();
				for (JsonElement e : JsonParser.parseString(out).getAsJsonArray()) {
					JsonObject o = e.getAsJsonObject();
					list.add(new GhRepo(o.get("nameWithOwner").getAsString(), o.get("url").getAsString(), o.has("isPrivate") && o.get("isPrivate").getAsBoolean(),
						o.has("description") && !o.get("description").isJsonNull() ? o.get("description").getAsString() : ""));
				}
				return list;
			} catch (com.google.gson.JsonParseException | IllegalStateException e) {
				throw new IllegalStateException("couldn't list your GitHub repositories (" + e.getMessage() + "): paste a URL instead");
			} catch (java.io.IOException e) {
				throw new IllegalStateException("the GitHub CLI (gh) isn't installed: paste a repository URL instead");
			} catch (InterruptedException e) {
				throw new IllegalStateException("interrupted");
			}
		}).whenComplete((list, err) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
			ghLoading = false;
			ghLoaded = true;
			if (err != null) {
				ghError = err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
				AgentCraft.LOGGER.info("New hub: no GitHub repo list ({})", ghError);
			} else {
				ghRepos = List.copyOf(list);
			}
		}));
	}

	/** Your repositories, filtered by the words typed in the URL field (unless it holds a URL). */
	private List<GhRepo> shownRepos() {
		String q = url == null ? "" : url.getValue().trim().toLowerCase(Locale.ROOT);
		if (HubSettingsScreen.isUrl(q)) {
			q = "";
		}
		String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
		List<GhRepo> out = new ArrayList<>();
		for (GhRepo r : ghRepos) {
			String hay = (r.nameWithOwner() + " " + r.description()).toLowerCase(Locale.ROOT);
			boolean all = true;
			for (String w : words) {
				all &= hay.contains(w);
			}
			if (all) {
				out.add(r);
			}
		}
		return out;
	}

	/** Why the form cannot be sent yet, or null. */
	private @Nullable String problem() {
		String v = id == null ? "" : id.getValue();
		if (v.isBlank()) {
			return github ? "Pick a repository (or paste its URL), or give the hub an id" : "Give the hub an id (a-z, 0-9, - and _): it is also its project's name";
		}
		if (HubRegistry.MAIN.equals(v) || HubRegistry.get(v) != null) {
			return "There is already a hub '" + v + "'";
		}
		if (FreelancePavilion.HUB.equals(v)) {
			return "'freelance' is the freelancer's pavilion; pick another id";
		}
		if (HubRegistry.COMPASS.equals(HubRegistry.layout()) && HubRegistry.freeSpot(HubRegistry.all()) == null) {
			return "The town is full";
		}
		if (github) {
			String u = url == null ? "" : url.getValue().trim();
			if (!HubSettingsScreen.isUrl(u)) {
				return u.isEmpty() ? "Pick one of your repositories below, or paste a GitHub URL" : "Pick a repository from the list, or paste a full https:// URL";
			}
		}
		return null;
	}

	private String projectState() {
		if (github) {
			String u = url == null ? "" : url.getValue().trim();
			if (HubSettingsScreen.isUrl(u)) {
				return "Clones " + HubSettingsScreen.shortRemote(u) + " into " + cloneTarget(u);
			}
			return ghLoading ? "Loading your GitHub repositories..." : ghError != null ? ghError : ghRepos.isEmpty() ? "Paste any repository URL"
				: "Pick one of your " + ghRepos.size() + " repositories below (scroll, or type to search), or paste a URL";
		}
		String f = folder == null ? "" : folder.getValue().trim();
		if (f.isEmpty()) {
			return "No project yet (add one later in the hub's Settings)";
		}
		try {
			Path p = Path.of(f);
			if (Files.isDirectory(p.resolve(".git"))) {
				return "An existing git repo: connected as it is";
			}
			if (Files.isDirectory(p)) {
				return "An existing folder: it becomes a git repo";
			}
			return "A new folder: created as an empty git repo";
		} catch (RuntimeException e) {
			return "Not a valid path";
		}
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
		int h = panelHeight();
		Panels.panel(g, px, py, W, h);
		int x = px + pad.left();
		int y = py + pad.top();
		HubRegistry.Spot spot = HubRegistry.COMPASS.equals(HubRegistry.layout()) ? HubRegistry.freeSpot(HubRegistry.all()) : null;
		Panels.header(g, font, "New hub" + (spot != null ? "  ·  next spot (" + spot.x() + ", " + spot.z() + "), ring " + spot.ring() : ""), x, y, inner);
		y += 22;
		row(g, "Id", x, y, muted);
		row(g, "Name", x, y + 22, muted);
		if (name != null && name.getValue().isEmpty() && !name.isFocused()) {
			Panels.text(g, font, id != null && !id.getValue().isEmpty() ? id.getValue() : "shown on the hub wall and in the HUD", x + 74, y + 26, muted);
		}
		// themes: a swatch in the theme's accent with its name
		Panels.text(g, font, "Theme", x, y + 50, muted);
		int tx = x + 70;
		int ty = y + 46;
		for (int i = 0; i < Theme.ALL.size(); i++) {
			Theme t = Theme.ALL.get(i);
			String label = t.name.replace(" Studio", "");
			int bw = 12 + font.width(label) + 8;
			if (tx + bw > x + inner) {
				tx = x + 70;
				ty += 19;
			}
			boolean on = t.id.equals(theme);
			boolean hover = mouseX >= tx && mouseX < tx + bw && mouseY >= ty && mouseY < ty + 16;
			g.fill(tx, ty, tx + bw, ty + 16, on ? UiStyle.withAlpha(t.accent, 110) : UiStyle.withAlpha(UiStyle.WALNUT, hover ? 50 : 24));
			g.fill(tx + 4, ty + 4, tx + 12, ty + 12, t.accent | 0xFF000000);
			Panels.text(g, font, label, tx + 15, ty + 4, on ? ink : muted);
			buttons.add(new Btn("theme", i, tx, ty, bw, 16));
			tx += bw + 3;
		}
		// project: where it comes from
		Panels.text(g, font, "Project", x, y + 92, muted);
		int sx = x + 70;
		sx += button(g, "source", 1, "From GitHub", sx, y + 86, github, mouseX, mouseY) + 4;
		button(g, "source", 0, "Local folder", sx, y + 86, !github, mouseX, mouseY);
		row(g, github ? "Repository" : "Folder", x, y + 112, muted);
		if (github && url != null && url.getValue().isEmpty() && !url.isFocused()) {
			Panels.text(g, font, "https://github.com/owner/repo, or search yours", x + 74, y + 116, muted);
		}
		Panels.text(g, font, TextUtil.ellipsize(font, projectState(), inner - 70), x + 70, y + 132, ghError != null && github && !HubSettingsScreen.isUrl(
			url == null ? "" : url.getValue()) ? UiStyle.CLAY_DARK : muted);
		super.extractRenderState(g, mouseX, mouseY, partial);
		if (github) {
			drawRepos(g, x, y + 146, mouseX, mouseY);
		}
		// footer
		int fy = py + h - pad.bottom() - 20;
		String create = "Create hub";
		int cw = UiBits.buttonWidth(font, create, 0);
		String why = problem();
		boolean hover = mouseX >= x + inner - cw && mouseX < x + inner && mouseY >= fy && mouseY < fy + 20;
		UiBits.button(g, font, create, 0, x + inner - cw, fy, cw, true, why != null ? UiBits.ButtonState.DISABLED : hover ? UiBits.ButtonState.HOVER
			: UiBits.ButtonState.NORMAL, false);
		buttons.add(new Btn("create", 0, x + inner - cw, fy, cw, 20));
		String cancel = "Back";
		int bw = UiBits.buttonWidth(font, cancel, 0);
		boolean hb = mouseX >= x + inner - cw - 6 - bw && mouseX < x + inner - cw - 6 && mouseY >= fy && mouseY < fy + 20;
		UiBits.button(g, font, cancel, 0, x + inner - cw - 6 - bw, fy, bw, false, hb ? UiBits.ButtonState.HOVER : UiBits.ButtonState.NORMAL, false);
		buttons.add(new Btn("back", 0, x + inner - cw - 6 - bw, fy, bw, 20));
		String hint = !note.isEmpty() ? note : why != null ? why : "Builds the studio, takes you there; its team starts with its Foreman";
		Panels.text(g, font, TextUtil.ellipsize(font, hint, inner - cw - bw - 16), x, fy + 6, why != null && note.isEmpty() ? UiStyle.CLAY_DARK : muted);
	}

	/** Your repositories: owner/name, private or public; a click picks one. */
	private void drawRepos(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
		int ink = UiBits.ink();
		int muted = UiBits.muted();
		List<GhRepo> list = shownRepos();
		repoMax = Math.max(0, list.size() - REPO_ROWS);
		repoScroll = Math.max(0, Math.min(repoScroll, repoMax));
		g.fill(x, y - 2, x + inner, y + REPO_ROWS * ROW + 2, UiStyle.withAlpha(UiStyle.WALNUT, 14));
		if (list.isEmpty()) {
			String why = ghLoading ? "Loading..." : ghError != null ? "No list (" + ghError + ")" : ghRepos.isEmpty() ? "No repositories on your GitHub account"
				: "No match: paste the URL instead";
			Panels.text(g, font, TextUtil.ellipsize(font, why, inner - 8), x + 4, y + 2, muted);
			return;
		}
		String picked = url == null ? "" : url.getValue().trim();
		for (int i = repoScroll; i < Math.min(list.size(), repoScroll + REPO_ROWS); i++) {
			GhRepo r = list.get(i);
			int ry = y + (i - repoScroll) * ROW;
			boolean on = r.url().equalsIgnoreCase(picked) || (r.url() + ".git").equalsIgnoreCase(picked);
			boolean hover = mx >= x && mx < x + inner && my >= ry && my < ry + ROW;
			if (on || hover) {
				g.fill(x, ry, x + inner, ry + ROW, UiStyle.withAlpha(on ? UiStyle.TEAL : UiStyle.WALNUT, on ? 70 : 40));
			}
			String vis = r.isPrivate() ? "private" : "public";
			int vw = font.width(vis);
			String label = r.nameWithOwner() + (r.description().isBlank() ? "" : "  ·  " + r.description());
			Panels.text(g, font, TextUtil.ellipsize(font, label, inner - vw - 14), x + 4, y + (i - repoScroll) * ROW + 2, ink);
			Panels.text(g, font, vis, x + inner - vw - 4, ry + 2, muted);
			buttons.add(new Btn("repo", i, x, ry, inner, ROW));
		}
		if (repoMax > 0) {
			// a scroll bar on the right edge of the list
			int trackH = REPO_ROWS * ROW;
			int thumbH = Math.max(6, trackH * REPO_ROWS / list.size());
			int thumbY = y + (trackH - thumbH) * repoScroll / repoMax;
			g.fill(x + inner - 2, y, x + inner, y + trackH, UiStyle.withAlpha(UiStyle.WALNUT, 30));
			g.fill(x + inner - 2, thumbY, x + inner, thumbY + thumbH, UiStyle.withAlpha(UiStyle.WALNUT, 140));
		}
	}

	private int button(GuiGraphicsExtractor g, String action, int arg, String label, int x, int y, boolean primary, int mx, int my) {
		int bw = UiBits.buttonWidth(font, label, 0);
		boolean hover = mx >= x && mx < x + bw && my >= y && my < y + 20;
		UiBits.button(g, font, label, 0, x, y, bw, primary, hover ? UiBits.ButtonState.HOVER : UiBits.ButtonState.NORMAL, false);
		buttons.add(new Btn(action, arg, x, y, bw, 20));
		return bw;
	}

	private void row(GuiGraphicsExtractor g, String label, int x, int y, int muted) {
		Panels.text(g, font, label, x, y + 4, muted);
		g.fill(x + 70, y + 1, x + inner, y + 16, UiStyle.withAlpha(UiStyle.WALNUT, 24));
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
		if (github) {
			repoScroll = Math.max(0, Math.min(repoMax, repoScroll - (int) Math.signum(dy)));
			return true;
		}
		return super.mouseScrolled(mx, my, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) {
			press("create", 0);
			return true;
		}
		if (k == InputConstants.KEY_TAB) {
			EditBox[] order = {id, name, github ? url : folder};
			int at = 0;
			for (int i = 0; i < order.length; i++) {
				if (getFocused() == order[i]) {
					at = i;
				}
			}
			setFocused(order[(at + 1) % order.length]);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(back);
	}

	/** theme n | source 1 (GitHub) / 0 (folder) | repo n | create | back (also the dev command). */
	void press(String action, int arg) {
		switch (action) {
			case "theme" -> {
				if (arg >= 0 && arg < Theme.ALL.size()) {
					theme = Theme.ALL.get(arg).id;
				}
			}
			case "source" -> {
				boolean gh = arg == 1;
				if (gh != github) {
					github = gh;
					lastGithub = gh;
					rebuildWidgets();
				}
			}
			case "repo" -> {
				List<GhRepo> list = shownRepos();
				if (arg >= 0 && arg < list.size() && url != null) {
					GhRepo r = list.get(arg);
					url.setValue(r.url());
					// an empty id (or one we filled in) follows the repository
					if (id != null && !idTouched) {
						settingId = true;
						id.setValue(slug(r.repoName()));
						settingId = false;
					}
				}
			}
			case "create" -> {
				String why = problem();
				if (why != null) {
					note = "";
					return;
				}
				String hubId = id.getValue().trim().toLowerCase(Locale.ROOT);
				String hubName = name.getValue().trim();
				String path = github ? url.getValue().trim() : folder.getValue().trim();
				minecraft.gui.setScreen(null);
				HubsFeature.createHub(hubId, hubName, theme, path.isEmpty() ? null : path);
			}
			case "back" -> onClose();
			default -> {
			}
		}
	}

	void fill(String hubId, String hubName, @Nullable String path) {
		if (id != null) {
			id.setValue(hubId);
		}
		if (name != null) {
			name.setValue(hubName);
		}
		if (path != null) {
			if (github && url != null) {
				url.setValue(path);
			} else if (folder != null) {
				folder.setValue(path);
			}
		}
	}

	boolean github() {
		return github;
	}

	int repoCount() {
		return shownRepos().size();
	}

	@Nullable String urlValue() {
		return url == null ? null : url.getValue();
	}

	@Nullable String idValue() {
		return id == null ? null : id.getValue();
	}
}
