///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS info.picocli:picocli:4.7.7
//DEPS rocks.voss:toniebox-api:3.7
// Override vulnerable transitive dependencies of toniebox-api
//DEPS com.fasterxml.jackson:jackson-bom:2.22.4@pom
//DEPS com.fasterxml.jackson.core:jackson-databind
//DEPS commons-io:commons-io:2.22.0

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import rocks.voss.toniebox.TonieHandler;
import rocks.voss.toniebox.beans.toniebox.Chapter;
import rocks.voss.toniebox.beans.toniebox.CreativeTonie;
import rocks.voss.toniebox.beans.toniebox.Household;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Downloads the audio of a YouTube video (using yt-dlp + ffmpeg) and uploads it
 * as a new chapter to one of your creative Tonies.
 *
 * Credentials are read from the TONIE_USERNAME and TONIE_PASSWORD environment variables.
 */
@Command(name = "TonieUpload", mixinStandardHelpOptions = true, version = "TonieUpload 0.1",
        description = "Download audio from a YouTube URL and upload it to a creative Tonie.")
public class TonieUpload implements Callable<Integer> {

    static final String ENV_USERNAME = "TONIE_USERNAME";
    static final String ENV_PASSWORD = "TONIE_PASSWORD";

    @Parameters(index = "0", arity = "0..1", paramLabel = "URL", description = "YouTube URL to download audio from.")
    String url;

    @Option(names = {"-t", "--tonie"}, paramLabel = "NAME_OR_ID",
            description = "Name or id of the creative Tonie to upload to. Optional if you only have one.")
    String tonieName;

    @Option(names = {"--title"}, description = "Chapter title on the Tonie. Defaults to the YouTube video title.")
    String title;

    @Option(names = {"-l", "--list"}, description = "List your creative Tonies and their chapters, then exit.")
    boolean list;

    @Option(names = {"--yt-dlp"}, paramLabel = "PATH", defaultValue = "yt-dlp",
            description = "yt-dlp executable (default: ${DEFAULT-VALUE}). ffmpeg must be on the PATH.")
    String ytDlp;

    public static void main(String... args) {
        // toniebox-api logs via log4j-api; use its built-in simple logger instead of
        // printing a "could not find a logging implementation" warning.
        System.setProperty("log4j2.loggerContextFactory",
                "org.apache.logging.log4j.simple.SimpleLoggerContextFactory");
        System.exit(new CommandLine(new TonieUpload())
                .setExecutionExceptionHandler((e, cmd, parseResult) -> {
                    System.err.println("Error: " + e);
                    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                        System.err.println("  caused by: " + cause);
                    }
                    return 1;
                })
                .execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (!list && (url == null || url.isBlank())) {
            System.err.println("A YouTube URL is required (or use --list).");
            return 2;
        }
        String username = System.getenv(ENV_USERNAME);
        String password = System.getenv(ENV_PASSWORD);
        if (isBlank(username) || isBlank(password)) {
            System.err.printf("Please set the %s and %s environment variables.%n", ENV_USERNAME, ENV_PASSWORD);
            return 2;
        }

        if (!list) {
            checkExecutable(ytDlp, "--version", "yt-dlp", "install it or specify its path with --yt-dlp");
            checkExecutable("ffmpeg", "-version", "ffmpeg", "install it and add it to your PATH");
        }

        TonieHandler tonieHandler = new TonieHandler();
        tonieHandler.login(username, password);
        try {
            List<CreativeTonie> tonies = getCreativeTonies(tonieHandler);
            if (list) {
                tonies.forEach(TonieUpload::printTonie);
                return 0;
            }

            CreativeTonie tonie = selectTonie(tonies, tonieName);
            if (tonie == null) {
                return 1;
            }
            if (tonie.getChapters() == null) {
                tonie.setChapters(new Chapter[0]);
            }
            if (tonie.getChaptersRemaining() <= 0) {
                System.err.printf("Creative Tonie '%s' has no chapters remaining.%n", tonie.getName());
                return 1;
            }

            Path workDir = Files.createTempDirectory("tonie-upload");
            try {
                Download download = downloadAudio(url, workDir);
                if (download.durationSeconds > 0 && download.durationSeconds > tonie.getSecondsRemaining()) {
                    System.err.printf("'%s' is %s long but creative Tonie '%s' only has %s remaining.%n",
                            download.title, formatSeconds(download.durationSeconds), tonie.getName(),
                            formatSeconds(tonie.getSecondsRemaining()));
                    return 1;
                }
                String chapterTitle = isBlank(title) ? download.title : title;
                System.out.printf("Uploading '%s' to creative Tonie '%s'...%n", chapterTitle, tonie.getName());
                tonie.uploadFile(chapterTitle, download.file.toString());
                tonie.commit();
                System.out.println("Done. The Tonie will play the new chapter after it has been transcoded and synced.");
                return 0;
            } finally {
                deleteRecursively(workDir);
            }
        } finally {
            try {
                tonieHandler.disconnect();
            } catch (IOException e) {
                System.err.println("Warning: failed to log out: " + e.getMessage());
            }
        }
    }

