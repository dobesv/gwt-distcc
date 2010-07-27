package gwtdistcc.client;

public class ApiException extends Exception {
	private static final long serialVersionUID = 1L;
	int statusCode;
	
	public ApiException() {
		super();
	}

	public ApiException(String message, Throwable cause) {
		super(message, cause);
	}

	public ApiException(String message) {
		super(message);
	}

	public ApiException(Throwable cause) {
		super(cause);
	}

	public ApiException(int statusCode, String statusText) {
		super(statusText);
		this.statusCode = statusCode;
	}

	public int getStatusCode() {
		return statusCode;
	}

	
}
