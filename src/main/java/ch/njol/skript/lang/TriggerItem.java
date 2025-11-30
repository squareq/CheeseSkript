package ch.njol.skript.lang;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.simplification.Simplifiable;
import ch.njol.skript.sections.SecLoop;
import ch.njol.skript.util.SkriptColor;
import ch.njol.util.Kleenean;
import ch.njol.util.StringUtils;
import org.bukkit.event.Event;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.script.Script;

import java.io.File;

/**
 * Represents a trigger item, i.e. a trigger section, a condition or an effect.
 * 
 * @author Peter Güttinger
 * @see TriggerSection
 * @see Trigger
 * @see Statement
 */
public abstract class TriggerItem implements Debuggable {

	protected @Nullable TriggerSection parent = null;
	private @Nullable TriggerItem next = null;
	private @Nullable Boolean delay = false;
	private @Nullable SyntaxElement syntaxElement;
	private @Nullable KeyedValue<?>[] keyedValue = null;

	protected TriggerItem() {}

	protected TriggerItem(TriggerSection parent) {
		this.parent = parent;
	}

	/**
	 * Executes this item and returns the next item to run.
	 * <p>
	 * Overriding classes must call {@link #debug(Event, boolean)}. If this method is overridden, {@link #run(Event)} is not used anymore and can be ignored.
	 * 
	 * @param event The event
	 * @return The next item to run or null to stop execution
	 */
	protected @Nullable TriggerItem walk(Event event) {
		if (run(event)) {
			debug(event, true);
			return next;
		} else {
			debug(event, false);
			TriggerSection parent = this.parent;
			return parent == null ? null : parent.getNext();
		}
	}

	/**
	 * Executes this item.
	 * 
	 * @param event The event to run this item with
	 * @return True if the next item should be run, or false for the item following this item's parent.
	 */
	protected abstract boolean run(Event event);

	/**
	 * @param start The item to start at
	 * @param event The event to run the items with
	 * @return false if an exception occurred
	 */
	public static boolean walk(TriggerItem start, Event event) {
		TriggerItem triggerItem = start;
		try {
			while (triggerItem != null) {
				if(triggerItem.isDelayed()){
					final Expression<?>[] expressions = (Expression<?>[]) triggerItem.getKeyedValues()[0].value();
					final Integer matchedPattern = (Integer) triggerItem.getKeyedValues()[1].value();
					final Kleenean delay = (Kleenean) triggerItem.getKeyedValues()[2].value();
					final SkriptParser.ParseResult parseResult = (SkriptParser.ParseResult) triggerItem.getKeyedValues()[3].value();
					final TriggerItem delayedTriggerItem = triggerItem; //The trigger item that is delayed.
					final TriggerItem resumeTrigger = triggerItem.getNext(); //Resume at the next trigger after the delay.
					BukkitRunnable resumePoint = new BukkitRunnable() { //
						@Override
						public void run() {
							TriggerItem triggerItem = resumeTrigger;
							while (triggerItem != null){
								triggerItem = triggerItem.walk(event);
								if(triggerItem == null)
									break;
							}
						}
					};
					BukkitRunnable delayedPoint = new BukkitRunnable() {
						@Override
						public void run() {
							if (delayedTriggerItem.getSyntaxElement().preInit() && delayedTriggerItem.getSyntaxElement().init(expressions, matchedPattern, delay, parseResult)) {
								delayedTriggerItem.run(event);
							}
						}

					};
					/*
					some async function stuff
					 */
					for(int i = 0; i < expressions.length; i++) {
						Expression e = expressions[i];
						e.getAll(event);
					}
					delayedPoint.runTaskLater(Skript.getInstance(), 40);
					resumePoint.runTaskLater(Skript.getInstance(), 80);
					break;
				} else {
					triggerItem = triggerItem.walk(event);
					if (triggerItem == null) {
						break;
					}
				}
			}
			return true;
		} catch (StackOverflowError err) {
			Trigger trigger = start.getTrigger();
			String scriptName = "<unknown>";
			if (trigger != null) {
				Script script = trigger.getScript();
				if (script != null) {
					File scriptFile = script.getConfig().getFile();
					if (scriptFile != null)
						scriptName = scriptFile.getName();
				}
			}
			Skript.adminBroadcast("<red>The script '<gold>" + scriptName + "<red>' infinitely (or excessively) repeated itself!");
			if (Skript.debug())
				err.printStackTrace();
		} catch (Exception ex) {
			if (ex.getStackTrace().length != 0) // empty exceptions have already been printed
				Skript.exception(ex, triggerItem);
		} catch (Throwable throwable) {
			// not all Throwables are Exceptions, but we usually don't want to catch them (without rethrowing)
			Skript.markErrored();
			throw throwable;
		}
		return false;
	}

	/**
	 * Returns whether this item stops the execution of the current trigger or section(s).
	 * <br>
	 * If present, and there are statement(s) after this one, the parser will print a warning
	 * to the user.
	 * <p>
	 * <b>Note: This method is used purely to print warnings and doesn't affect parsing, execution or anything else.</b>
	 *
	 * @return whether this item stops the execution of the current trigger or section.
	 */
	public @Nullable ExecutionIntent executionIntent() {
		return null;
	}


	public void setCopy(SyntaxElement syntaxElement, KeyedValue<?>[] keyedValues){
		this.syntaxElement = syntaxElement;
		this.keyedValue = keyedValues;
		this.setDelay(true);
	}

	public @Nullable SyntaxElement getSyntaxElement(){
		return this.syntaxElement;
	}

	public @Nullable KeyedValue<?>[] getKeyedValues(){
		return this.keyedValue;
	};

	protected boolean isDelayed(){
		return delay;
	}

	private void setDelay(boolean b){
		delay = b;
	}

	/**
	 * how much to indent each level
	 */
	private final static String INDENT = "  ";

	private @Nullable String indentation = null;

	public String getIndentation() {
		if (indentation == null) {
			int level = 0;
			TriggerItem triggerItem = this;
			while ((triggerItem = triggerItem.parent) != null)
				level++;
			indentation = StringUtils.multiply(INDENT, level);
		}
		return indentation;
	}

	protected final void debug(Event event, boolean run) {
		if (!Skript.debug())
			return;
		Skript.debug(SkriptColor.replaceColorChar(getIndentation() + (run ? "" : "-") + toString(event, true)));
	}

	@Override
	public final String toString() {
		try{
			return toString(null, false);
		} catch (NullPointerException e){
			return "trigger item";
		}
	}

	public TriggerItem setParent(@Nullable TriggerSection parent) {
		this.parent = parent;
		return this;
	}

	public final @Nullable TriggerSection getParent() {
		return parent;
	}

	/**
	 * @return The trigger this item belongs to, or null if this is a stand-alone item (e.g. the effect of an effect command)
	 */
	public final @Nullable Trigger getTrigger() {
		TriggerItem triggerItem = this;
		while (triggerItem != null && !(triggerItem instanceof Trigger))
			triggerItem = triggerItem.getParent();
		return (Trigger) triggerItem;
	}

	public TriggerItem setNext(@Nullable TriggerItem next) {
		this.next = next;
		return this;
	}

	public @Nullable TriggerItem getNext() {
		return next;
	}

	/**
	 * This method guarantees to return next {@link TriggerItem} after this item.
	 * This is not always the case for {@link #getNext()}, for example, {@code getNext()}
	 * of a {@link SecLoop loop section} usually returns itself.
	 * 
	 * @return The next {@link TriggerItem}.
	 */
	public @Nullable TriggerItem getActualNext() {
		return next;
	}

}
