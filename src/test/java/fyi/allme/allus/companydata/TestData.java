package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Shared test helpers: locate + load the cross-language decryption vector
 * ({@code sdks/testdata/decryption-vector.json}) that every SDK port must
 * decrypt identically.
 */
final class TestData {
    private TestData() {
    }

    /** Resolve {@code testdata/} from the Java module dir (self-contained in this repo). */
    static Path testdataDir() {
        // Surefire runs with the module dir (the repo root) as the working directory.
        Path moduleDir = Path.of(System.getProperty("user.dir"));
        Path candidate = moduleDir.resolve("testdata");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // Fallback: walk up looking for a sibling testdata dir (robust to runners).
        for (Path p = moduleDir; p != null; p = p.getParent()) {
            Path c = p.resolve("testdata").resolve("decryption-vector.json");
            if (Files.isRegularFile(c)) {
                return c.getParent();
            }
            Path c2 = p.resolveSibling("testdata").resolve("decryption-vector.json");
            if (Files.isRegularFile(c2)) {
                return c2.getParent();
            }
        }
        throw new IllegalStateException("could not locate sdks/testdata from " + moduleDir);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> vector() {
        Path path = testdataDir().resolve("decryption-vector.json");
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return Json.parseObject(text);
        } catch (IOException exc) {
            throw new IllegalStateException("could not read vector " + path, exc);
        }
    }
}
