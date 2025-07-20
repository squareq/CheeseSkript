package ch.njol.skript.lang.function;

import ch.njol.skript.Skript;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.lang.*;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.skript.variables.HintManager;
import ch.njol.skript.variables.Variables;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.script.Script;

public class ScriptFunction<T> extends Function<T> implements ReturnHandler<T> {

	private final Trigger trigger;

	private boolean returnValueSet;
	private T @Nullable [] returnValues;
	private String @Nullable [] returnKeys;

	/**
	 * @deprecated use {@link ScriptFunction#ScriptFunction(Signature, SectionNode)} instead.
	 */
	@Deprecated(since = "2.9.0", forRemoval = true)
	public ScriptFunction(Signature<T> sign, Script script, SectionNode node) {
		this(sign, node);
	}

	public ScriptFunction(Signature<T> sign, SectionNode node) {
		super(sign);

		Functions.currentFunction = this;
		HintManager hintManager = ParserInstance.get().getHintManager();
		try {
			hintManager.enterScope(false);
			for (Parameter<?> parameter : sign.getParameters()) {
				String hintName = parameter.getName();
				if (!parameter.isSingleValue()) {
					hintName += Variable.SEPARATOR + "*";
				}
				hintManager.set(hintName, parameter.getType().getC());
			}
			trigger = loadReturnableTrigger(node, "function " + sign.getName(), new SimpleEvent());
		} finally {
			hintManager.exitScope();
			Functions.currentFunction = null;
		}
		trigger.setLineNumber(node.getLine());
	}

	// REMIND track possible types of local variables (including undefined variables) (consider functions, commands, and EffChange) - maybe make a general interface for this purpose
	// REM: use patterns, e.g. {_a%b%} is like "a.*", and thus subsequent {_axyz} may be set and of that type.
	@Override
	public T @Nullable [] execute(FunctionEvent<?> event, Object[][] params) {
		Parameter<?>[] parameters = getSignature().getParameters();
		for (int i = 0; i < parameters.length; i++) {
			Parameter<?> parameter = parameters[i];
			Object[] val = params[i];
			if (parameter.single && val.length > 0) {
				Variables.setVariable(parameter.name, val[0], event, true);
			} else {
				for (Object value : val) {
					KeyedValue<?> keyedValue = (KeyedValue<?>) value;
					Variables.setVariable(parameter.name + "::" + keyedValue.key(), keyedValue.value(), event, true);
				}
			}
		}
		if(event.getFunction().getSignature().isAsync()){
			new BukkitRunnable() {

				@Override
				public void run() {
					trigger.execute(event);
				}

			}.runTaskAsynchronously(Skript.getInstance());
		} else{
			trigger.execute(event);
		}
		ClassInfo<T> returnType = getReturnType();
		return returnType != null ? returnValues : null;
	}

	@Override
	public @NotNull String @Nullable [] returnedKeys() {
		return returnKeys;
	}

	/**
	 * @deprecated Use {@link ScriptFunction#returnValues(Event, Expression)} instead.
	 */
	@Deprecated(since = "2.9.0", forRemoval = true)
	@ApiStatus.Internal
	public final void setReturnValue(@Nullable T[] values) {
		assert !returnValueSet;
		returnValueSet = true;
		this.returnValues = values;
	}

	@Override
	public boolean resetReturnValue() {
		returnValueSet = false;
		returnValues = null;
		returnKeys = null;
		return true;
	}

	@Override
	public final void returnValues(Event event, Expression<? extends T> value) {
		assert !returnValueSet;
		returnValueSet = true;
		this.returnValues = value.getArray(event);
		if (KeyProviderExpression.canReturnKeys(value))
			this.returnKeys = ((KeyProviderExpression<?>) value).getArrayKeys(event);
	}

	@Override
	public final boolean isSingleReturnValue() {
		return isSingle();
	}

	@Override
	public final @Nullable Class<? extends T> returnValueType() {
		return getReturnType() != null ? getReturnType().getC() : null;
	}

}
