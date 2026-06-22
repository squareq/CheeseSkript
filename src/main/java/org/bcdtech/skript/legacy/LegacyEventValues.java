package org.bcdtech.skript.legacy;

import ch.njol.skript.Skript;
import ch.njol.skript.util.Getter;
import org.bukkit.event.Event;
import org.skriptlang.skript.bukkit.lang.eventvalue.EventValue;
import org.skriptlang.skript.bukkit.lang.eventvalue.EventValueRegistry;

public final class LegacyEventValues {

	private LegacyEventValues() {}

	public static <T, E extends Event> void registerEventValue(Class<E> eventClass, Class<T> valueClass, Getter<T, E> getter, int time){
		final EventValue eventValue = EventValue.builder(eventClass, valueClass)
			.getter(getter)
			.build();
		Skript.instance().registry(EventValueRegistry.class).register(eventValue);
	}
}
