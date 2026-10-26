package io.github.louch.devdesk.core.setup;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import java.util.Optional;

/** Fuzz test (Jazzer): any docker-compose.yml, even a broken one, imports to cards and notes without crashing. */
class ComposeImportFuzzTest {

    @FuzzTest(maxDuration = "10s")
    void composeFile(FuzzedDataProvider data) {
        String yaml = data.consumeRemainingAsString();
        ComposeImport.read(
                yaml,
                "shop",
                "shop",
                folder -> Optional.of(new ComposeImport.DetectedApp(
                        "npm run start", Optional.of(3000), Optional.empty(), "package.json", List.of())));
    }
}
