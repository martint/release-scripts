/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReleaseGithub
{
    private final static Pattern CLI_PATTERN = Pattern.compile("trino-cli-(?<version>.*)-executable\\.jar");

    record Arguments(boolean dryRun, Path stagingPath, String tag) { }

    record Artifact(Path path, String name) { }

    private static Arguments parseArguments(String[] args)
    {
        boolean dryRun = false;
        int index = 0;
        if (args.length > 0 && args[0].equals("--dry-run")) {
            dryRun = true;
            index++;
        }
        if (args.length - index != 2) {
            System.err.println("Usage: PrepareRelease [--dry-run] <staging-repo-path> <tag>");
            System.exit(1);
        }
        return new Arguments(dryRun, Paths.get(args[index]), args[index + 1]);
    }

    public static void main(String[] args)
            throws IOException, InterruptedException
    {
        Arguments arguments = parseArguments(args);

        createRelease(arguments.tag(), arguments.dryRun());
        List<Artifact> artifacts = enumerateArtifacts(arguments.stagingPath(), arguments.dryRun());
        for (Artifact artifact : artifacts) {
            upload(artifact, arguments.tag(), arguments.dryRun());
        }
    }

    private static List<Artifact> enumerateArtifacts(Path repoPath, boolean dryRun)
            throws IOException
    {
        List<Artifact> artifacts = new ArrayList<>();

        // find all zip files
        try (var paths = Files.walk(repoPath)) {
            paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("trino-") && name.endsWith(".zip");
                    })
                    .forEach(path -> {
                        // file name looks like: trino-<artifact>-<version>.zip
                        // extract the name as "<artifact>", without version and extension
                        String name = path.getFileName().toString();
                        int firstDash = name.indexOf('-');
                        int lastDash = name.lastIndexOf('-');
                        artifacts.add(new Artifact(path, "plugins/" + name.substring(firstDash + 1, lastDash)));
                    });
        }

        try (var paths = Files.walk(repoPath)) {
            paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".tar.gz") && (
                                name.startsWith("trino-server-core") ||
                                        name.startsWith("trino-server") ||
                                        name.startsWith("trino-proxy"));
                    })
                    .forEach(path -> {
                        // file name looks like: trino-<artifact>-<version>.zip
                        // extract the name as "<artifact>", without version and extension
                        String name = path.getFileName().toString();
                        int firstDash = name.indexOf('-');
                        int lastDash = name.lastIndexOf('-');
                        artifacts.add(new Artifact(path, "servers/" + name.substring(firstDash + 1, lastDash)));
                    });
        }

        try (var paths = Files.walk(repoPath)) {
            paths.forEach(path -> {
                Matcher matcher = CLI_PATTERN.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    String version = matcher.group("version");

                    // copy CLI to temp path and rename to trino-cli-<version>
                    try {
                        // create temp directory to hold the renamed file
                        // copy file into tem directory and name it trino-cli-<version>
                        // use the path of the temp file for upload
                        Path temp = Files.createTempDirectory("trino-cli-");
                        temp.toFile().deleteOnExit();

                        Path tempFile = temp.resolve("trino-cli-" + version);
                        tempFile.toFile().deleteOnExit();

                        if (!dryRun) {
                            Files.copy(path, tempFile);
                        }

                        artifacts.add(new Artifact(tempFile, "clients/trino-cli-" + version));
                    }
                    catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
        return artifacts;
    }

    private static void createRelease(String tag, boolean dryRun)
            throws IOException, InterruptedException
    {
        System.out.println("Creating release " + tag);
        if (!dryRun) {
            ProcessBuilder processBuilder = new ProcessBuilder("gh", "release", "create", tag, "-R", "trinodb/trino", "--notes", "See https://trino.io/docs/current/release/release-" + tag + ".html")
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process process = processBuilder.start();
            if (process.waitFor() != 0) {
                throw new RuntimeException("Failed to create release " + tag);
            }
        }
    }

    private static void upload(Artifact artifact, String tag, boolean dryRun)
            throws IOException, InterruptedException
    {
        Path path = artifact.path().toAbsolutePath();
        System.out.println("Uploading " + path + " -> " + artifact.name());

        // gh release upload <tag> <file>#<asset name> --clobber
        if (!dryRun) {
            ProcessBuilder processBuilder = new ProcessBuilder("gh", "release", "upload", tag, path + "#" + artifact.name(), "-R", "trinodb/trino")
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process process = processBuilder.start();
            if (process.waitFor() != 0) {
                throw new RuntimeException("Failed to upload " + path);
            }
        }
    }
}
