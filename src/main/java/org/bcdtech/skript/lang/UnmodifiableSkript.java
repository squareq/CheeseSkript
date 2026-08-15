package org.bcdtech.skript.lang;

import com.google.common.collect.ImmutableSet;
import org.jetbrains.annotations.Unmodifiable;
import org.skriptlang.skript.Skript;
import org.skriptlang.skript.addon.AddonModule;
import org.skriptlang.skript.addon.SkriptAddon;
import org.skriptlang.skript.localization.Localizer;
import org.skriptlang.skript.registration.SyntaxRegistry;
import org.skriptlang.skript.util.Registry;

import java.util.Collection;
import java.util.function.Supplier;

public class UnmodifiableSkript implements Skript {

	private final Skript skript;
	private final SkriptAddon unmodifiableAddon;

	public UnmodifiableSkript(Skript skript, SkriptAddon unmodifiableAddon) {
		this.skript = skript;
		this.unmodifiableAddon = unmodifiableAddon;
	}

	@Override
	public SkriptAddon registerAddon(Class<?> source, String name) {
		throw new UnsupportedOperationException("Cannot register addons using an unmodifiable Skript");
	}

	@Override
	public @Unmodifiable Collection<SkriptAddon> addons() {
		ImmutableSet.Builder<SkriptAddon> addons = ImmutableSet.builder();
		skript.addons().stream()
			.map(SkriptAddon::unmodifiableView)
			.forEach(addons::add);
		return addons.build();
	}

	@Override
	public Class<?> source() {
		return skript.source();
	}

	@Override
	public String name() {
		return skript.name();
	}

	@Override
	public <R extends Registry<?>> void storeRegistry(Class<R> registryClass, R registry) {
		unmodifiableAddon.storeRegistry(registryClass, registry);
	}

	@Override
	public void removeRegistry(Class<? extends Registry<?>> registryClass) {
		unmodifiableAddon.removeRegistry(registryClass);
	}

	@Override
	public boolean hasRegistry(Class<? extends Registry<?>> registryClass) {
		return unmodifiableAddon.hasRegistry(registryClass);
	}

	@Override
	public <R extends Registry<?>> R registry(Class<R> registryClass) {
		return unmodifiableAddon.registry(registryClass);
	}

	@Override
	public <R extends Registry<?>> R registry(Class<R> registryClass, Supplier<R> putIfAbsent) {
		return unmodifiableAddon.registry(registryClass, putIfAbsent);
	}

	@Override
	public SyntaxRegistry syntaxRegistry() {
		return unmodifiableAddon.syntaxRegistry();
	}

	@Override
	public Localizer localizer() {
		return unmodifiableAddon.localizer();
	}

	@Override
	public void loadModules(AddonModule... modules) {
		unmodifiableAddon.loadModules(modules);
	}

	@Override
	public Skript unmodifiableView() {
		return this;
	}

}
