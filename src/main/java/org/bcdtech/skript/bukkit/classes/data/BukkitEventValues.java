package org.bcdtech.skript.bukkit.classes.data;

import org.bcdtech.skript.bukkit.map.maprenderer.section.MapRendererSection;
import org.bukkit.map.MapRenderer;
import org.skriptlang.skript.bukkit.lang.eventvalue.EventValueRegistry;

import static org.skriptlang.skript.bukkit.lang.eventvalue.EventValue.builder;

//ToDo: Change the registry for Bukkit to CheeseSkript
public final class BukkitEventValues {

	public static void register(EventValueRegistry registry) {

		registry.register(
			builder(MapRendererSection.SecCreateMapRenderer.MapRendererCreatedEvent.class, MapRenderer.class)
				.getter(MapRendererSection.SecCreateMapRenderer.MapRendererCreatedEvent::getMapRenderer)
				.build()
		);
	}
}
