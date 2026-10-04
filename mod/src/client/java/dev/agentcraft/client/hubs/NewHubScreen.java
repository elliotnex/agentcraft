package dev.agentcraft.client.hubs;

import com.mojang.blaze3d.platform.InputConstants;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A new hub from the hubs overview: id, name, a theme and the project folder (an existing git repo,
 * or a new folder that becomes one). Create builds the studio in the next free spot with that theme,
 * takes you there, and connects the project to the hub's Foreman once it is up
 * ({@link HubsFeature#createHub}).
 */
public class NewHubScreen extends Screen {
	private static final int W = 340;
	private final @Nullable Screen back;
	private final List<Btn> buttons = new ArrayList<>();
	private @Nullable EditBox id;
	private @Nullable EditBox name;
	private @Nullable EditBox folder;
	private String theme = "warm";
	private boolean folderTouched;
	private String note = "";
	private int px, py, inner;

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

	@Override
	protected void init() {
		Kit.Padding pad = Kit.padding("panel_paper");
		inner = W - pad.left() - pad.right();
		px = (width - W) / 2;
		py = Math.max(4, (height - 230) / 2);
		int x = px + pad.left() + 70;
		int y = py + pad.top() + 22;
		int fw = inner - 74;
		id = addRenderableWidget(field(x, y + 4, fw, id, 32, "Id"));
		id.setResponder(v -> {
			// ids are a-z 0-9 _ - (also the project's Foreman profile): clean up what is typed or pasted
			String clean = v.toLowerCase(Locale.ROOT).replace(' ', '-').replaceAll("[^a-z0-9_-]", "");
			if (!clean.equals(v)) {
				id.setValue(clean);
				return;
			}
			if (!folderTouched && folder != null) {
				folder.setValue(suggestFolder(v));
				folderTouched = false;
			}
		});
		name = addRenderableWidget(field(x, y + 26, fw, name, 40, "Name"));
		folder = addRenderableWidget(field(x, y + 92, fw, folder, 400, "Project folder"));
		folder.setResponder(v -> folderTouched = true);
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

	/** Why the form cannot be sent yet, or null. */
	private @Nullable String problem() {
		String v = id == null ? "" : id.getValue();
		if (v.isBlank()) {
			return "Give the hub an id (a-z, 0-9, - and _): it is also its project's name";
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
		return null;
	}

	private String folderState() {
		String f = folder == null ? "" : folder.getValue().trim();
		if (f.isEmpty()) {
			return "no project yet (connect one later in the console: /repo add <path>)";
		}
		try {
			Path p = Path.of(f);
			if (Files.isDirectory(p.resolve(".git"))) {
				return "an existing git repo: connected as it is";
			}
			if (Files.isDirectory(p)) {
				return "an existing folder: it becomes a git repo";
			}
			return "a new folder: created as an empty git repo";
		} catch (RuntimeException e) {
			return "not a valid path";
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
		int h = pad.top() + 200 + pad.bottom();
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
				// wrap onto a second row
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
		row(g, "Project", x, y + 88, muted);
		Panels.text(g, font, TextUtil.ellipsize(font, folderState(), inner - 70), x + 70, y + 106, muted);
		super.extractRenderState(g, mouseX, mouseY, partial);
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
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) {
			press("create", 0);
			return true;
		}
		if (k == InputConstants.KEY_TAB) {
			EditBox[] order = {id, name, folder};
			int at = 0;
			for (int i = 0; i < order.length; i++) {
				if (getFocused() == order[i]) {
					at = i;
				}
			}
			EditBox next = order[(at + 1) % order.length];
			setFocused(next);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(back);
	}

	/** theme n | create | back (also the dev command). */
	void press(String action, int arg) {
		switch (action) {
			case "theme" -> {
				if (arg >= 0 && arg < Theme.ALL.size()) {
					theme = Theme.ALL.get(arg).id;
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
				String path = folder.getValue().trim();
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
		if (path != null && folder != null) {
			folder.setValue(path);
		}
	}
}
