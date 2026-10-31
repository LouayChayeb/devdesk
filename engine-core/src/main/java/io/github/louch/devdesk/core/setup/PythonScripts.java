package io.github.louch.devdesk.core.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises plain Python scripts that keep running: a server ({@code serve_forever()}, {@code app.run()}), a loop
 * ({@code while True}) or a {@code __main__} script. Projects without a build file are often just that.
 */
final class PythonScripts {

    private static final Pattern RUNS = Pattern.compile(
            "serve_forever\\(|\\bwhile True\\s*:|__name__\\s*==\\s*[\"']__main__[\"']|\\bapp\\.run\\(|uvicorn\\.run\\(");
    /** {@code os.environ["X"]}: the script fails without X (KeyError), so the card asks for it. */
    private static final Pattern REQUIRED_ENV =
            Pattern.compile("os\\.environ\\[\\s*[\"']([A-Za-z_][A-Za-z0-9_]*)[\"']\\s*]");
    /** A literal port in the script: {@code ("127.0.0.1", 8000)}, {@code port=8000}, {@code getenv("PORT", "8000")}. */
    private static final Pattern PORT = Pattern.compile(
            "\\(\\s*[\"'][\\d.]+[\"']\\s*,\\s*(\\d{2,5})\\s*\\)|\\bport\\s*=\\s*(\\d{2,5})\\b|[\"']PORT[\"']\\s*,\\s*[\"']?(\\d{2,5})");

    private PythonScripts() {}

    /**
     * @param files the folder's top-level file names
     * @param read reads a file of the folder
     */
    static List<SetupDraft.Service> find(
            List<String> files, Function<String, Optional<String>> read, Set<String> takenNames) {
        List<SetupDraft.Service> found = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>(takenNames);
        for (String file :
                files.stream().filter(f -> f.endsWith(".py")).sorted().toList()) {
            Optional<String> code = read.apply(file);
            if (code.isEmpty() || !RUNS.matcher(code.get()).find() || isTest(file, code.get())) {
                continue;
            }
            String name = DatabaseTemplates.uniqueName(
                    ComposeImport.serviceName(file.substring(0, file.length() - 3)), names);
            names.add(name);
            Map<String, String> env = new LinkedHashMap<>();
            Matcher m = REQUIRED_ENV.matcher(code.get());
            while (m.find()) {
                env.put(m.group(1), "");
            }
            Optional<Integer> port = firstPort(code.get());
            List<String> notes = new ArrayList<>();
            if (!env.isEmpty()) {
                notes.add(file + " reads " + String.join(", ", env.keySet())
                        + " and fails without them: give each a value (or put it in .env).");
            }
            found.add(new SetupDraft.Service(
                    name,
                    "command",
                    AppDetector.inVenv(read, "python3 " + file),
                    "",
                    "",
                    port.map(p -> List.of(String.valueOf(p))).orElse(List.of()),
                    List.of(),
                    env,
                    Map.of(),
                    port.map(SetupDraft.Health::tcp).orElse(SetupDraft.Health.PROCESS),
                    "on-failure",
                    file,
                    notes));
        }
        return found;
    }

    private static Optional<Integer> firstPort(String code) {
        Matcher m = PORT.matcher(code);
        while (m.find()) {
            for (int g = 1; g <= 3; g++) {
                if (m.group(g) != null) {
                    return Optional.of(Integer.parseInt(m.group(g)));
                }
            }
        }
        return Optional.empty();
    }

    /** A test file has a __main__ block too ("unittest.main()"), but it's not something that keeps running. */
    private static boolean isTest(String file, String code) {
        return file.matches("tests?\\.py|test_.*\\.py|.*_tests?\\.py|conftest\\.py")
                || code.contains("unittest.main(")
                || code.contains("pytest.main(");
    }
}
