package org.bcdtech.skript.bukkit;

import ch.njol.skript.Skript;
import org.bcdtech.skript.bukkit.map.MapModule;
import org.skriptlang.skript.addon.AddonModule;
import org.skriptlang.skript.addon.HierarchicalAddonModule;
import org.skriptlang.skript.addon.SkriptAddon;

import java.util.List;

public class BukkitModule extends HierarchicalAddonModule {

	@Override
	protected boolean canLoadSelf(SkriptAddon addon) {
		return Skript.classExists("org.bukkit.Bukkit");
	}

	@Override
	public Iterable<AddonModule> children() {
		return List.of(
			new MapModule(this)
		);
	}

	@Override
	protected void loadSelf(SkriptAddon addon) {
		// nothing to do
	}

	@Override
	public String name() {
		return "bukkit";
	}

}
