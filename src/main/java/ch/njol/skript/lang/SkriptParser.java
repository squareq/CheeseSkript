package ch.njol.skript.lang;

import ch.njol.skript.Skript;
import ch.njol.skript.SkriptAPIException;
import ch.njol.skript.SkriptConfig;
import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.Parser;
import ch.njol.skript.command.Argument;
import ch.njol.skript.command.Commands;
import ch.njol.skript.command.ScriptCommand;
import ch.njol.skript.command.ScriptCommandEvent;
import ch.njol.skript.expressions.ExprParse;
import ch.njol.skript.lang.DefaultExpressionUtils.DefaultExpressionError;
import ch.njol.skript.lang.function.ExprFunctionCall;
import ch.njol.skript.lang.function.FunctionReference;
import ch.njol.skript.lang.function.FunctionRegistry;
import ch.njol.skript.lang.function.Functions;
import ch.njol.skript.lang.function.Signature;
import ch.njol.skript.lang.parser.DefaultValueData;
import ch.njol.skript.lang.parser.ParseStackOverflowException;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.lang.parser.ParsingStack;
import ch.njol.skript.lang.simplification.Simplifiable;
import ch.njol.skript.lang.util.SimpleLiteral;
import ch.njol.skript.localization.Language;
import ch.njol.skript.localization.Message;
import ch.njol.skript.localization.Noun;
import ch.njol.skript.log.ErrorQuality;
import ch.njol.skript.log.LogEntry;
import ch.njol.skript.log.ParseLogHandler;
import ch.njol.skript.log.SkriptLogger;
import ch.njol.skript.patterns.MalformedPatternException;
import ch.njol.skript.patterns.PatternCompiler;
import ch.njol.skript.patterns.SkriptPattern;
import ch.njol.skript.patterns.TypePatternElement;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.util.Utils;
import ch.njol.util.Kleenean;
import ch.njol.util.NonNullPair;
import ch.njol.util.StringUtils;
import ch.njol.util.coll.CollectionUtils;
import ch.njol.util.coll.iterator.CheckedIterator;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Booleans;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.skriptlang.skript.lang.converter.Converters;
import org.skriptlang.skript.lang.experiment.ExperimentSet;
import org.skriptlang.skript.lang.experiment.ExperimentalSyntax;
import org.skriptlang.skript.lang.script.Script;
import org.skriptlang.skript.lang.script.ScriptWarning;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Used for parsing my custom patterns.<br>
 * <br>
 * Note: All parse methods print one error at most xor any amount of warnings and lower level log messages. If the given string doesn't match any pattern then nothing is printed.
 *
 * @author Peter Güttinger
 */
public final class SkriptParser {

	private final String expr;

	public final static int PARSE_EXPRESSIONS = 1;
	public final static int PARSE_LITERALS = 2;
	public final static int ALL_FLAGS = PARSE_EXPRESSIONS | PARSE_LITERALS;
	private final int flags;

	public final boolean doSimplification = SkriptConfig.simplifySyntaxesOnParse.value();

	public final ParseContext context;

	public SkriptParser(String expr) {
		this(expr, ALL_FLAGS);
	}

	public SkriptParser(String expr, int flags) {
		this(expr, flags, ParseContext.DEFAULT);
	}

	/**
	 * Constructs a new SkriptParser object that can be used to parse the given expression.
	 * <p>
	 * A SkriptParser can be re-used indefinitely for the given expression, but to parse a new expression a new SkriptParser has to be created.
	 *
	 * @param expr The expression to parse
	 * @param flags Some parse flags ({@link #PARSE_EXPRESSIONS}, {@link #PARSE_LITERALS})
	 * @param context The parse context
	 */
	public SkriptParser(String expr, int flags, ParseContext context) {
		assert expr != null;
		assert (flags & ALL_FLAGS) != 0;
		this.expr = "" + expr.trim();
		this.flags = flags;
		this.context = context;
	}

	public SkriptParser(SkriptParser other, String expr) {
		this(expr, other.flags, other.context);
	}

	public static final String WILDCARD = "[^\"]*?(?:\"[^\"]*?\"[^\"]*?)*?";

	public static class ParseResult {
		public @Nullable SkriptPattern source;
		public Expression<?>[] exprs;
		public List<MatchResult> regexes = new ArrayList<>(1);
		public String expr;
		/**
		 * Defaults to 0. Any marks encountered in the pattern will be XORed with the existing value, in particular if only one mark is encountered this value will be set to that
		 * mark.
		 */
		public int mark = 0;
		public List<String> tags = new ArrayList<>();

		public ParseResult(SkriptParser parser, String pattern) {
			expr = parser.expr;
			exprs = new Expression<?>[countUnescaped(pattern, '%') / 2];
		}

		public ParseResult(String expr, Expression<?>[] expressions) {
			this.expr = expr;
			this.exprs = expressions;
		}

		public boolean hasTag(String tag) {
			return tags.contains(tag);
		}
	}

	/**
	 * Parses a single literal, i.e. not lists of literals.
	 * <p>
	 * Prints errors.
	 */
	public static <T> @Nullable Literal<? extends T> parseLiteral(String expr, Class<T> expectedClass, ParseContext context) {
		expr = "" + expr.trim();
		if (expr.isEmpty())
			return null;
		//noinspection ReassignedVariable,unchecked
		return new UnparsedLiteral(expr).getConvertedExpression(context, expectedClass);
	}

	/**
	 * Parses a string as one of the given syntax elements.
	 * <p>
	 * Can print an error.
	 */
	public static <T extends SyntaxElement> @Nullable T parse(String expr, Iterator<? extends SyntaxInfo<T>> source, @Nullable String defaultError) {
		expr = "" + expr.trim();
		if (expr.isEmpty()) {
			Skript.error(defaultError);
			return null;
		}
		try (ParseLogHandler log = SkriptLogger.startParseLogHandler()) {
			T element = new SkriptParser(expr).parse(source);
			if (element != null) {
				log.printLog();
				return element;
			}
			log.printError(defaultError);
			return null;
		}
	}

	public static <T extends SyntaxElement> @Nullable T parseStatic(String expr, Iterator<? extends SyntaxInfo<? extends T>> source, @Nullable String defaultError) {
		return parseStatic(expr, source, ParseContext.DEFAULT, defaultError);
	}

	public static <T extends SyntaxElement> @Nullable T parseStatic(String expr, Iterator<? extends SyntaxInfo<? extends T>> source, ParseContext parseContext, @Nullable String defaultError) {
		expr = expr.trim();
		if (expr.isEmpty()) {
			Skript.error(defaultError);
			return null;
		}

		T element;
		try (ParseLogHandler log = SkriptLogger.startParseLogHandler()) {
			element = new SkriptParser(expr, PARSE_LITERALS, parseContext).parse(source);
			if (element != null) {
				log.printLog();
				return element;
			}
			log.printError(defaultError);
			return null;
		}
	}

	private <T extends SyntaxElement> @Nullable T parse(Iterator<? extends SyntaxInfo<? extends T>> source) {
		ParsingStack parsingStack = getParser().getParsingStack();
		try (ParseLogHandler log = SkriptLogger.startParseLogHandler()) {
			while (source.hasNext()) {
				SyntaxInfo<? extends T> info = source.next();
				int matchedPattern = -1; // will increment at the start of each iteration
				patternsLoop: for (String pattern : info.patterns()) {
					matchedPattern++;
					log.clear();
					ParseResult parseResult;

					try {
						parsingStack.push(new ParsingStack.Element(info, matchedPattern));
						parseResult = parse_i(pattern);
					} catch (MalformedPatternException e) {
						String message = "pattern compiling exception, element class: " + info.type().getName();
						try {
							JavaPlugin providingPlugin = JavaPlugin.getProvidingPlugin(info.type());
							message += " (provided by " + providingPlugin.getName() + ")";
						} catch (IllegalArgumentException | IllegalStateException ignored) { }
						throw new RuntimeException(message, e);
					} catch (StackOverflowError e) {
						// Parsing caused a stack overflow, possibly due to too long lines
						throw new ParseStackOverflowException(e, new ParsingStack(parsingStack));
					} finally {
						// Recursive parsing call done, pop the element from the parsing stack
						ParsingStack.Element stackElement = parsingStack.pop();
						assert stackElement.syntaxElementInfo() == info && stackElement.patternIndex() == matchedPattern;
					}

					if (parseResult == null)
						continue;

					assert parseResult.source != null; // parse results from parse_i have a source
					List<TypePatternElement> types = null;
					for (int i = 0; i < parseResult.exprs.length; i++) {
						if (parseResult.exprs[i] == null) {
							if (types == null)
								types = parseResult.source.getElements(TypePatternElement.class);;
							ExprInfo exprInfo = types.get(i).getExprInfo();
							if (!exprInfo.isOptional) {
								List<DefaultExpression<?>> exprs = getDefaultExpressions(exprInfo, pattern);
								DefaultExpression<?> matchedExpr = null;
								for (DefaultExpression<?> expr : exprs) {
									if (expr.init()) {
										matchedExpr = expr;
										break;
									}
								}
								if (matchedExpr == null)
									continue patternsLoop;
								parseResult.exprs[i] = matchedExpr;
							}
						}
					}
					T element = info.instance();

					if (!checkRestrictedEvents(element, parseResult))
						continue;

					if (!checkExperimentalSyntax(element))
						continue;

					boolean success = element.preInit() && element.init(parseResult.exprs, matchedPattern, getParser().getHasDelayBefore(), parseResult);
					if (success) {
						// Check if any expressions are 'UnparsedLiterals' and if applicable for multiple info warning.
						for (Expression<?> expr : parseResult.exprs) {
							if (expr instanceof UnparsedLiteral unparsedLiteral && unparsedLiteral.multipleWarning())
								break;
						}
						log.printLog();
						if (doSimplification && element instanceof Simplifiable<?> simplifiable)
							//noinspection unchecked
							return (T) simplifiable.simplify();
						return element;
					}
				}
			}

			// No successful syntax elements parsed, print errors and return
			log.printError();
			return null;
		}
	}

