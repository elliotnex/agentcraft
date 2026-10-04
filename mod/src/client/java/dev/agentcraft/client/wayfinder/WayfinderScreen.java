package dev.agentcraft.client.wayfinder;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.foreman.Hub;
import dev.agentcraft.client.foreman.Hubs;
import dev.agentcraft.client.foreman.Protocol.Agent;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.hq.FreelancePavilion;
import dev.agentcraft.hq.Theme;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.layout.HubRegistry;
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
 * The Wayfinder's menu: places on the left (the town, every hub, the freelancer's pavilion), where to
 * go in the selected place on the right. Spots come from the anchors each studio publishes: standing
 * spots (entrance, atrium, podium) and the camera viewpoints of the stations, which already look at
 * what matters (the task wall, the library, each agent's desk). Keys: up/down pick a place, 1-9 go.
 */
public class WayfinderScreen extends Screen {
	private static final int W = 440;
	private static final int LIST_W = 128;
	private static final int ROW = 18;
	private static final double EYE = 1.62;
	private static @Nullable String lastSelected;
	private final List<Btn> buttons = new ArrayList<>();
	private String selected;
	private int scroll;

	/** A place to go to: feet at (x, y, z), looking yaw / pitch. */
	public record Spot(String label, double x, double y, double z, float yaw, float pitch) {
	}

	public record Place(String id, String name, int accent, List<Spot> spots, @Nullable String why) {
	}

	private record Btn(String action, int arg, String id, int x, int y, int w, int h) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** Studio spots: label, anchor, whether the anchor is an eye position (camera) rather than feet. */
	private static final Object[][] STUDIO = {
		{"Entrance", AnchorNames.SPAWN, false},
		{"Goal atrium", AnchorNames.GOAL_ATRIUM, false},
		{"Task wall", "cam_task_wall", true},
		{"Decision podium", "podium_user", false},
		{"Merge station", "cam_merge_station", true},
		{"Library", "cam_library", true},
		{"Console terminal", "cam_console", true},
		{"Test bench", "cam_testbench", true},
		{"Lounge", "cam_lounge", true},
		{"Whole studio", "cam_wide_interior", true},
	};

	public WayfinderScreen() {
		super(Component.literal("Wayfinder"));
		selected = lastSelected != null ? lastSelected : here();
	}

	/** The place the player is in: the active hub, or the town. */
	private static String here() {
		Hub h = Hubs.active();
		return h == null ? "town" : h.id();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ places

	public static List<Place> places() {
		List<Place> out = new ArrayList<>();
		Place town = town();
		if (town != null) {
			out.add(town);
		}
		List<Hub> hubs = new ArrayList<>(Hubs.all());
		for (Hub h : hubs) {
			if (FreelancePavilion.HUB.equals(h.id())) {
				continue;
			}
			out.add(hub(h));
		}
		Anchors.Layout fl = Anchors.of(FreelancePavilion.HUB);
		if (!fl.isEmpty()) {
			List<Spot> spots = new ArrayList<>();
			add(spots, "Pavilion", fl.get(AnchorNames.SPAWN), false);
			add(spots, "Scout's terminal", fl.get(AnchorNames.USER), false);
			add(spots, "Scout's desk", fl.get("cam_desk_" + FreelancePavilion.AGENT), true);
			out.add(new Place(FreelancePavilion.HUB, "Freelancer", 0xFF7E6BC4, spots, null));
		}
		return out;
	}

	private static @Nullable Place town() {
		if (!HubRegistry.COMPASS.equals(HubRegistry.layout())) {
			return null;
		}
		Anchor mainSpawn = Anchors.of(HubRegistry.MAIN).get(AnchorNames.SPAWN);
		double y = mainSpawn != null ? mainSpawn.y() : 65;
		double cx = HubRegistry.CENTER_X + 0.5, cz = HubRegistry.centerZ() + 0.5;
		List<Spot> spots = new ArrayList<>();
		spots.add(new Spot("Plaza & fountain", cx, y, cz + 11, 180, 12));
		spots.add(new Spot("Hub wall", cx, y, cz + 15, 0, -8));
		spots.add(new Spot("Help: keys & clicks", cx, y, cz - 16, 180, -6));
		spots.add(new Spot("Help: console", cx - 16, y, cz, 90, -6));
		spots.add(new Spot("Help: hubs & town", cx + 17, y, cz, -90, -6));
		Anchor pav = Anchors.of(FreelancePavilion.HUB).get(AnchorNames.SPAWN);
		if (pav != null) {
			spots.add(new Spot("Freelancer pavilion", pav.x(), pav.y(), pav.z(), pav.yaw(), 0));
		}
		return new Place("town", "Town", 0xFFC9A227, spots, null);
	}

	private static Place hub(Hub h) {
		HubRegistry.Hub reg = HubRegistry.get(h.id());
		int accent = Theme.byId(reg == null ? null : reg.theme()).accent;
		Anchors.Layout l = Anchors.of(h.id());
		if (l.isEmpty()) {
			return new Place(h.id(), h.name(), accent, List.of(), "Its studio isn't built yet: /ac hub build " + h.id());
		}
		List<Spot> spots = new ArrayList<>();
		for (Object[] s : STUDIO) {
			add(spots, (String) s[0], l.get((String) s[1]), (Boolean) s[2]);
		}
		// every agent's desk: the over-the-shoulder view of its monitor
		for (String name : l.anchors().keySet()) {
			if (name.startsWith("cam_desk_")) {
				String id = name.substring("cam_desk_".length());
				Agent a = h.state().agents().get(id);
				String who = a != null ? a.name() : id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
				add(spots, who + "'s desk", l.get(name), true);
			}
		}
		return new Place(h.id(), h.name(), accent, spots, null);
	}

	private static void add(List<Spot> spots, String label, @Nullable Anchor a, boolean eye) {
		if (a != null) {
			spots.add(new Spot(label, a.x(), eye ? a.y() - EYE : a.y(), a.z(), a.yaw(), eye ? a.pitch() : 0));
		}
	}

	private @Nullable Place current(List<Place> places) {
		for (Place p : places) {
			if (p.id().equals(selected)) {
				return p;
			}
		}
		return places.isEmpty() ? null : places.getFirst();
	}

	// ------------------------------------------------------------------ drawing

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
		List<Place> places = places();
		Place cur = current(places);
		int inner = W - pad.left() - pad.right();
		int listRows = Math.max(4, Math.min(places.size(), (height - 90) / ROW));
		int h = pad.top() + 22 + listRows * ROW + 22 + pad.bottom();
		int px = (width - W) / 2;
		int py = Math.max(4, (height - h) / 2);
		Panels.panel(g, px, py, W, h);
		int x = px + pad.left();
		int y = py + pad.top();
		Panels.header(g, font, "Wayfinder" + (cur != null ? "  ·  " + cur.name() : ""), x, y, inner);
		y += 22;
		// ---- places
		int maxScroll = Math.max(0, places.size() - listRows);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		String hereId = here();
		for (int i = scroll; i < Math.min(places.size(), scroll + listRows); i++) {
			Place p = places.get(i);
			int ry = y + (i - scroll) * ROW;
			boolean on = cur != null && cur.id().equals(p.id());
			boolean hover = mouseX >= x && mouseX < x + LIST_W && mouseY >= ry && mouseY < ry + ROW - 2;
			if (on || hover) {
				g.fill(x, ry, x + LIST_W, ry + ROW - 2, UiStyle.withAlpha(on ? p.accent() : UiStyle.WALNUT, on ? 70 : 30));
			}
			g.fill(x, ry, x + 3, ry + ROW - 2, p.accent() | 0xFF000000);
			String label = TextUtil.ellipsize(font, p.name(), LIST_W - 20);
			Panels.text(g, font, label, x + 8, ry + 4, p.why() != null ? muted : ink);
			if (p.id().equals(hereId)) {
				Panels.dot(g, "working", x + LIST_W - 10, ry + 4, false);
			}
			buttons.add(new Btn("place", i, p.id(), x, ry, LIST_W, ROW - 2));
		}
		// ---- spots of the selected place: two columns of buttons
		int sx = x + LIST_W + 10;
		int sw = inner - LIST_W - 10;
		if (cur == null) {
			Panels.text(g, font, "Nothing to go to yet.", sx, y + 4, muted);
		} else if (cur.why() != null) {
			Panels.text(g, font, TextUtil.ellipsize(font, cur.why(), sw), sx, y + 4, muted);
		} else {
			int colW = (sw - 6) / 2;
			for (int i = 0; i < cur.spots().size(); i++) {
				Spot s = cur.spots().get(i);
				int bx = sx + (i % 2) * (colW + 6);
				int by = y + (i / 2) * 22;
				if (by + 20 > py + h - pad.bottom() - 20) {
					break;
				}
				boolean hover = mouseX >= bx && mouseX < bx + colW && mouseY >= by && mouseY < by + 20;
				String label = (i < 9 ? (i + 1) + "  " : "") + s.label();
				UiBits.button(g, font, TextUtil.ellipsize(font, label, colW - 10), 0, bx, by, colW, i == 0, hover ? UiBits.ButtonState.HOVER
					: UiBits.ButtonState.NORMAL, false);
				buttons.add(new Btn("go", i, cur.id(), bx, by, colW, 20));
			}
		}
		String foot = "pick a place, then where to go  ·  1-9 go  ·  up/down change place  ·  /ac wayfinder gives another";
		Panels.text(g, font, TextUtil.ellipsize(font, foot, inner), x, py + h - pad.bottom() - 10, muted);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (Btn b : List.copyOf(buttons)) {
				if (b.hit(event.x(), event.y())) {
					if (b.action().equals("place")) {
						select(b.id());
					} else {
						go(b.arg());
					}
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		scroll -= (int) Math.signum(dy);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		if (k >= InputConstants.KEY_1 && k <= InputConstants.KEY_9) {
			go(k - InputConstants.KEY_1);
			return true;
		}
		if (k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN) {
			List<Place> places = places();
			int at = 0;
			for (int i = 0; i < places.size(); i++) {
				if (places.get(i).id().equals(selected)) {
					at = i;
				}
			}
			int next = Math.floorMod(at + (k == InputConstants.KEY_UP ? -1 : 1), Math.max(1, places.size()));
			if (!places.isEmpty()) {
				select(places.get(next).id());
			}
			return true;
		}
		return super.keyPressed(event);
	}

	void select(String id) {
		selected = id;
		lastSelected = id;
	}

	String selectedId() {
		return selected;
	}

	/** Teleport to spot {@code i} of the selected place and close; the spot's label, or null. */
	@Nullable String go(int i) {
		Place cur = current(places());
		if (cur == null || cur.why() != null || i < 0 || i >= cur.spots().size()) {
			return null;
		}
		Spot s = cur.spots().get(i);
		lastSelected = cur.id();
		WayfinderFeature.teleport(s.x(), s.y(), s.z(), s.yaw(), s.pitch());
		onClose();
		return String.format(Locale.ROOT, "%s / %s", cur.name(), s.label());
	}
}
