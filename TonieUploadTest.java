//JAVA 17+
//DEPS info.picocli:picocli:4.7.7
//DEPS rocks.voss:toniebox-api:3.7
//DEPS com.fasterxml.jackson:jackson-bom:2.22.4@pom
//DEPS com.fasterxml.jackson.core:jackson-databind
//DEPS commons-io:commons-io:2.22.0
//SOURCES TonieUpload.java

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public class TonieUploadTest {
    public static void main(String[] args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        TonieUpload.checkExecutable(java, "--version", "java", "install it");
        try {
            TonieUpload.checkExecutable(java, "--not-a-real-option", "java", "install it");
            throw new AssertionError("A failing executable must not pass the prerequisite check");
        } catch (IOException e) {
            if (!e.getMessage().contains("exited with code")) {
                throw e;
            }
        }

        assertPreflightFailure("missing-yt-dlp-for-test", "Could not run yt-dlp", false);
        assertPreflightFailure(java, "Could not run ffmpeg", true);
        System.out.println("Prerequisite checks passed.");
    }

    static void assertPreflightFailure(String ytDlp, String expected, boolean clearPath) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"),
                "TonieUpload", "--yt-dlp", ytDlp, "https://example.com/video"))
                .redirectErrorStream(true);
        builder.environment().put("TONIE_USERNAME", "test");
        builder.environment().put("TONIE_PASSWORD", "test");
        if (clearPath) {
            builder.environment().put("PATH", "");
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode == 0 || !output.contains(expected)) {
            throw new IOException("Expected " + expected + " before login; exit " + exitCode + ": " + output);
        }
    }
}
