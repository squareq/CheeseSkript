package org.bcdtech.skript.bukkit.classes.data;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.Changer;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.EnumClassInfo;
import ch.njol.skript.classes.Parser;
import ch.njol.skript.lang.ParseContext;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.skriptlang.skript.lang.properties.Property;
import org.skriptlang.skript.lang.properties.handlers.base.ExpressionPropertyHandler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static ch.njol.skript.registrations.Classes.registerClass;

public class BukkitClasses {

	public BukkitClasses() {
	}

	static {
		registerClass((new ClassInfo<>(CompletableFuture.class, "completablefuture")
				.user("completable ?futures?")
				.name("Completable Future")
			));
		registerClass(new EnumClassInfo<>(MapView.Scale.class, "mapviewscale", "Map View Scales")
			.user("map view ?scales?")
			.name("Map View Scale")
			.description("The possible scales a map can be set to.")
				.parser(new Parser<>() {

					@Override
					public boolean canParse(ParseContext context) {
						return context == ParseContext.COMMAND || context == ParseContext.PARSE;
					}

					@Override
					public MapView.@Nullable Scale parse(String s, ParseContext context) {
						try {
							return MapView.Scale.valueOf(s);
						} catch (IllegalArgumentException e) {
							return null;
						}
					}

					@Override
					public String toString(MapView.Scale o, int flags) {
						return o.toString();
					}

					@Override
					public String toVariableNameString(MapView.Scale o) {
						return o.toString();
					}
				}));
		registerClass(new ClassInfo<>(MapView.class, "mapview")
			.user("map ?views?")
			.name("Map View")
			.property(Property.SCALE,
			"Set the scale of this map.",
			Skript.instance(), new ExpressionPropertyHandler<MapView, MapView.Scale>() {
					@Override
					public MapView.@Nullable Scale convert(MapView propertyHolder) {
						return Objects.requireNonNull(propertyHolder.getScale());
				}

					@Override
					public Class<?> @org.jetbrains.annotations.Nullable [] acceptChange(Changer.ChangeMode mode) {
				 		return (mode == Changer.ChangeMode.SET) || (mode == Changer.ChangeMode.RESET) ? new Class[] {MapView.Scale.class} : null;
					}

					@Override
					public void change(MapView propertyHolder, Object @org.jetbrains.annotations.Nullable [] delta, Changer.ChangeMode mode) {
						Objects.requireNonNull(delta);
						if(mode == Changer.ChangeMode.RESET) {
							propertyHolder.setScale(MapView.Scale.NORMAL);
							return;
						}
						if(delta[0] instanceof MapView.Scale scale && delta.length == 1) {
							propertyHolder.setScale(scale);
						}
					}

					@Override
					public @NotNull Class<MapView.Scale> returnType() {
						return MapView.Scale.class;
					}
				})
		);
		registerClass(new ClassInfo<>(MapRenderer.class, "maprenderer")
			.user("map ?renderers?")
			.name("Map Renderer")
			.description("The possible scales a map can be set to.")
		);
	}
}