	/**
	 * Checks whether the given element is restricted to specific events, and if so, whether the current event is allowed.
	 * Prints errors.
	 * @param element The syntax element to check.
	 * @param parseResult The parse result for error information.
	 * @return True if the element is allowed in the current event, false otherwise.
	 */
	private static boolean checkRestrictedEvents(SyntaxElement element, ParseResult parseResult) {
		if (element instanceof EventRestrictedSyntax eventRestrictedSyntax) {
			Class<? extends Event>[] supportedEvents = eventRestrictedSyntax.supportedEvents();
			if (!getParser().isCurrentEvent(supportedEvents)) {
				Skript.error("'" + parseResult.expr + "' can only be used in " + supportedEventsNames(supportedEvents));
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns a string with the names of the supported skript events for the given class array.
	 * If no events are found, returns an empty string.
	 * @param supportedEvents The array of supported event classes.
	 * @return A string with the names of the supported skript events, or an empty string if none are found.
	 */
	private static @NotNull String supportedEventsNames(Class<? extends Event>[] supportedEvents) {
		List<String> names = new ArrayList<>();

		for (SkriptEventInfo<?> eventInfo : Skript.getEvents()) {
			for (Class<? extends Event> eventClass : supportedEvents) {
				for (Class<? extends Event> event : eventInfo.events) {
					if (event.isAssignableFrom(eventClass)) {
						names.add("the %s event".formatted(eventInfo.getName().toLowerCase()));
					}
				}
			}
		}

		return StringUtils.join(names, ", ", " or ");
	}

	/**
	 * Checks that {@code element} is an {@link ExperimentalSyntax} and, if so, ensures that its requirements are satisfied by the current {@link ExperimentSet}.
	 * @param element The {@link SyntaxElement} to check.
	 * @return {@code True} if the {@link SyntaxElement} is not an {@link ExperimentalSyntax} or is satisfied.
	 */
	private static <T extends SyntaxElement> boolean checkExperimentalSyntax(T element) {
		if (!(element instanceof ExperimentalSyntax experimentalSyntax))
			return true;
		ExperimentSet experiments = getParser().getExperimentSet();
		return experimentalSyntax.isSatisfiedBy(experiments);
	}

	/**
	 * Returns the {@link DefaultExpression} from the first {@link ClassInfo} stored in {@code exprInfo}.
	 *
	 * @param exprInfo The {@link ExprInfo} to check for {@link DefaultExpression}.
	 * @param pattern The pattern used to create {@link ExprInfo}.
	 * @return {@link DefaultExpression}.
	 * @throws SkriptAPIException If the {@link DefaultExpression} is not valid, produces an error message for the reasoning of failure.
	 */
	private static @NotNull DefaultExpression<?> getDefaultExpression(ExprInfo exprInfo, String pattern) {
		DefaultValueData data = getParser().getData(DefaultValueData.class);
		ClassInfo<?> classInfo = exprInfo.classes[0];
		DefaultExpression<?> expr = data.getDefaultValue(classInfo.getC());
		if (expr == null)
			expr = classInfo.getDefaultExpression();

		DefaultExpressionError errorType = DefaultExpressionUtils.isValid(expr, exprInfo, 0);
		if (errorType == null) {
			assert expr != null;
			return expr;
		}

		throw new SkriptAPIException(errorType.getError(List.of(classInfo.getCodeName()), pattern));
	}

	/**
	 * Returns all {@link DefaultExpression}s from all the {@link ClassInfo}s embedded in {@code exprInfo} that are valid.
	 *
	 * @param exprInfo The {@link ExprInfo} to check for {@link DefaultExpression}s.
	 * @param pattern The pattern used to create {@link ExprInfo}.
	 * @return All available {@link DefaultExpression}s.
	 * @throws SkriptAPIException If no {@link DefaultExpression}s are valid, produces an error message for the reasoning of failure.
	 */
	static @NotNull List<DefaultExpression<?>> getDefaultExpressions(ExprInfo exprInfo, String pattern) {
		if (exprInfo.classes.length == 1)
			return new ArrayList<>(List.of(getDefaultExpression(exprInfo, pattern)));

		DefaultValueData data = getParser().getData(DefaultValueData.class);

		EnumMap<DefaultExpressionError, List<String>> failed = new EnumMap<>(DefaultExpressionError.class);
		List<DefaultExpression<?>> passed = new ArrayList<>();
		for (int i = 0; i < exprInfo.classes.length; i++) {
			ClassInfo<?> classInfo = exprInfo.classes[i];
			DefaultExpression<?> expr = data.getDefaultValue(classInfo.getC());
			if (expr == null)
				expr = classInfo.getDefaultExpression();

			String codeName = classInfo.getCodeName();
			DefaultExpressionError errorType = DefaultExpressionUtils.isValid(expr, exprInfo, i);

			if (errorType != null) {
				failed.computeIfAbsent(errorType, list -> new ArrayList<>()).add(codeName);
			} else {
				passed.add(expr);
			}
		}

		if (!passed.isEmpty())
			return passed;

		List<String> errors = new ArrayList<>();
		for (Entry<DefaultExpressionError, List<String>> entry : failed.entrySet()) {
			String error = entry.getKey().getError(entry.getValue(), pattern);
			errors.add(error);
		}
		throw new SkriptAPIException(StringUtils.join(errors, "\n"));
	}

	private static final Pattern VARIABLE_PATTERN = Pattern.compile("((the )?var(iable)? )?\\{.+\\}", Pattern.CASE_INSENSITIVE);

	/**
	 * Prints errors
	 */
	private static <T> @Nullable Variable<T> parseVariable(String expr, Class<? extends T>[] returnTypes) {
		if (VARIABLE_PATTERN.matcher(expr).matches()) {
			String variableName = "" + expr.substring(expr.indexOf('{') + 1, expr.lastIndexOf('}'));
			boolean inExpression = false;
			int variableDepth = 0;
			for (char character : variableName.toCharArray()) {
				if (character == '%' && variableDepth == 0)
					inExpression = !inExpression;
				if (inExpression) {
					if (character == '{') {
						variableDepth++;
					} else if (character == '}')
						variableDepth--;
				}

				if (!inExpression && (character == '{' || character == '}'))
					return null;
			}
			return Variable.newInstance(variableName, returnTypes);
		}
		return null;
	}

	private static @Nullable Expression<?> parseExpression(Class<?>[] types, String expr) {;
		if (expr.startsWith("“") || expr.startsWith("”") || expr.endsWith("”") || expr.endsWith("“")) {
			Skript.error("Pretty quotes are not allowed, change to regular quotes (\")");
			return null;
		}
		if (expr.startsWith("\"") && expr.length() != 1 && nextQuote(expr, 1) == expr.length() - 1) {
			return VariableString.newInstance("" + expr.substring(1, expr.length() - 1));
		} else {
			var iterator = new CheckedIterator<>(Skript.instance().syntaxRegistry().syntaxes(SyntaxRegistry.EXPRESSION).iterator(), info -> {
				if (info == null || info.returnType() == Object.class)
					return true;
				for (Class<?> returnType : types) {
					assert returnType != null;
					if (Converters.converterExists(info.returnType(), returnType))
						return true;
				}
				return false;
			});
			//noinspection unchecked,rawtypes
			return (Expression<?>) parse(expr, (Iterator) iterator, null);
		}
	}


	@SuppressWarnings({"unchecked"})
	private <T> @Nullable Expression<? extends T> parseSingleExpr(boolean allowUnparsedLiteral, @Nullable LogEntry error, Class<? extends T>... types) {
		assert types.length > 0;
		assert types.length == 1 || !CollectionUtils.contains(types, Object.class);
		if (expr.isEmpty())
			return null;
		if (context != ParseContext.COMMAND &&
					context != ParseContext.PARSE &&
					expr.startsWith("(") && expr.endsWith(")") &&
					next(expr, 0, context) == expr.length())
			return new SkriptParser(this, "" + expr.substring(1, expr.length() - 1)).parseSingleExpr(allowUnparsedLiteral, error, types);
		try (ParseLogHandler log = SkriptLogger.startParseLogHandler()) {
			if (context == ParseContext.DEFAULT || context == ParseContext.EVENT) {
				Variable<? extends T> parsedVariable = parseVariable(expr, types);
				if (parsedVariable != null) {
					if ((flags & PARSE_EXPRESSIONS) == 0) {
						Skript.error("Variables cannot be used here.");
						log.printError();
						return null;
					}
					log.printLog();
					return parsedVariable;
				} else if (log.hasError()) {
					log.printError();
					return null;
				}
				FunctionReference<T> functionReference = parseFunction(types);
				if (functionReference != null) {
					log.printLog();
					//noinspection rawtypes
					return new ExprFunctionCall(functionReference);
				} else if (log.hasError()) {
					log.printError();
					return null;
				}
			}
			log.clear();
			if ((flags & PARSE_EXPRESSIONS) != 0) {
				Expression<?> parsedExpression = parseExpression(types, expr);
				if (parsedExpression != null) { // Expression/VariableString parsing success
					Class<?> parsedReturnType = parsedExpression.getReturnType();
					for (Class<? extends T> type : types) {
						if (type.isAssignableFrom(parsedReturnType)) {
							log.printLog();
							return (Expression<? extends T>) parsedExpression;
						}
					}

					// No directly same type found
					Class<T>[] objTypes = (Class<T>[]) types; // Java generics... ?
					Expression<? extends T> convertedExpression = parsedExpression.getConvertedExpression(objTypes);
					if (convertedExpression != null) {
						log.printLog();
						return convertedExpression;
					}
					// Print errors, if we couldn't get the correct type
					log.printError(parsedExpression.toString(null, false) + " " + Language.get("is") + " " + notOfType(types), ErrorQuality.NOT_AN_EXPRESSION);
					return null;
				}
				log.clear();
			}
			if ((flags & PARSE_LITERALS) == 0) {
				log.printError();
				return null;
			}
			return parseAsLiteral(allowUnparsedLiteral, log, error, types);
		}
	}

	private static final String INVALID_LSPEC_CHARS = "[^,():/\"'\\[\\]}{]";
	private static final Pattern LITERAL_SPECIFICATION_PATTERN = Pattern.compile("(?<literal>" + INVALID_LSPEC_CHARS + "+) \\((?<classinfo>[\\w\\p{L} ]+)\\)");

	private @Nullable Expression<?> parseSingleExpr(boolean allowUnparsedLiteral, @Nullable LogEntry error, ExprInfo exprInfo) {
		if (expr.isEmpty()) // Empty expressions return nothing, obviously
			return null;

		// Command special parsing
		if (context != ParseContext.COMMAND &&
					context != ParseContext.PARSE &&
					expr.startsWith("(") && expr.endsWith(")") &&
					next(expr, 0, context) == expr.length())
			return new SkriptParser(this, "" + expr.substring(1, expr.length() - 1)).parseSingleExpr(allowUnparsedLiteral, error, exprInfo);
		try (ParseLogHandler log = SkriptLogger.startParseLogHandler()) {
			// Construct types array which contains all potential classes
			Class<?>[] types = new Class[exprInfo.classes.length]; // This may contain nulls!
			boolean hasSingular = false;
			boolean hasPlural = false;

			// Another array for all potential types, but this time without any nulls
			// (indexes do not align with other data in ExprInfo)
			Class<?>[] nonNullTypes = new Class[exprInfo.classes.length];

			int nonNullIndex = 0;
			for (int i = 0; i < types.length; i++) {
				if ((flags & exprInfo.flagMask) == 0) { // Flag mask invalidates this, skip it
					continue;
				}

				// Plural/singular checks
				// TODO move them elsewhere, this method needs to be as fast as possible
				if (exprInfo.isPlural[i])
					hasPlural = true;
				else
					hasSingular = true;

				// Actually put class to types[i]
				types[i] = exprInfo.classes[i].getC();

				// Handle nonNullTypes data fill
				nonNullTypes[nonNullIndex] = types[i];
				nonNullIndex++;
			}

			boolean onlyPlural = !hasSingular && hasPlural;
			boolean onlySingular = hasSingular && !hasPlural;

			if (context == ParseContext.DEFAULT || context == ParseContext.EVENT) {
				// Attempt to parse variable first
				if (onlySingular || onlyPlural) { // No mixed plurals/singulars possible
					Variable<?> parsedVariable = parseVariable(expr, nonNullTypes);
					if (parsedVariable != null) { // Parsing succeeded, we have a variable
						// If variables cannot be used here, it is now allowed
						if ((flags & PARSE_EXPRESSIONS) == 0) {
							Skript.error("Variables cannot be used here.");
							log.printError();
							return null;
						}

						// Plural/singular sanity check
						if (hasSingular && !parsedVariable.isSingle()) {
							Skript.error("'" + expr + "' can only be a single "
								+ Classes.toString(Stream.of(exprInfo.classes).map(classInfo -> classInfo.getName().toString()).toArray(), false)
								+ ", not more.");
							log.printError();
							return null;
						}

						log.printLog();
						return parsedVariable;
					} else if (log.hasError()) {
						log.printError();
						return null;
					}
				} else { // Mixed plurals/singulars
					Variable<?> parsedVariable = parseVariable(expr, types);
					if (parsedVariable != null) { // Parsing succeeded, we have a variable
						// If variables cannot be used here, it is now allowed
						if ((flags & PARSE_EXPRESSIONS) == 0) {
							Skript.error("Variables cannot be used here.");
							log.printError();
							return null;
						}

						// Plural/singular sanity check
						//
						// It's (currently?) not possible to detect this at parse time when there are multiple
						// acceptable types and only some of them are single, since variables, global especially,
						// can hold any possible type, and the type used can only be 100% known at runtime
						//
						// TODO:
						// despite of that, we should probably implement a runtime check for this somewhere
						// before executing the syntax element (perhaps even exceptionally with a console warning,
						// otherwise users may have some hard time debugging the plurality issues) - currently an
						// improper use in a script would result in an exception
						if (((exprInfo.classes.length == 1 && !exprInfo.isPlural[0]) || Booleans.contains(exprInfo.isPlural, true))
								&& !parsedVariable.isSingle()) {
							Skript.error("'" + expr + "' can only be a single "
									+ Classes.toString(Stream.of(exprInfo.classes).map(classInfo -> classInfo.getName().toString()).toArray(), false)
									+ ", not more.");
							log.printError();
							return null;
						}

						log.printLog();
						return parsedVariable;
					} else if (log.hasError()) {
						log.printError();
						return null;
					}
				}

				// If it wasn't variable, do same for function call
				FunctionReference<?> functionReference = parseFunction(types);
				if (functionReference != null) {

					if (onlySingular && !functionReference.isSingle()) {
						Skript.error("'" + expr + "' can only be a single "
							+ Classes.toString(Stream.of(exprInfo.classes).map(classInfo -> classInfo.getName().toString()).toArray(), false)
							+ ", not more.");
						log.printError();
						return null;
					}

					log.printLog();
					return new ExprFunctionCall<>(functionReference);
				} else if (log.hasError()) {
					log.printError();
					return null;
				}
			}
			log.clear();
			if ((flags & PARSE_EXPRESSIONS) != 0) {
				Expression<?> parsedExpression = parseExpression(types, expr);
				if (parsedExpression != null) { // Expression/VariableString parsing success
					Class<?> parsedReturnType = parsedExpression.getReturnType();
					for (int i = 0; i < types.length; i++) {
						Class<?> type = types[i];
						if (type == null) // Ignore invalid (null) types
							continue;

						// Check return type against the expression's return type
						if (type.isAssignableFrom(parsedReturnType)) {
							if (!exprInfo.isPlural[i] && !parsedExpression.isSingle()) { // Wrong number of arguments
								if (context == ParseContext.COMMAND) {
									Skript.error(Commands.m_too_many_arguments.toString(exprInfo.classes[i].getName().getIndefiniteArticle(), exprInfo.classes[i].getName().toString()), ErrorQuality.SEMANTIC_ERROR);
								} else {
									Skript.error("'" + expr + "' can only be a single "
										+ Classes.toString(Stream.of(exprInfo.classes).map(classInfo -> classInfo.getName().toString()).toArray(), false)
										+ ", not more.");
								}
								log.printError();
								return null;
							}

							log.printLog();
							return parsedExpression;
						}
					}

					if (onlySingular && !parsedExpression.isSingle()) {
						Skript.error("'" + expr + "' can only be a single "
							+ Classes.toString(Stream.of(exprInfo.classes).map(classInfo -> classInfo.getName().toString()).toArray(), false)
							+ ", not more.");
						log.printError();
						return null;
					}

					// No directly same type found
					Expression<?> convertedExpression = parsedExpression.getConvertedExpression((Class<Object>[]) types);
					if (convertedExpression != null) {
						log.printLog();
						return convertedExpression;
					}

					// Print errors, if we couldn't get the correct type
					log.printError(parsedExpression.toString(null, false) + " " + Language.get("is") + " " + notOfType(types), ErrorQuality.NOT_AN_EXPRESSION);
					return null;
				}
				log.clear();
			}
			if ((flags & PARSE_LITERALS) == 0) {
				log.printError();
				return null;
			}
			return parseAsLiteral(allowUnparsedLiteral, log, error, nonNullTypes);
		}
	}

	/**
	 * Helper method for {@link #parseSingleExpr(boolean, LogEntry, Class[])} and {@link #parseSingleExpr(boolean, LogEntry, ExprInfo)}.
	 * Attempts to parse {@link #expr} as a literal. Prints errors.
	 *
	 * @param allowUnparsedLiteral If {@code true}, will allow unparsed literals to be returned.
	 * @param log The current {@link ParseLogHandler} to use for logging.
	 * @param error A {@link LogEntry} containing a default error to be printed if failed to parse.
	 * @param types The valid types to parse the literal as.
	 * @return {@link Expression} of type {@code T} if successful, otherwise {@code null}.<br>
	 * @param <T> The type of the literal to parse.<br>
	 */
	@SafeVarargs
	private <T> @Nullable Expression<? extends T> parseAsLiteral(
			boolean allowUnparsedLiteral,
			ParseLogHandler log,
			@Nullable LogEntry error,
			Class<? extends T>... types
	) {
		if (expr.endsWith(")") && expr.contains("(")) {
			Matcher classInfoMatcher = LITERAL_SPECIFICATION_PATTERN.matcher(expr);
			if (classInfoMatcher.matches()) {
				String literalString = classInfoMatcher.group("literal");
				String unparsedClassInfo = Noun.stripDefiniteArticle(classInfoMatcher.group("classinfo"));
				Expression<? extends T> result = parseSpecifiedLiteral(literalString, unparsedClassInfo, types);
				if (result != null) {
					log.printLog();
					return result;
				}
			}
		}
		if (types.length == 1 && types[0] == Object.class) {
			if (!allowUnparsedLiteral) {
				log.printError();
				return null;
			}
			//noinspection unchecked
			return (Expression<? extends T>) getUnparsedLiteral(log, error);
		}
		boolean containsObjectClass = false;
		for (Class<?> type : types) {
			log.clear();
			if (type == Object.class) {
				// If 'Object.class' is an option, needs to be treated as previous behavior
				// But we also want to be sure every other 'ClassInfo' is attempted to be parsed beforehand
				containsObjectClass = true;
				continue;
			}
			//noinspection unchecked
			T parsedObject = (T) Classes.parse(expr, type, context);
			if (parsedObject != null) {
				log.printLog();
				return new SimpleLiteral<>(parsedObject, false, new UnparsedLiteral(expr));
			}
		}
		if (allowUnparsedLiteral && containsObjectClass)
			//noinspection unchecked
			return (Expression<? extends T>) getUnparsedLiteral(log, error);
		if (expr.startsWith("\"") && expr.endsWith("\"") && expr.length() > 1) {
			for (Class<?> type : types) {
				if (!type.isAssignableFrom(String.class))
					continue;
				VariableString string = VariableString.newInstance(expr.substring(1, expr.length() - 1));
				if (string instanceof LiteralString)
					//noinspection unchecked
					return (Expression<? extends T>) string;
				break;
			}
		}
		log.printError();
		return null;
	}

	/**
	 * If {@link #expr} is a valid literal expression, will return {@link UnparsedLiteral}.
	 * @param log The current {@link ParseLogHandler}.
	 * @param error A {@link LogEntry} containing a default error to be printed if failed to retrieve.
	 * @return {@link UnparsedLiteral} or {@code null}.
	 */
	private @Nullable UnparsedLiteral getUnparsedLiteral(
		ParseLogHandler log,
		@Nullable LogEntry error
	)  {
		// Do check if a literal with this name actually exists before returning an UnparsedLiteral
		if (Classes.parseSimple(expr, Object.class, context) == null) {
			log.printError();
			return null;
		}
		log.clear();
		LogEntry logError = log.getError();
		return new UnparsedLiteral(expr, logError != null && (error == null || logError.quality > error.quality) ? logError : error);
	}

	/**
	 * <p>
	 *     With ambiguous literals being used in multiple {@link ClassInfo}s, users can specify which one they want
	 *     in the format of 'literal (classinfo)'; Example: black (wolf variant)
	 *     This checks to ensure the given 'classinfo' exists, is parseable, and is of the accepted types that is required.
	 *     If so, the literal section of the input is parsed as the given classinfo and the result returned.
	 * </p>
	 * @param literalString A {@link String} representing a literal
	 * @param unparsedClassInfo A {@link String} representing a class info
	 * @param types An {@link Array} of the acceptable {@link Class}es
	 * @return {@link SimpleLiteral} or {@code null} if any checks fail
	 */
	@SafeVarargs
	private <T> @Nullable Expression<? extends T> parseSpecifiedLiteral(
		String literalString,
		String unparsedClassInfo,
		Class<? extends T> ... types
	) {
		ClassInfo<?> classInfo = Classes.parse(unparsedClassInfo, ClassInfo.class, context);
		if (classInfo == null) {
			Skript.error("A " + unparsedClassInfo  + " is not a valid type.");
			return null;
		}
		Parser<?> classInfoParser = classInfo.getParser();
		if (classInfoParser == null || !classInfoParser.canParse(context)) {
			Skript.error("A " + unparsedClassInfo  + " cannot be parsed.");
			return null;
		}
		if (!checkAcceptedType(classInfo.getC(), types)) {
			Skript.error(expr + " " + Language.get("is") + " " + notOfType(types));
			return null;
		}
		//noinspection unchecked
		T parsedObject = (T) classInfoParser.parse(literalString, context);
		if (parsedObject != null)
			return new SimpleLiteral<>(parsedObject, false, new UnparsedLiteral(literalString));
		return null;
	}

	/**
	 * Check if the provided {@code clazz} is an accepted type from any class of {@code types}.
	 * @param clazz The {@link Class} to check
	 * @param types The {@link Class}es that are accepted
	 * @return true if {@code clazz} is of a {@link Class} from {@code types}
	 */
	private boolean checkAcceptedType(Class<?> clazz, Class<?> ... types) {
		for (Class<?> targetType : types) {
			if (targetType.isAssignableFrom(clazz))
				return true;
		}
		return false;
	}

	/**
	 * Matches ',', 'and', 'or', etc. as well as surrounding whitespace.
	 * <p>
	 * group 1 is null for ',', otherwise it's one of and/or/nor (not necessarily lowercase).
	 */
	public static final Pattern LIST_SPLIT_PATTERN = Pattern.compile("\\s*,?\\s+(and|n?or)\\s+|\\s*,\\s*", Pattern.CASE_INSENSITIVE);
	public static final Pattern OR_PATTERN = Pattern.compile("\\sor\\s", Pattern.CASE_INSENSITIVE);

	private final static String MULTIPLE_AND_OR = "List has multiple 'and' or 'or', will default to 'and'. Use brackets if you want to define multiple lists.";
	private final static String MISSING_AND_OR = "List is missing 'and' or 'or', defaulting to 'and'";

	private boolean suppressMissingAndOrWarnings = SkriptConfig.disableMissingAndOrWarnings.value();

	private SkriptParser suppressMissingAndOrWarnings() {
		suppressMissingAndOrWarnings = true;
		return this;
	}

	@SafeVarargs
	public final <T> @Nullable Expression<? extends T> parseExpression(Class<? extends T>... types) {
		if (expr.isEmpty()) {
			return null;
		}

		assert types.length > 0;
		assert types.length == 1 || !CollectionUtils.contains(types, Object.class);

		try (ParseLogHandler log = SkriptLogger.startParseLogHandler()) {
			Expression<? extends T> parsedExpression = parseSingleExpr(true, null, types);
			if (parsedExpression != null) {
				log.printLog();
				return parsedExpression;
			}
			log.clear();

			return parseExpressionList(log, types);
		}
	}

	public @Nullable Expression<?> parseExpression(ExprInfo exprInfo) {
		if (expr.isEmpty()) {
			return null;
		}

		try (ParseLogHandler log = SkriptLogger.startParseLogHandler()) {
			Expression<?> parsedExpression = parseSingleExpr(true, null, exprInfo);
			if (parsedExpression != null) {
				log.printLog();
				return parsedExpression;
			}
			log.clear();

			return parseExpressionList(log, exprInfo);
		}
	}

	/*
	 * List parsing
	 */

	private record OrderedExprInfo(ExprInfo[] infos) { }

	@SafeVarargs
	private <T> @Nullable Expression<? extends T> parseExpressionList(ParseLogHandler log, Class<? extends T>... types) {
		//noinspection unchecked
		return (Expression<? extends T>) parseExpressionList_i(log, types);
	}

	private @Nullable Expression<?> parseExpressionList(ParseLogHandler log, ExprInfo info) {
		return parseExpressionList_i(log, info);
	}

	private @Nullable Expression<?> parseExpressionList(ParseLogHandler log, OrderedExprInfo info) {
		return parseExpressionList_i(log, info);
	}

	private @Nullable Expression<?> parseExpressionList_i(ParseLogHandler log, Object data) {
		OrderedExprInfo orderedExprInfo = data instanceof OrderedExprInfo info ? info : null;
		ExprInfo exprInfo = data instanceof ExprInfo info ? info : null;
		Class<?>[] types = orderedExprInfo == null && exprInfo == null ? (Class<?>[]) data : null;
		boolean isObject;
		if (orderedExprInfo != null) {
			isObject = orderedExprInfo.infos.length == 1 && orderedExprInfo.infos[0].classes[0].getC() == Object.class;
		} else if (exprInfo != null) {
			isObject = exprInfo.classes.length == 1 && exprInfo.classes[0].getC() == Object.class;
		} else {
			isObject = types.length == 1 && types[0] == Object.class;
		}

		List<int[]> pieces = new ArrayList<>();
		Matcher matcher = LIST_SPLIT_PATTERN.matcher(expr);
		int currentPosition = 0;
		int lastPosition = currentPosition;
		while (currentPosition >= 0 && currentPosition <= expr.length()) {
			if (currentPosition == expr.length() || matcher.region(currentPosition, expr.length()).lookingAt()) {
				pieces.add(new int[]{lastPosition, currentPosition});
				if (currentPosition == expr.length()) {
					break;
				}
				currentPosition = matcher.end();
				lastPosition = currentPosition;
			}
			currentPosition = next(expr, currentPosition, context);
		}
		if (currentPosition != expr.length()) {
			assert currentPosition == -1 && context != ParseContext.COMMAND && context != ParseContext.PARSE : currentPosition + "; " + expr;
			log.printError("Invalid brackets/variables/text in '" + expr + "'", ErrorQuality.NOT_AN_EXPRESSION);
			return null;
		}

		if (pieces.size() == 1) { // not a list of expressions, and a single one has failed to parse above
			if (expr.startsWith("(") && expr.endsWith(")") && next(expr, 0, context) == expr.length()) {
				log.clear();
				// parse again without parentheses
				SkriptParser parser = new SkriptParser(this, expr.substring(1, expr.length() - 1));
				if (exprInfo != null) {
					return parser.parseExpression(exprInfo);
				} else {
					return parser.parseExpression(types);
				}
			}
			if (isObject && (flags & PARSE_LITERALS) != 0) { // single expression, can return an UnparsedLiteral now
				log.clear();
				return new UnparsedLiteral(expr, log.getError());
			}
			log.printError();
			return null;
		}

		// early check whether this can be parsed as an 'or' list
		// if it cannot, and the output is expected to be single, we can return early
		if (exprInfo != null && !Booleans.contains(exprInfo.isPlural, true) && !OR_PATTERN.matcher(expr).find()) {
			log.printError();
			return null;
		}

		List<Expression<?>> parsedExpressions = new ArrayList<>();
		boolean isLiteralList = true;
		Kleenean and = Kleenean.UNKNOWN;
		// given "a, b, c" try "a, ab, ac" when starting with "a"
		outer: for (int first = 0; first < pieces.size(); ) {
			for (int last = first; last < pieces.size(); last++) {
				if (first == 0 && last == pieces.size() - 1) { // this is the whole expression, which would have already been tried
					continue;
				}

				int start = pieces.get(first)[0];
				int end = pieces.get(last)[1];
				String subExpr = expr.substring(start, end);

				// allow parsing as a list only if subExpr is wrapped with parentheses
				SkriptParser parser = new SkriptParser(this, subExpr);
				Expression<?> parsedExpression;
				if (subExpr.startsWith("(") && subExpr.endsWith(")") && next(subExpr, 0, context) == subExpr.length()) {
					if (orderedExprInfo != null) {
						int infoIndex = parsedExpressions.size();
						if (infoIndex >= orderedExprInfo.infos.length) {
							log.printError();
							return null;
						}
						parsedExpression = parser.parseExpression(orderedExprInfo.infos[infoIndex]);
					} else if (exprInfo != null) {
						parsedExpression = parser.parseExpression(exprInfo);
					} else {
						parsedExpression = parser.parseExpression(types);
					}
				} else {
					if (orderedExprInfo != null) {
						int infoIndex = parsedExpressions.size();
						if (infoIndex >= orderedExprInfo.infos.length) {
							log.printError();
							return null;
						}
						parsedExpression = parser.parseSingleExpr(last == first, log.getError(), orderedExprInfo.infos[infoIndex]);
					} else if (exprInfo != null) {
						parsedExpression = parser.parseSingleExpr(last == first, log.getError(), exprInfo);
					} else {
						parsedExpression = parser.parseSingleExpr(last == first, log.getError(), types);
					}
				}

				if (parsedExpression == null) { // try again with expanded subExpr
					continue;
				}

				isLiteralList &= parsedExpression instanceof Literal;
				parsedExpressions.add(parsedExpression);
				if (first != 0) {
					String delimiter = expr.substring(pieces.get(first - 1)[1], start).trim().toLowerCase(Locale.ENGLISH);
					if (!delimiter.equals(",")) {
						boolean or = !delimiter.endsWith("nor") && delimiter.endsWith("or");
						if (and.isUnknown()) {
							and = Kleenean.get(!or); // nor is and
						} else if (and != Kleenean.get(!or)) {
							Skript.warning(MULTIPLE_AND_OR + " List: " + expr);
							and = Kleenean.TRUE;
						}
					}
				}

				first = last + 1;
				continue outer;
			}
			// could not parse successfully with the piece starting from "first"
			log.printError();
			return null;
		}

		// determine return types
		Class<?>[] returnTypes;
		Class<?> superReturnType;
		if (parsedExpressions.size() == 1) {
			returnTypes = null;
			superReturnType = parsedExpressions.get(0).getReturnType();
		} else {
			returnTypes = new Class[parsedExpressions.size()];
			for (int i = 0; i < parsedExpressions.size(); i++) {
				returnTypes[i] = parsedExpressions.get(i).getReturnType();
			}
			superReturnType = Classes.getSuperClassInfo(returnTypes).getC();
		}

		// this could be an 'and' list, and the expected list should be an 'or' list
		if (exprInfo != null && !and.isFalse()) {
			boolean canBePlural = false;

			// quick check for direct super type match
			for (int typeIndex = 0; typeIndex < exprInfo.classes.length; typeIndex++) {
				if (exprInfo.isPlural[typeIndex] && exprInfo.classes[typeIndex].getC().isAssignableFrom(superReturnType)) {
					canBePlural = true;
					break;
				}
			}

			// long check against return types for each expression
			if (!canBePlural) {
				for (var parsedExpression : parsedExpressions) { // ensure each expression is of a plural type
					canBePlural = false; // reset for each iteration
					for (int typeIndex = 0; typeIndex < exprInfo.classes.length; typeIndex++) {
						if (!exprInfo.isPlural[typeIndex]) {
							continue;
						}
						if (parsedExpression.canReturn(exprInfo.classes[typeIndex].getC())) {
							canBePlural = true;
							break;
						}
					}
					if (!canBePlural) { // expression could not return a plural type
						break;
					}
				}
			}

			if (!canBePlural) {
				// List cannot be used in place of a single value here
				log.printError();
				return null;
			}
		}

		if (returnTypes == null) { // only parsed one expression out of the pieces
			return parsedExpressions.get(0);
		}

		log.printLog(false);

		if (and.isUnknown() && !suppressMissingAndOrWarnings) {
			ParserInstance parser = getParser();
			if (parser.isActive() && !parser.getCurrentScript().suppressesWarning(ScriptWarning.MISSING_CONJUNCTION)) {
				Skript.warning(MISSING_AND_OR + ": " + expr);
			}
		}

		if (isLiteralList) {
			//noinspection SuspiciousToArrayCall
			Literal<?>[] literals = parsedExpressions.toArray(new Literal[0]);
			//noinspection unchecked, rawtypes
			return new LiteralList(literals, superReturnType, returnTypes, !and.isFalse());
		} else {
			Expression<?>[] expressions = parsedExpressions.toArray(new Expression[0]);
			//noinspection unchecked, rawtypes
			return new ExpressionList(expressions, superReturnType, returnTypes, !and.isFalse());
		}
	}

	/*
	 * Function parsing
	 */

	private final static Pattern FUNCTION_CALL_PATTERN = Pattern.compile("(" + Functions.functionNamePattern + ")\\((.*)\\)");

	/**
	 * @param types The required return type or null if it is not used (e.g. when calling a void function)
	 * @return The parsed function, or null if the given expression is not a function call or is an invalid function call (check for an error to differentiate these two)
	 */
	@SuppressWarnings("unchecked")
	public <T> @Nullable FunctionReference<T> parseFunction(@Nullable Class<? extends T>... types) {
		if (context != ParseContext.DEFAULT && context != ParseContext.EVENT)
			return null;
		try (ParseLogHandler log = SkriptLogger.startParseLogHandler()) {
			Matcher matcher = FUNCTION_CALL_PATTERN.matcher(expr);
			if (!matcher.matches()) {
				log.printLog();
				return null;
			}

			String functionName = matcher.group(1);
			String args = matcher.group(2);

			// Check for incorrect quotes, e.g. "myFunction() + otherFunction()" being parsed as one function
			// See https://github.com/SkriptLang/Skript/issues/1532
			for (int i = 0; i < args.length(); i = next(args, i, context)) {
				if (i == -1) {
					log.printLog();
					return null;
				}
			}

			if ((flags & PARSE_EXPRESSIONS) == 0) {
				Skript.error("Functions cannot be used here (or there is a problem with your arguments).");
				log.printError();
				return null;
			}

			SkriptParser skriptParser = new SkriptParser(args, flags | PARSE_LITERALS, context)
				.suppressMissingAndOrWarnings();
			Expression<?>[] params = args.isEmpty() ? new Expression[0] : null;

			String namespace = null;
			ParserInstance parser = getParser();
			if (parser.isActive()) {
				namespace = parser.getCurrentScript().getConfig().getFileName();
			}

			if (params == null) { // there are arguments to parse
				// determine signatures that could match
				var signatures = FunctionRegistry.getRegistry().getSignatures(namespace, functionName).stream()
					.filter(signature -> {
						if (signature.getMaxParameters() == 0) { // we have arguments, but this function doesn't
							return false;
						}
						if (types != null) { // filter signatures based on expected return type
							if (signature.getReturnType() == null) {
								return false;
							}
							Class<?> signatureType = signature.getReturnType().getC();
							for (Class<?> type : types) {
								//noinspection DataFlowIssue - individual elements won't be null
								if (Converters.converterExists(signatureType, type)) {
									return true;
								}
							}
							return false;
						}
						return true;
					})
					.toList();

				// here, we map all signatures into type/plurality collections
				// for example, all possible types (and whether they are plural) for the first parameter
				//  will be mapped into the 0-index of both collections
				record SignatureData(ClassInfo<?> classInfo, boolean plural) { }
				List<List<SignatureData>> signatureDatas = new ArrayList<>();
				boolean trySingle = false;
				boolean trySinglePlural = false;
				for (var signature : signatures) {
					trySingle |= signature.getMinParameters() == 1 || signature.getMaxParameters() == 1;
					trySinglePlural |= trySingle && !signature.getParameter(0).isSingleValue();
					for (int i = 0; i < signature.getMaxParameters(); i++) {
						if (signatureDatas.size() <= i) {
							signatureDatas.add(new ArrayList<>());
						}
						var parameter = signature.getParameter(i);
						signatureDatas.get(i).add(new SignatureData(parameter.getType(), !parameter.isSingleValue()));
					}
				}
				ExprInfo[] signatureInfos = new ExprInfo[signatureDatas.size()];
				for (int infoIndex = 0; infoIndex < signatureInfos.length; infoIndex++) {
					List<SignatureData> datas = signatureDatas.get(infoIndex);
					ClassInfo<?>[] infos = new ClassInfo[datas.size()];
					boolean[] isPlural = new boolean[infos.length];
					for (int dataIndex = 0; dataIndex < infos.length; dataIndex++) {
						SignatureData data = datas.get(dataIndex);
						infos[dataIndex] = data.classInfo;
						isPlural[dataIndex] = data.plural;
					}
					signatureInfos[infoIndex] = new ExprInfo(infos, isPlural);
				}
				OrderedExprInfo orderedExprInfo = new OrderedExprInfo(signatureInfos);

				if (trySingle) {
					params = this.getFunctionArguments(
						() -> skriptParser.parseSingleExpr(true, null, orderedExprInfo.infos[0]),
						args);
					if (params == null && trySinglePlural) {
						log.clear();
						log.clearError();
						try (ParseLogHandler listLog = SkriptLogger.startParseLogHandler()) {
							params = this.getFunctionArguments(
								() -> skriptParser.parseExpressionList(listLog, orderedExprInfo.infos[0]),
								args);
						}
					}
				}
				if (params == null) {
					log.clear();
					log.clearError();
					try (ParseLogHandler listLog = SkriptLogger.startParseLogHandler()) {
						params = this.getFunctionArguments(
							() -> skriptParser.parseExpressionList(listLog, orderedExprInfo),
							args);
					}
				}
				if (params == null) {
					log.printError();
					return null;
				}
			}

			FunctionReference<T> functionReference = new FunctionReference<>(functionName, SkriptLogger.getNode(), namespace, types, params);
			if (!functionReference.validateFunction(true)) {
				log.printError();
				return null;
			}
			log.printLog();
			return functionReference;
		}
	}

	private Expression<?> @Nullable [] getFunctionArguments(Supplier<Expression<?>> parsing, String args) {
		if (args.isEmpty()) {
			return new Expression[0];
		}

		Expression<?> parsedExpression = parsing.get();
		if (parsedExpression == null) {
			return null;
		}

		Expression<?>[] params;
		if (parsedExpression instanceof ExpressionList) {
			if (!parsedExpression.getAnd()) {
				Skript.error("Function arguments must be separated by commas and optionally an 'and', but not an 'or'."
								 + " Put the 'or' into a second set of parentheses if you want to make it a single parameter, e.g. 'give(player, (sword or axe))'");
				return null;
			}
			params = ((ExpressionList<?>) parsedExpression).getExpressions();
		} else {
			params = new Expression[] {parsedExpression};
		}

		return params;
	}

	/*
	 * Command parsing
	 */

	/**
	 * Prints parse errors (i.e. must start a ParseLog before calling this method)
	 */
	public static boolean parseArguments(String args, ScriptCommand command, ScriptCommandEvent event) {
		SkriptParser parser = new SkriptParser(args, PARSE_LITERALS, ParseContext.COMMAND);
		ParseResult parseResult = parser.parse_i(command.getPattern());
		if (parseResult == null)
			return false;

		List<Argument<?>> arguments = command.getArguments();
		assert arguments.size() == parseResult.exprs.length;
		for (int i = 0; i < parseResult.exprs.length; i++) {
			if (parseResult.exprs[i] == null)
				arguments.get(i).setToDefault(event);
			else
				arguments.get(i).set(event, parseResult.exprs[i].getArray(event));
		}
		return true;
	}

	/*
	 * Utility methods
	 */

	/**
	 * Parses the text as the given pattern as {@link ParseContext#COMMAND}.
	 * <p>
	 * Prints parse errors (i.e. must start a ParseLog before calling this method)
	 */
	public static @Nullable ParseResult parse(String text, String pattern) {
		return new SkriptParser(text, PARSE_LITERALS, ParseContext.COMMAND).parse_i(pattern);
	}

	/**
	 * Parses the text as the given pattern with the given parse context and parse flags.
	 * <p>
	 * Prints parse errors (i.e. must start a ParseLog before calling this method)
	 */
	public static @Nullable ParseResult parse(String text, String pattern, int parseFlags, ParseContext parseContext) {
		return new SkriptParser(text, parseFlags, parseContext).parse_i(pattern);
	}

	/**
	 * Parses the text as the given pattern with the given parse context and parse flags.
	 * <p>
	 * Prints parse errors (i.e. must start a ParseLog before calling this method)
	 */
	public static @Nullable ParseResult parse(String text, SkriptPattern pattern, int parseFlags, ParseContext parseContext) {
		return parse(text, pattern.toString(), parseFlags, parseContext);
	}

	/**
	 * Finds the closing bracket of the group at <tt>start</tt> (i.e. <tt>start</tt> has to be <i>in</i> a group).
	 *
	 * @param pattern The string to search in
	 * @param closingBracket The bracket to look for, e.g. ')'
	 * @param openingBracket A bracket that opens another group, e.g. '('
	 * @param start This must not be the index of the opening bracket!
	 * @param isGroup Whether <tt>start</tt> is assumed to be in a group (will print an error if this is not the case, otherwise it returns <tt>pattern.length()</tt>)
	 * @return The index of the next bracket
	 * @throws MalformedPatternException If the group is not closed
	 */
	public static int nextBracket(String pattern, char closingBracket, char openingBracket, int start, boolean isGroup) throws MalformedPatternException {
		int index = 0;
		for (int i = start; i < pattern.length(); i++) {
			if (pattern.charAt(i) == '\\') {
				i++;
			} else if (pattern.charAt(i) == closingBracket) {
				if (index == 0) {
					if (!isGroup)
						throw new MalformedPatternException(pattern, "Unexpected closing bracket '" + closingBracket + "'");
					return i;
				}
				index--;
			} else if (pattern.charAt(i) == openingBracket) {
				index++;
			}
		}
		if (isGroup)
			throw new MalformedPatternException(pattern, "Missing closing bracket '" + closingBracket + "'");
		return -1;
	}

	/**
	 * Gets the next occurrence of a character in a string that is not escaped with a preceding backslash.
	 *
	 * @param pattern The string to search in
	 * @param character The character to search for
	 * @param from The index to start searching from
	 * @return The next index where the character occurs unescaped or -1 if it doesn't occur.
	 */
	private static int nextUnescaped(String pattern, char character, int from) {
		for (int i = from; i < pattern.length(); i++) {
			if (pattern.charAt(i) == '\\') {
				i++;
			} else if (pattern.charAt(i) == character) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Counts how often the given character occurs in the given string, ignoring any escaped occurrences of the character.
	 *
	 * @param haystack The string to search in
	 * @param needle The character to search for
	 * @return The number of unescaped occurrences of the given character
	 */
	static int countUnescaped(String haystack, char needle) {
		return countUnescaped(haystack, needle, 0, haystack.length());
	}

	/**
	 * Counts how often the given character occurs between the given indices in the given string,
	 * ignoring any escaped occurrences of the character.
	 *
	 * @param haystack The string to search in
	 * @param needle The character to search for
	 * @param start The index to start searching from (inclusive)
	 * @param end The index to stop searching at (exclusive)
	 * @return The number of unescaped occurrences of the given character
	 */
	static int countUnescaped(String haystack, char needle, int start, int end) {
		assert start >= 0 && start <= end && end <= haystack.length() : start + ", " + end + "; " + haystack.length();
		int count = 0;
		for (int i = start; i < end; i++) {
			char character = haystack.charAt(i);
			if (character == '\\') {
				i++;
			} else if (character == needle) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Find the next unescaped (i.e. single) double quote in the string.
	 *
	 * @param string The string to search in
	 * @param start Index after the starting quote
	 * @return Index of the end quote
	 */
	private static int nextQuote(String string, int start) {
		boolean inExpression = false;
		int length = string.length();
		for (int i = start; i < length; i++) {
			char character = string.charAt(i);
			if (character == '"' && !inExpression) {
				if (i == length - 1 || string.charAt(i + 1) != '"')
					return i;
				i++;
			} else if (character == '%') {
				inExpression = !inExpression;
			}
		}
		return -1;
	}

	/**
	 * @param types The types to include in the message
	 * @return "not an x" or "neither an x, a y nor a z"
	 */
	public static String notOfType(Class<?>... types) {
		if (types.length == 1) {
			Class<?> type = types[0];
			assert type != null;
			return Language.get("not") + " " + Classes.getSuperClassInfo(type).getName().withIndefiniteArticle();
		} else {
			StringBuilder message = new StringBuilder(Language.get("neither") + " ");
			for (int i = 0; i < types.length; i++) {
				if (i != 0) {
					if (i != types.length - 1) {
						message.append(", ");
					} else {
						message.append(" ").append(Language.get("nor")).append(" ");
					}
				}
				Class<?> c = types[i];
				assert c != null;
				ClassInfo<?> classInfo = Classes.getSuperClassInfo(c);
				// if there's a registered class info,
				if (classInfo != null) {
					// use the article,
					message.append(classInfo.getName().withIndefiniteArticle());
				} else {
					// otherwise fallback to class name
					message.append(c.getName());
				}
			}
			return message.toString();
		}
	}

	public static String notOfType(ClassInfo<?>... types) {
		if (types.length == 1) {
			return Language.get("not") + " " + types[0].getName().withIndefiniteArticle();
		} else {
			StringBuilder message = new StringBuilder(Language.get("neither") + " ");
			for (int i = 0; i < types.length; i++) {
				if (i != 0) {
					if (i != types.length - 1) {
						message.append(", ");
					} else {
						message.append(" ").append(Language.get("nor")).append(" ");
					}
				}
				message.append(types[i].getName().withIndefiniteArticle());
			}
			return message.toString();
		}
	}

	/**
	 * Returns the next character in the expression, skipping strings,
	 * variables and parentheses
	 * (unless {@code context} is {@link ParseContext#COMMAND} or {@link ParseContext#PARSE}).
	 *
	 * @param expr The expression to traverse.
	 * @param startIndex The index to start at.
	 * @return The next index (can be expr.length()), or -1 if
	 * an invalid string, variable or bracket is found
	 * or if {@code startIndex >= expr.length()}.
	 * @throws StringIndexOutOfBoundsException if {@code startIndex < 0}.
	 */
	public static int next(String expr, int startIndex, ParseContext context) {
		if (startIndex < 0)
			throw new StringIndexOutOfBoundsException(startIndex);

		int exprLength = expr.length();
		if (startIndex >= exprLength)
			return -1;

		if (context == ParseContext.COMMAND || context == ParseContext.PARSE)
			return startIndex + 1;

		int index;
		switch (expr.charAt(startIndex)) {
			case '"':
				index = nextQuote(expr, startIndex + 1);
				return index < 0 ? -1 : index + 1;
			case '{':
				index = VariableString.nextVariableBracket(expr, startIndex + 1);
				return index < 0 ? -1 : index + 1;
			case '(':
				for (index = startIndex + 1; index >= 0 && index < exprLength; index = next(expr, index, context)) {
					if (expr.charAt(index) == ')')
						return index + 1;
				}
				return -1;
			default:
				return startIndex + 1;
		}
	}

	/**
	 * Returns the next occurrence of the needle in the haystack.
	 * Similar to {@link #next(String, int, ParseContext)}, this method skips
	 * strings, variables and parentheses (unless <tt>context</tt> is {@link ParseContext#COMMAND}
	 * or {@link ParseContext#PARSE}).
	 *
	 * @param haystack The string to search in.
	 * @param needle The string to search for.
	 * @param startIndex The index to start in within the haystack.
	 * @param caseSensitive Whether this search will be case-sensitive.
	 * @return The next index representing the first character of the needle.
	 * May return -1 if an invalid string, variable or bracket is found or if <tt>startIndex >= hatsack.length()</tt>.
	 * @throws StringIndexOutOfBoundsException if <tt>startIndex < 0</tt>.
	 */
	public static int nextOccurrence(String haystack, String needle, int startIndex, ParseContext parseContext, boolean caseSensitive) {
		if (startIndex < 0)
			throw new StringIndexOutOfBoundsException(startIndex);
		if (parseContext == ParseContext.COMMAND || parseContext == ParseContext.PARSE)
			return haystack.indexOf(needle, startIndex);

		int haystackLength = haystack.length();
		if (startIndex >= haystackLength)
			return -1;

		int needleLength = needle.length();

		char firstChar = needle.charAt(0);
		boolean startsWithSpecialChar = firstChar == '"' || firstChar == '{' || firstChar == '(';

		while (startIndex < haystackLength) {

			char character = haystack.charAt(startIndex);

			if ( // Early check before special character handling
				startsWithSpecialChar &&
				haystack.regionMatches(!caseSensitive, startIndex, needle, 0, needleLength)
			) {
				return startIndex;
			}

			switch (character) {
				case '"':
					startIndex = nextQuote(haystack, startIndex + 1);
					if (startIndex < 0)
						return -1;
					break;
				case '{':
					startIndex = VariableString.nextVariableBracket(haystack, startIndex + 1);
					if (startIndex < 0)
						return -1;
					break;
				case '(':
					startIndex = next(haystack, startIndex, parseContext); // Use other function to skip to right after closing parentheses
					if (startIndex < 0)
						return -1;
					break;
			}

			if (haystack.regionMatches(!caseSensitive, startIndex, needle, 0, needleLength))
				return startIndex;

			startIndex++;
		}

		return -1;
	}

	private static final Map<String, SkriptPattern> patterns = new ConcurrentHashMap<>();

	private @Nullable ParseResult parse_i(String pattern) {
		SkriptPattern skriptPattern = patterns.computeIfAbsent(pattern, PatternCompiler::compile);
		ch.njol.skript.patterns.MatchResult matchResult = skriptPattern.match(expr, flags, context);
		if (matchResult == null)
			return null;
		return matchResult.toParseResult();
	}

	/**
	 * Validates a user-defined pattern (used in {@link ExprParse}).
	 *
	 * @param pattern The pattern string to validate
	 * @return The pattern with %codenames% and a boolean array that contains whether the expressions are plural or not
	 */
	public static @Nullable NonNullPair<String, NonNullPair<ClassInfo<?>, Boolean>[]> validatePattern(String pattern) {
		List<NonNullPair<ClassInfo<?>, Boolean>> pairs = new ArrayList<>();
		int groupLevel = 0, optionalLevel = 0;
		Deque<Character> groups = new LinkedList<>();
		StringBuilder stringBuilder = new StringBuilder(pattern.length());
		int last = 0;
		for (int i = 0; i < pattern.length(); i++) {
			char character = pattern.charAt(i);
			if (character == '(') {
				groupLevel++;
				groups.addLast(character);
			} else if (character == '|') {
				if (groupLevel == 0 || groups.peekLast() != '(' && groups.peekLast() != '|')
					return error("Cannot use the pipe character '|' outside of groups. Escape it if you want to match a literal pipe: '\\|'");
				groups.removeLast();
				groups.addLast(character);
			} else if (character == ')') {
				if (groupLevel == 0 || groups.peekLast() != '(' && groups.peekLast() != '|')
					return error("Unexpected closing group bracket ')'. Escape it if you want to match a literal bracket: '\\)'");
				if (groups.peekLast() == '(')
					return error("(...|...) groups have to contain at least one pipe character '|' to separate it into parts. Escape the brackets if you want to match literal brackets: \"\\(not a group\\)\"");
				groupLevel--;
				groups.removeLast();
			} else if (character == '[') {
				optionalLevel++;
				groups.addLast(character);
			} else if (character == ']') {
				if (optionalLevel == 0 || groups.peekLast() != '[')
					return error("Unexpected closing optional bracket ']'. Escape it if you want to match a literal bracket: '\\]'");
				optionalLevel--;
				groups.removeLast();
			} else if (character == '<') {
				int j = pattern.indexOf('>', i + 1);
				if (j == -1)
					return error("Missing closing regex bracket '>'. Escape the '<' if you want to match a literal bracket: '\\<'");
				try {
					Pattern.compile(pattern.substring(i + 1, j));
				} catch (PatternSyntaxException e) {
					return error("Invalid Regular Expression '" + pattern.substring(i + 1, j) + "': " + e.getLocalizedMessage());
				}
				i = j;
			} else if (character == '>') {
				return error("Unexpected closing regex bracket '>'. Escape it if you want to match a literal bracket: '\\>'");
			} else if (character == '%') {
				int j = pattern.indexOf('%', i + 1);
				if (j == -1)
					return error("Missing end sign '%' of expression. Escape the percent sign to match a literal '%': '\\%'");
				NonNullPair<String, Boolean> pair = Utils.getEnglishPlural("" + pattern.substring(i + 1, j));
				ClassInfo<?> classInfo = Classes.getClassInfoFromUserInput(pair.getFirst());
				if (classInfo == null)
					return error("The type '" + pair.getFirst() + "' could not be found. Please check your spelling or escape the percent signs if you want to match literal %s: \"\\%not an expression\\%\"");
				pairs.add(new NonNullPair<>(classInfo, pair.getSecond()));
				stringBuilder.append(pattern, last, i + 1);
				stringBuilder.append(Utils.toEnglishPlural(classInfo.getCodeName(), pair.getSecond()));
				last = j;
				i = j;
			} else if (character == '\\') {
				if (i == pattern.length() - 1)
					return error("Pattern must not end in an unescaped backslash. Add another backslash to escape it, or remove it altogether.");
				i++;
			}
		}
		stringBuilder.append(pattern.substring(last));
		//noinspection unchecked
		return new NonNullPair<>(stringBuilder.toString(), pairs.toArray(new NonNullPair[0]));
	}

	private static @Nullable NonNullPair<String, NonNullPair<ClassInfo<?>, Boolean>[]> error(final String error) {
		Skript.error("Invalid pattern: " + error);
		return null;
	}

	private final static Message M_QUOTES_ERROR = new Message("skript.quotes error");
	private final static Message M_BRACKETS_ERROR = new Message("skript.brackets error");

	public static boolean validateLine(String line) {
		if (StringUtils.count(line, '"') % 2 != 0) {
			Skript.error(M_QUOTES_ERROR.toString());
			return false;
		}
		for (int i = 0; i < line.length(); i = next(line, i, ParseContext.DEFAULT)) {
			if (i == -1) {
				Skript.error(M_BRACKETS_ERROR.toString());
				return false;
			}
		}
		return true;
	}

	public static class ExprInfo {
		public ExprInfo(int length) {
			this(new ClassInfo[length], new boolean[length]);
		}

		public ExprInfo(ClassInfo<?>[] classes, boolean[] isPlural) {
			Preconditions.checkState(classes.length == isPlural.length, "classes and isPlural must be the same length");
			this.classes = classes;
			this.isPlural = isPlural;
		}

		public final ClassInfo<?>[] classes;
		public final boolean[] isPlural;
		public boolean isOptional;
		public int flagMask = ~0;
		public int time = 0;
	}

	/**
	 * @see ParserInstance#get()
	 */
	private static ParserInstance getParser() {
		return ParserInstance.get();
	}

	// register default value data when the parser class is loaded.
	static {
		ParserInstance.registerData(DefaultValueData.class, DefaultValueData::new);
	}

	/**
	 * @deprecated due to bad naming conventions,
	 * use {@link #LIST_SPLIT_PATTERN} instead. 
	 */
	@Deprecated(since = "2.7.0", forRemoval = true)
	public final static Pattern listSplitPattern = LIST_SPLIT_PATTERN;

	/**
	 * @deprecated due to bad naming conventions,
	 * use {@link #WILDCARD} instead.
	 */
	@Deprecated(since = "2.8.0", forRemoval = true)
	public final static String wildcard = WILDCARD;

}
