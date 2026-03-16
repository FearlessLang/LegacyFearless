package repl;

import program.Program;
import program.TypeSystemFeatures;
import parser.Parser;
import org.apache.commons.cli.*;

import failure.CompileError;

import java.util.*;
import java.nio.file.Path;

public class Repl {
	private final static String GOOD_PROMPT = "fear> ";
	private final static String BAD_PROMPT = "  ... ";

	/**
	 * Tries to compile the given state.
	 */
	static Program compile(State state) throws CompileError {
		var ps = List.of(new Parser(Path.of("DummyREPL.fear"), state.asCode()));
		var res = Parser.parseAll(ps, TypeSystemFeatures.of());

		return res;
	}

	/**
	 * Return true if it compiles fine,
	 * false if CompileError
	 */
	static boolean isValid(State state) {
		try {
			compile(state);
			return true;
		} catch (CompileError e) {
			return false;
		}
	}

	static Options createOptions() {
		var options = new Options();

		options.addOption("h", "help", false, "Show this message");
		options.addOption("p", "package", true, "Set REPL's package name");
		options.addOption("r", "refresh", false, "Refresh program state after every input.");

		return options;
	}

	static CommandLine parseArgs(String[] args, Options options) throws ParseException {
		// Based on https://stackoverflow.com/a/367714
		var parser = new DefaultParser();
		CommandLine cmd = null;

		cmd = parser.parse(options, args);

		return cmd;
	}

	static SlashCommand slashCommand(String cmd) {
		assert cmd.startsWith("/");

		var sc = new Scanner(cmd.substring(1));
		String word = sc.next();
		sc.close();

		// TODO: Pass in scanner instead for arguments?
		return SlashCommand.from(word);
	}

	public static void main(String[] args) {
		CommandLine conf = null;
		HelpFormatter formatter = new HelpFormatter();
		Options options = createOptions();

		try {
			conf = parseArgs(args, options);
		} catch (ParseException e) {
			System.err.println(e.getMessage());
			formatter.printHelp("java -cp fearless.jar repl.Repl", createOptions());

			System.exit(1);
		}

		if (conf.hasOption("help")) {
			formatter.printHelp("java -cp fearless.jar repl.Repl", createOptions());
			System.exit(127);
		}

		State state = new State(conf.getOptionValue("package", "user"));
		State lastGoodState = new State(state);

		Scanner sc = new Scanner(System.in);
		String prompt = GOOD_PROMPT;

		// Hold validity in variable to avoid recompiling every time.
		boolean valid = true;

mainloop: while (true) {
			if (conf.hasOption("refresh")) {
				state = new State(state.packageName());
				lastGoodState = new State(state);
			}

			System.out.print(prompt);
			String line;

			try {
				line = sc.nextLine();
			} catch (NoSuchElementException e) {
				// User pressed Ctrl D. stdin is closed.
				break mainloop;
			}

			// Handle slash commands.
			if (line.startsWith("/")) {
				switch (slashCommand(line)) {
					case Help -> SlashCommand.printHelp();
					case Quit -> {
						break mainloop;
					}
					case Reset -> {
						state = new State(state.packageName());
						lastGoodState = new State(state);
						prompt = GOOD_PROMPT;
					}
					case Rollback -> {
						state = new State(lastGoodState);
						prompt = GOOD_PROMPT;
					}
					case Show -> System.out.println(state.asCode());
					case Error -> {
						try {
							compile(state);
							System.out.println("No Compiler Error");
						} catch (CompileError e) {
							System.out.println(e);
						}
					}
					default -> {
						System.out.println("Unknown command.");
						SlashCommand.printHelp();
					}
				}

				continue;
			}

			try {
				state.add(line, valid);
			} catch (InvalidAliasException e) {
				System.err.println("Error: " + e);
				state = new State(lastGoodState);
				continue;
			}

			valid = isValid(state);

			if (valid) {
				prompt = GOOD_PROMPT;

				// TODO: Check if it ran successfully
				// if (success) {
				lastGoodState = new State(state);
				// }
			} else {
				prompt = BAD_PROMPT;
			}
		}

		// Technically not ideal to close stdin, but it brings up a leaked resource
		// warning otherwise.
		sc.close();
	}
}

enum SlashCommand {
	Unknown, Help, Show, Reset, Rollback, Error, Quit;

	public static void printHelp() {
		System.out.println("""
				/help\tShow this help
				/show\tPrint current state as code
				/reset\tReset state
				/rollback\tChange state to last known good state
				/error\tPrint Compiler Error
				/quit\tExit repl""");
	}

	public static SlashCommand from(String str) {
		return switch (str) {
			case "help" -> Help;
			case "show" -> Show;
			case "reset" -> Reset;
			case "rollback" -> Rollback;
			case "quit" -> Quit;
			case "error" -> Error;
			default -> Unknown;
		};
	}
}
