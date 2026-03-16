package repl;

public class InvalidAliasException extends Exception {
	public InvalidAliasException(String msg) {
		super(msg);
	}

	public InvalidAliasException(Throwable cause) {
		super(cause);
	}

	public InvalidAliasException(String msg, Throwable cause) {
		super(msg, cause);
	}
}
