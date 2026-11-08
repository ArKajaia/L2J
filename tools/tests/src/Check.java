import java.util.Objects;

/**
 * Assertions for the tests in tools/tests/src: each throws an AssertionError with its message.
 */
public final class Check
{
	private Check()
	{
	}
	
	public static void isTrue(boolean condition, String message)
	{
		if (!condition)
		{
			throw new AssertionError(message);
		}
	}
	
	public static void eq(Object actual, Object expected, String message)
	{
		if (!Objects.equals(actual, expected))
		{
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
		}
	}
	
	public static void near(double actual, double expected, double epsilon, String message)
	{
		if (Double.isNaN(actual) || (Math.abs(actual - expected) > epsilon))
		{
			throw new AssertionError(message + ": expected " + expected + " ±" + epsilon + ", got " + actual);
		}
	}
	
	/**
	 * Runs each named test and prints "Name: OK" or the failure, exiting with status 1 on a failure.
	 * @param suite the test class name
	 * @param tests the tests, in order
	 */
	public static void run(String suite, Runnable... tests)
	{
		int i = 0;
		try
		{
			for (Runnable test : tests)
			{
				i++;
				test.run();
			}
		}
		catch (Throwable e)
		{
			System.out.println(suite + ": FAILED (test " + i + "): " + e);
			System.exit(1);
		}
		System.out.println(suite + ": OK (" + tests.length + " tests)");
	}
}
