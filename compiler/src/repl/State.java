package repl;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * The current program state of the repl.
 */
public class State {
	private String packageName;
	/** Alias declarations, stored separately so aliases can be created at any time */
	private List<String> aliases = new ArrayList<>();
	/** The user provided code */
	private List<String> code = new ArrayList<>();

	public State(String packageName) {
		this.packageName = packageName;
	}

	/**
	 * Deep copy of the state
	 */
	public State(State other) {
		this(other.packageName);
		aliases.addAll(other.aliases);
		code.addAll(other.code);
	}

	public String packageName() {
		return packageName;
	}

	/**
	 * Let state decide if this is alias or code.
	 */
	public void add(String line, boolean currentlyValid) throws InvalidAliasException {
		// We assume that when the state is invalid, the user can only add
		// normal code until it's valid again.
		if (currentlyValid && line.strip().startsWith("alias")) {
			addAlias(line);
		} else {
			addCode(line);
		}
	}
	
	/**
	 * Add a line of code to the list...
	 */
	public void addCode(String loc) {
		code.add(loc);
	}

	/**
	 * Add an alias to the list, performing basic syntax checks.
	 * @param alias The alias
	 * @throws InvalidAliasException If the synrax is wrong.
	 */
	public void addAlias(String alias) throws InvalidAliasException {
		String stripped = alias.strip();

		// TODO: Leverage parser to check correctness
		if (!stripped.startsWith("alias") && !stripped.endsWith(",")) {
			throw new InvalidAliasException("Invalid alias syntax");
		}

		// TODO: Let user override existing aliases
		aliases.add(alias);
	}

	/**
	 * Converts all the held state into a single string that can be passed to
	 * program or whatever.
	 * @return Fearless code, not guaranteed to be vaiid
	 */
	public String asCode() {
		// Merging from https://www.baeldung.com/java-merge-streams
		return Stream.of(
				Stream.of("package " + packageName + "\n"),
				aliases.stream(),
				// Stream.of("REPLMain: Main { sys ->"),
				code.stream()
				// Stream.of("}")
				)
			.flatMap(Function.identity())
			.collect(Collectors.joining("\n"));
	}
}
