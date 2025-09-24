import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class PrepareMavenRelease
{
    private static final Set<String> MAVEN_RELEASE_ARTIFACTS = Set.of(
            "io.trino:trino-root:pom",
            "io.trino:trino-root:pom.asc",

            "io.trino:trino-jdbc:pom",
            "io.trino:trino-jdbc:jar",
            "io.trino:trino-jdbc:jar:sources",
            "io.trino:trino-jdbc:jar:javadoc",
            "io.trino:trino-jdbc:pom.asc",
            "io.trino:trino-jdbc:jar.asc",
            "io.trino:trino-jdbc:jar.asc:sources",
            "io.trino:trino-jdbc:jar.asc:javadoc",

            "io.trino:trino-spi:pom",
            "io.trino:trino-spi:jar",
            "io.trino:trino-spi:jar:sources",
            "io.trino:trino-spi:jar:javadoc",
            "io.trino:trino-spi:pom.asc",
            "io.trino:trino-spi:jar.asc",
            "io.trino:trino-spi:jar.asc:sources",
            "io.trino:trino-spi:jar.asc:javadoc"
    );

    private static final Path META_DIR = Path.of(".meta");
    private static final Path ARTIFACTS_MANIFEST = META_DIR.resolve("artifacts");
    private static final Path METADATA_MANIFEST = META_DIR.resolve("metadata");
    private static final Path REPO_PROPERTIES = META_DIR.resolve("repository.properties");

    record Arguments(boolean dryRun, Path stagingDir, String outputRepoName) { }

    record MavenCoordinate(String groupId, String artifactId, Optional<String> packaging, Optional<String> classifier, Optional<String> version)
    {
        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append(groupId)
                    .append(":")
                    .append(artifactId);
            packaging.ifPresent(v -> builder.append(":").append(v));
            classifier.ifPresent(v -> builder.append(":").append(v));
            version.ifPresent(v -> builder.append(":").append(v));
            return builder.toString();
        }
    }

    record Artifact(MavenCoordinate coordinate, Path path) { }

    record MetadataEntry(MavenCoordinate coordinate, Path path) { }

    enum Checksum
    {
        MD5("md5"),
        SHA1("sha1"),
        SHA256("sha256"),
        SHA512("sha512");

        private final String extension;

        Checksum(String extension)
        {
            this.extension = extension;
        }

        public String extension()
        {
            return extension;
        }
    }

    private static Arguments parseArguments(String[] args)
    {
        boolean dryRun = false;
        int index = 0;
        if (args.length > 0 && args[0].equals("--dry-run")) {
            dryRun = true;
            index++;
        }
        if (args.length - index != 2) {
            System.err.println("Usage: PrepareRelease [--dry-run] <staging-repo-path> <output-repo-name>");
            System.exit(1);
        }
        return new Arguments(dryRun, Paths.get(args[index]), args[index + 1]);
    }

    // e.g., io.trino:trino-matching:pom:477
    // e.g., io.trino:trino-matching:jar:sources:477
    // e.g., io.trino:trino-root
    private static MavenCoordinate parseCoordinates(String value)
    {
        String[] parts = value.split(":");

        if (parts.length < 2 || parts.length > 5) {
            throw new IllegalArgumentException("Invalid Maven coordinates: " + value);
        }

        String groupId = parts[0];
        String artifactId = parts[1];
        Optional<String> packaging = Optional.empty();
        Optional<String> classifier = Optional.empty();
        Optional<String> version = Optional.empty();
        if (parts.length == 3) {
            version = Optional.of(parts[2]);
        }
        else if (parts.length == 4) {
            packaging = Optional.of(parts[2]);
            version = Optional.of(parts[3]);
        }
        else if (parts.length == 5) {
            packaging = Optional.of(parts[2]);
            classifier = Optional.of(parts[3]);
            version = Optional.of(parts[4]);
        }

        return new MavenCoordinate(groupId, artifactId, packaging, classifier, version);
    }

    private static List<Artifact> loadArtifacts(Path path)
            throws IOException
    {
        return Files.readAllLines(path).stream()
                .map(line -> {
                    String[] parts = line.split("=", 2);
                    return new Artifact(parseCoordinates(parts[0]), Path.of(parts[1]));
                })
                .toList();
    }

    private static List<MetadataEntry> loadMetadata(Path path)
            throws IOException
    {
        return Files.readAllLines(path).stream()
                .map(line -> {
                    String[] parts = line.split("=", 2);
                    String[] key = parts[0].split("::");
                    String coordinates = key[0];
                    if (!key[1].equals("maven-metadata.xml")) {
                        throw new IllegalArgumentException("Unexpected entry: " + key[1]);
                    }

                    return new MetadataEntry(parseCoordinates(coordinates), Path.of(parts[1]));
                })
                .toList();
    }

    private static Properties loadRepoProperties(Path path)
            throws IOException
    {
        File file = path.toFile();

        try (FileReader reader = new FileReader(file)) {
            Properties properties = new Properties();
            properties.load(reader);
            return properties;
        }
    }

    private static Properties processProperties(Properties properties, String repoName)
    {
        Properties updated = new Properties();
        // copy properties from properties to updated
        // replace property "name" with repoName
        properties.forEach((key, value) -> {
            if (key.equals("name")) {
                updated.put(key, repoName);
            }
            else {
                updated.put(key, value);
            }
        });

        return updated;
    }

    private static List<MetadataEntry> processMetadata(List<MetadataEntry> metadata, Set<String> releaseArtifacts)
    {
        Set<MavenCoordinate> candidates = releaseArtifacts.stream()
                .map(PrepareMavenRelease::parseCoordinates)
                .map(value -> new MavenCoordinate(
                        value.groupId(),
                        value.artifactId(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()))
                .collect(Collectors.toSet());

        return metadata.stream()
                .filter(entry -> {
                    MavenCoordinate canonical = new MavenCoordinate(
                            entry.coordinate().groupId(),
                            entry.coordinate().artifactId(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());

                    return candidates.contains(canonical);
                })
                .toList();
    }

    private static List<Artifact> processArtifacts(List<Artifact> artifacts, Set<String> releaseModules)
    {
        return artifacts.stream()
                .filter(artifact -> {
                    MavenCoordinate canonical = new MavenCoordinate(
                            artifact.coordinate().groupId(),
                            artifact.coordinate().artifactId(),
                            artifact.coordinate().packaging(),
                            artifact.coordinate().classifier(),
                            Optional.empty());

                    return releaseModules.contains(canonical.toString());
                })
                .toList();
    }

    private static List<Checksum> parseChecksums(String algorithms)
    {
        return Stream.of(algorithms.split(","))
                .map(String::trim)
                .map(value -> switch (value) {
                    case "MD5" -> Checksum.MD5;
                    case "SHA-1" -> Checksum.SHA1;
                    case "SHA-256" -> Checksum.SHA256;
                    case "SHA-512" -> Checksum.SHA512;
                    default -> throw new IllegalArgumentException("Unknown checksum algorithm: " + value);
                })
                .toList();
    }

    private static String getExtension(String filename)
    {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex == -1) {
            return "";
        }
        return filename.substring(dotIndex + 1);
    }

    private static void copyArtifacts(Path inputPath, Path outputPath, List<Artifact> artifacts, List<MetadataEntry> metadata, List<Checksum> checksums, Set<String> skipChecksums, boolean dryRun)
            throws IOException
    {
        for (MetadataEntry entry : metadata) {
            copyArtifact(inputPath, outputPath, checksums, skipChecksums, entry.path(), dryRun);
        }

        for (Artifact artifact : artifacts) {
            copyArtifact(inputPath, outputPath, checksums, skipChecksums, artifact.path(), dryRun);
        }
    }

    private static void copyArtifact(Path inputPath, Path outputPath, List<Checksum> checksums, Set<String> skipChecksums, Path artifactPath, boolean dryRun)
            throws IOException
    {
        if (!skipChecksums.contains(getExtension(artifactPath.toString()))) {
            for (Checksum checksum : checksums) {
                Path source = inputPath.resolve(artifactPath + "." + checksum.extension());
                Path target = outputPath.resolve(artifactPath + "." + checksum.extension());
                System.out.println(source + " -> " + target);
                if (!dryRun) {
                    copyFile(target, source);
                }
            }
        }
        Path source = inputPath.resolve(artifactPath);
        Path target = outputPath.resolve(artifactPath);
        System.out.println(source + " -> " + target);
        if (!dryRun) {
            copyFile(target, source);
        }
    }

    private static void copyFile(Path target, Path source)
            throws IOException
    {
        File parent = target.getParent().toFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + target.getParent());
        }
        if (!parent.isDirectory()) {
            throw new IOException("Parent is not a directory: " + target.getParent());
        }

        Files.copy(source, target);
    }

    public static void main(String[] args)
            throws IOException
    {
        Arguments arguments = parseArguments(args);
        boolean dryRun = arguments.dryRun();
        Path outputDir = arguments.stagingDir().getParent().resolve(arguments.outputRepoName());

        Path artifactsPath = arguments.stagingDir().resolve(ARTIFACTS_MANIFEST);
        Path metadataPath = arguments.stagingDir().resolve(METADATA_MANIFEST);
        Path repoPropertiesPath = arguments.stagingDir().resolve(REPO_PROPERTIES);

        if (outputDir.toFile().exists() && !dryRun) {
            System.err.println("Output repository already exists: " + outputDir);
            System.exit(1);
        }

        if (outputDir.equals(arguments.stagingDir())) {
            System.err.println("Output repository must be different from staging repository");
            System.exit(1);
        }

        Properties properties = loadRepoProperties(repoPropertiesPath);
        List<Artifact> artifacts = loadArtifacts(artifactsPath);
        List<MetadataEntry> metadata = loadMetadata(metadataPath);

        List<Checksum> checksums = parseChecksums(properties.getProperty("checksumAlgorithmFactories"));
        Set<String> skipChecksums = Stream.of(properties.getProperty("omitChecksumsForExtensions", "").split(","))
                .map(value -> value.substring(1)) // skip leading ".")
                .collect(Collectors.toSet());

        Properties updatedProperties = processProperties(properties, arguments.outputRepoName());
        List<MetadataEntry> updatedMetadata = processMetadata(metadata, MAVEN_RELEASE_ARTIFACTS);
        List<Artifact> updatedArtifacts = processArtifacts(artifacts, MAVEN_RELEASE_ARTIFACTS);

        copyArtifacts(arguments.stagingDir(), outputDir, updatedArtifacts, updatedMetadata, checksums, skipChecksums, dryRun);

        if (!dryRun) {
            if (!outputDir.resolve(META_DIR).toFile().mkdirs()) {
                throw new IOException("Failed to create directory: " + outputDir.resolve(META_DIR));
            }
            writeRepoProperties(outputDir.resolve(REPO_PROPERTIES), updatedProperties, dryRun);
            writeMetadataManifest(outputDir.resolve(METADATA_MANIFEST), updatedMetadata, dryRun);
            writeArtifactsManifest(outputDir.resolve(ARTIFACTS_MANIFEST), updatedArtifacts, dryRun);
        }
    }

    private static void writeArtifactsManifest(Path path, List<Artifact> artifacts, boolean dryRun)
            throws IOException
    {
        try (PrintWriter out = new PrintWriter(new FileWriter(path.toFile()))) {
            for (Artifact entry : artifacts) {
                out.println(entry.coordinate() + "=" + entry.path());
            }
        }
    }

    private static void writeMetadataManifest(Path path, List<MetadataEntry> metadata, boolean dryRun)
            throws IOException
    {
        try (PrintWriter out = new PrintWriter(new FileWriter(path.toFile()))) {
            for (MetadataEntry entry : metadata) {
                out.println(entry.coordinate() + "::maven-metadata.xml=" + entry.path());
            }
        }
    }

    private static void writeRepoProperties(Path path, Properties properties, boolean dryRun)
            throws IOException
    {
        try (FileWriter out = new FileWriter(path.toFile())) {
            properties.store(out, "");
        }
    }
}
