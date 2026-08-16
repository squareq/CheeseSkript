package org.bcdtech.skript;

import ch.njol.skript.lang.SyntaxElement;
import com.google.common.collect.ImmutableSet;
import org.bcdtech.skript.lang.UnmodifiableSkript;
import org.jetbrains.annotations.Unmodifiable;
import org.skriptlang.skript.Skript;
import org.skriptlang.skript.addon.SkriptAddon;
import org.skriptlang.skript.docs.Origin;
import org.skriptlang.skript.localization.Localizer;
import org.skriptlang.skript.registration.SyntaxRegistry;
import org.skriptlang.skript.util.Registry;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * An implementation of {@link Skript} for CheeseSkript.
 * Contains only the registry and a {@link SkriptAddon}
 * associated with it. Registering any further addons
 * is not allowed!
 * Access the instance with {@link CheeseSkript#getOrCreate()}.
 * This class is singleton and only one instance should exist!
 */
public final class CheeseSkript implements Skript {

	private static CheeseSkript CheeseSkript; //Singleton
	private static Boolean Enabled = false;
	private final String name = "CheeseSkript";
	private final String addonRegistrationError = name + " doesn't allow skript addons. Use Njol's Skript instead.";
	private SkriptAddon cheeseAddon;
	private final Map<String, SkriptAddon> addons = new HashMap<>();

	/**
	 * CheeseSkript implementation
	 */

	private CheeseSkript() {
		if(cheeseAddon != null) {
			throw new IllegalStateException("Cannot create more than once instance");
		}
	}

	/**
	 * Called when {@link CheeseSkript} is first created.
	 */
	private void onEnable() {
		if (!isEnabled()){
			Enabled = true;
			//ToDo: BUkkit Moduules
			//End
		} else {
			throw new IllegalStateException("CheeseSkript is already enabled");
		}
	}

	/**
	 * Returns true if {@link CheeseSkript}
	 * has been instantiated.
	 */
	public static Boolean isEnabled() {
		return Enabled;
	}

	/**
	 * Get the instance of {@link CheeseSkript} or creates it
	 * then returns if it doesn't exist.
	 * @return {@link CheeseSkript}
	 */
	public static CheeseSkript getOrCreate(){
		if(CheeseSkript == null){
			CheeseSkript = new CheeseSkript();
			CheeseSkript.setCheeseAddon((CheeseSkriptAddonImpl) CheeseSkript.registerAddon(CheeseSkript.class, CheeseSkript.name));
			CheeseSkript.onEnable();
		}
		return CheeseSkript;
	}

	/**
	 * Set the {@link SkriptAddon} associated with {@link CheeseSkript}
	 * @param cheeseAddon The {@link CheeseSkriptAddonImpl}
	 * @throws IllegalStateException if the addon is already set.
	 */
	private void setCheeseAddon(CheeseSkriptAddonImpl cheeseAddon) throws IllegalStateException {
		if(this.cheeseAddon == null){
			this.cheeseAddon = cheeseAddon;
			addons.put(name, cheeseAddon);
		} else {
			throw new IllegalStateException("Addon cannot be changed once set.");
		}
	}

	/**
	 * Registry management.
	 */

	private final Map<Class<?>, Registry<?>> registries = new ConcurrentHashMap<>();

	@Override
	public <R extends Registry<?>> void storeRegistry(Class<R> registryClass, R registry) {
		registries.put(registryClass, registry);
	}

	@Override
	public void removeRegistry(Class<? extends Registry<?>> registryClass) {
		registries.remove(registryClass);
	}

	@Override
	public boolean hasRegistry(Class<? extends Registry<?>> registryClass) {
		return registries.containsKey(registryClass);
	}

	@Override
	public <R extends Registry<?>> R registry(Class<R> registryClass) {
		//noinspection unchecked
		R registry = (R) registries.get(registryClass);
		if (registry == null)
			throw new NullPointerException("Registry not present for " + registryClass);
		return registry;
	}

	@Override
	public <R extends Registry<?>> R registry(Class<R> registryClass, Supplier<R> putIfAbsent) {
		//noinspection unchecked
		return (R) registries.computeIfAbsent(registryClass, key -> putIfAbsent.get());
	}

	/**
	 * {@link CheeseSkript}'s addon registry/implementation
	 * supports only {@link CheeseSkriptAddonImpl}.
	 * Registering syntax is not supported!!!!
	 */

	@Override
	public SkriptAddon registerAddon(Class<?> source, String name) {
		if(source.isInstance(CheeseSkript.class)){
			throw new UnsupportedOperationException(addonRegistrationError);
		}
		return new CheeseSkriptAddonImpl(CheeseSkript, source, name, null);
	}

	@Override
	public @Unmodifiable Collection<SkriptAddon> addons() {
		return ImmutableSet.copyOf(addons.values());
	}

	@Override
	public Class<?> source() {
		return cheeseAddon.source();
	}

	@Override
	public String name() {
		return cheeseAddon.name();
	}

	@Override
	public SyntaxRegistry syntaxRegistry() {
		return ch.njol.skript.Skript.instance().syntaxRegistry();
	}

	@Override
	public Localizer localizer() {
		return ch.njol.skript.Skript.instance().localizer();
	}

	/**
	 * Implementation of {@link SkriptAddon} associated with
	 * and only with {@link CheeseSkript}
	 */
	private static final class CheeseSkriptAddonImpl implements SkriptAddon {

		private CheeseSkript cheeseSkript;
		private Class<?> source;
		private String addonName;
		private Localizer localizer;
		private Origin origin;

		private CheeseSkriptAddonImpl(CheeseSkript cheeseSkript, Class<?> source, String name, Localizer localizer) {
			this.cheeseSkript = cheeseSkript;
			this.source = source;
			this.addonName = name;
			this.localizer = localizer;
			this.origin = Origin.of(this);
		}

		@Override
		public Class<?> source() {
			return source;
		}

		@Override
		public String name() {
			return addonName;
		}

		@Override
		public <R extends Registry<?>> void storeRegistry(Class<R> registryClass, R registry) {
			cheeseSkript.storeRegistry(registryClass, registry);
		}

		@Override
		public void removeRegistry(Class<? extends Registry<?>> registryClass) {
			cheeseSkript.removeRegistry(registryClass);
		}

		@Override
		public boolean hasRegistry(Class<? extends Registry<?>> registryClass) {
			return cheeseSkript.hasRegistry(registryClass);
		}

		@Override
		public <R extends Registry<?>> R registry(Class<R> registryClass) {
			R registry = cheeseSkript.registry(registryClass);
			if (registryClass == SyntaxRegistry.class) {
				//noinspection unchecked
				return (R) SyntaxRegistry.withOrigin((SyntaxRegistry) registry, origin);
			}
			return registry;
		}

		@Override
		public <R extends Registry<?>> R registry(Class<R> registryClass, Supplier<R> putIfAbsent) {
			return cheeseSkript.registry(registryClass, putIfAbsent);
		}

		@Override
		public SyntaxRegistry syntaxRegistry() {
			return registry(SyntaxRegistry.class);
		}

		@Override
		public Localizer localizer() {
			return localizer;
		}

		@Override
		public SkriptAddon unmodifiableView() {
			return new UnmodifiableSkript(cheeseSkript, this);
		}
	}
}
