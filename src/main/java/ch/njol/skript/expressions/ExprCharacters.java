package ch.njol.skript.expressions;

import ch.njol.skript.Skript;
import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.SyntaxStringBuilder;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import org.apache.commons.lang.ArrayUtils;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import ch.njol.skript.lang.simplification.SimplifiedLiteral;

import java.util.Objects;

@Name("Characters Between")
@Description({
	"All characters between two given characters, useful for generating random strings. This expression uses the Unicode numerical code " +
	"of a character to determine which characters are between the two given characters. The <a href=\"https://www.asciitable.com/\">ASCII table linked here</a> " +
	"shows this ordering for the first 256 characters.",
	"If you would like only alphanumeric characters you can use the 'alphanumeric' option in the expression.",
	"If strings of more than one character are given, only the first character of each is used.",
	"To get all of the characters in a string, simply use the %all the characters in {_string}% pattern."
})
@Examples({
	"loop characters from \"a\" to \"f\":",
		"\tbroadcast \"%loop-value%\"",
	"",
	"# 0123456789:;<=>?@ABC... ...uvwxyz",
	"send characters between \"0\" and \"z\"",
	"",
	"# 0123456789ABC... ...uvwxyz",
	"send alphanumeric characters between \"0\" and \"z\""
})
/*
TODO: Everything seems to look good. Just need to update Skript Documentation, as well as
code explanations to #get and #init. Also, explain where BCD starts & ends. A little testing of
both syntaxes wouldn't hurt as well.
 */

@Since("2.8.0")
public class ExprCharacters extends SimpleExpression<String> {

	static {
		Skript.registerExpression(ExprCharacters.class, String.class, ExpressionType.COMBINED,
				"[(all [[of] the]|the)] [:alphanumeric] characters (between|from) %string% (and|to) %string%", "[(all [[of] the]|the)] [:alphanumeric] characters in %string%");
	}

	private @Nullable Expression<String> start, end;
	private boolean isAlphanumeric;

	@Override
	public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
		start = (Expression<String>) exprs[0];
		end = matchedPattern == 0 ? (Expression<String>) exprs[1] : null; //BCD - Support both patterns.
		isAlphanumeric = parseResult.hasTag("alphanumeric");
		return true;
	}

	@Override
	@Nullable
	protected String[] get(Event event) {
		String start = this.start.getSingle(event);
		String end = (Objects.isNull(this.end)) ? this.start.getSingle(event) : this.end.getSingle(event); //BCD - Support both patterns.
		char startChar = start.charAt(0);
		char endChar = (Objects.isNull(this.end)) ? start.charAt(start.length() - 1) : end.charAt(0); //BCD - Support both patterns.

		boolean reversed = startChar > endChar;
		char delta = reversed ? (char) -1 : (char) 1;

		int min = Math.min(startChar, endChar);
		int max = Math.max(startChar, endChar);

		String[] chars = new String[max - min + 1];
		//Pattern 0
		if(!Objects.isNull(this.end)){
			for (char c = startChar; min <= c && c <= max; c += delta) {
				if (isAlphanumeric && !Character.isLetterOrDigit(c))
					continue;
				chars[c - min] = String.valueOf(c);
			}
			if (reversed)
				ArrayUtils.reverse(chars);
			return chars;
			//END
		//BCD - Pattern 1
		} else {
			chars = new String[]{String.valueOf(this.start.getSingle(event).toCharArray())}; //Get the passed string from the expression.
			for(String c : chars){
				final int n = c.length() - 1;
				int removed = 0;
				//If isAlphanumeric is true, then index each character in the string to check if it is alphanumeric.
				if(isAlphanumeric)
					for(int i = 0; i < n; i++){
						try{
							if (isAlphanumeric && Character.isLetterOrDigit(c.charAt(i - removed)))
								continue;
							c = c.replace(String.valueOf(c.charAt(i - removed)), "");
							if(removed == c.length()){
								c = c.replace(String.valueOf(c.charAt(c.length() - 1)), "");
								break;
							}
							removed++;
						} catch (StringIndexOutOfBoundsException e){
							c = c.replace(String.valueOf(c.charAt(c.length() - 1)), "");
						}
				}
				chars = c.split("");
			return chars;
			}
			//END
		}
	return null;
	}

	@Override
	public boolean isSingle() {
		return false;
	}

	@Override
	public Class<? extends String> getReturnType() {
		return String.class;
	}

	@Override
	public Expression<? extends String> simplify() {
		if (start instanceof Literal<?> && end instanceof Literal<?>)
			return SimplifiedLiteral.fromExpression(this);
		return this;
	}

	@Override
	public String toString(@Nullable Event event, boolean debug) {
		SyntaxStringBuilder builder = new SyntaxStringBuilder(event, debug);
		builder.append("[(all [[of] the]|the)]");
		if(isAlphanumeric){
			builder.append("[:alphanumeric]");
		}
		builder.append("characters");
		if (!(Objects.isNull(this.end))){
			builder.append("(between|from)", this.start.getSingle(event), "(and|to)", this.end.getSingle(event));
		} else {
			builder.append("in", this.start.getSingle(event));
		}
		return builder.toString();
	}
}