    static List<CreativeTonie> getCreativeTonies(TonieHandler tonieHandler) throws IOException {
        List<Household> households;
        try {
            households = tonieHandler.getHouseholds();
        } catch (IOException e) {
            throw new IOException("Could not load households - check your Tonie username and password.", e);
        }
        List<CreativeTonie> tonies = new ArrayList<>();
        for (Household household : households) {
            tonies.addAll(tonieHandler.getCreativeTonies(household));
        }
        return tonies;
    }

    static CreativeTonie selectTonie(List<CreativeTonie> tonies, String nameOrId) {
        if (tonies.isEmpty()) {
            System.err.println("No creative Tonies found for this account.");
            return null;
        }
        if (isBlank(nameOrId)) {
            if (tonies.size() == 1) {
                return tonies.get(0);
            }
            System.err.println("You have more than one creative Tonie, please choose one with --tonie:");
            tonies.forEach(t -> System.err.printf("  %s (id: %s)%n", t.getName(), t.getId()));
            return null;
        }
        List<CreativeTonie> matches = tonies.stream()
                .filter(t -> nameOrId.equals(t.getId()) || nameOrId.equalsIgnoreCase(t.getName()))
                .collect(Collectors.toList());
        if (matches.size() == 1) {
            return matches.get(0);
        }
        System.err.printf(matches.isEmpty()
                ? "No creative Tonie named '%s'. Available:%n"
                : "More than one creative Tonie matches '%s', please use the id instead:%n", nameOrId);
        (matches.isEmpty() ? tonies : matches)
                .forEach(t -> System.err.printf("  %s (id: %s)%n", t.getName(), t.getId()));
        return null;
    }

    static void printTonie(CreativeTonie tonie) {
        System.out.printf("%s (id: %s) - %d chapters, %s used, %s remaining%n",
                tonie.getName(), tonie.getId(), tonie.getChaptersPresent(),
                formatSeconds(tonie.getSecondsPresent()), formatSeconds(tonie.getSecondsRemaining()));
        if (tonie.getChapters() != null) {
            int i = 1;
            for (Chapter chapter : tonie.getChapters()) {
                System.out.printf("  %2d. %s (%s)%s%n", i++, chapter.getTitle(), formatSeconds(chapter.getSeconds()),
                        chapter.isTranscoding() ? " [transcoding]" : "");
            }
        }
    }

    record Download(Path file, String title, float durationSeconds) {
    }

    static void checkExecutable(String executable, String versionOption, String name, String guidance)
            throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(executable, versionOption)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
        } catch (IOException e) {
            throw new IOException("Could not run " + name + " ('" + executable + "'); " + guidance + ".", e);
        }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IOException(name + " ('" + executable + "') did not respond within 30 seconds.");
            }
            if (process.exitValue() != 0) {
                throw new IOException(name + " ('" + executable + "') exited with code "
                        + process.exitValue() + " during the prerequisite check.");
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Uses yt-dlp (which uses ffmpeg) to extract the audio of the given URL as mp3 into the given directory.
     */
    Download downloadAudio(String url, Path workDir) throws IOException, InterruptedException {
        List<String> command = buildYtDlpCommand(ytDlp, url, workDir);
        System.out.println("Downloading audio from " + url + " ...");
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
        } catch (IOException e) {
            throw new IOException("Could not run '" + ytDlp + "'. Is yt-dlp installed and on your PATH?", e);
        }
        List<String> output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.toList());
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("yt-dlp failed with exit code " + exitCode
                    + ". Make sure ffmpeg is installed and the URL is valid.");
        }
        return parseYtDlpOutput(output);
    }

    static List<String> buildYtDlpCommand(String ytDlp, String url, Path workDir) {
        return List.of(ytDlp,
                "--no-playlist",
                "--encoding", "utf-8",
                "--extract-audio", "--audio-format", "mp3",
                "--output", workDir.resolve("%(id)s.%(ext)s").toString(),
                "--no-simulate",
                "--print", "after_move:filepath",
                "--print", "after_move:title",
                "--print", "after_move:duration",
                "--", url);
    }

    static Download parseYtDlpOutput(List<String> output) throws IOException {
        List<String> lines = output.stream().map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        if (lines.size() < 3) {
            throw new IOException("Unexpected output from yt-dlp: " + output);
        }
        Path file = Path.of(lines.get(lines.size() - 3));
        if (!Files.isRegularFile(file)) {
            throw new IOException("yt-dlp did not produce an audio file: " + file);
        }
        float duration;
        try {
            duration = Float.parseFloat(lines.get(lines.size() - 1));
        } catch (NumberFormatException e) {
            duration = 0;
        }
        return new Download(file, lines.get(lines.size() - 2), duration);
    }

    static String formatSeconds(float seconds) {
        long total = Math.round(seconds);
        return String.format("%d:%02d", total / 60, total % 60);
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            System.err.println("Warning: could not clean up " + dir + ": " + e.getMessage());
        }
    }
}
