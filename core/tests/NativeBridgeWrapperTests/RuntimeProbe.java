import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public class RuntimeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("--wrapper-args")) {
            testWrapperArguments();
        }
        System.out.println("RUNTIME_PROBE_PASS arch=" + System.getProperty("os.arch"));
    }

    private static void testWrapperArguments() throws Exception {
        Method method = Class.forName("com.android.internal.os.WrapperInit").getDeclaredMethod(
                "appendNativeBridgeArgs", StringBuilder.class, String.class, String.class,
                String.class, String.class);
        method.setAccessible(true);
        String path = "/data/user/10/quo'ted;$(echo injected)/com.example";
        String library = "lib'$(echo injected).so";
        StringBuilder command = new StringBuilder("printf '%s\\n'");
        method.invoke(null, command, "arm64", path, "x86_64", library);
        Process process = new ProcessBuilder("/system/bin/sh", "-c", command.toString()).start();
        StringBuilder actual = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                actual.append(line).append('\n');
            }
        }
        String expected = "-XX:NativeBridge=" + library + "\n"
                + "-XX:NativeBridgeInstructionSet=arm64\n"
                + "-XX:NativeBridgeAppDataDir=" + path + "\n";
        if (process.waitFor() != 0 || !expected.equals(actual.toString())) {
            throw new AssertionError("Shell argument round-trip failed: " + actual);
        }
        for (String[] test : new String[][] {
                {"x86_64", "libbridge.so"}, {"arm64", ""}, {"arm64", "0"},
                {null, "libbridge.so"}}) {
            command = new StringBuilder();
            method.invoke(null, command, test[0], null, "x86_64", test[1]);
            if (command.length() != 0) {
                throw new AssertionError("Unexpected bridge options: " + command);
            }
        }
        for (String invalid : new String[] {null, "", ".", "relative/path", "/", "/data/user/0/pkg/"}) {
            try {
                method.invoke(null, new StringBuilder(), "arm64", invalid, "x86_64", "libbridge.so");
                throw new AssertionError("Invalid app directory accepted");
            } catch (InvocationTargetException error) {
                if (!(error.getCause() instanceof IllegalArgumentException)) {
                    throw error;
                }
            }
        }
        System.out.println("WRAPPER_ARGUMENTS_PASS");
    }
}
