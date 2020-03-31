package com.sequsoft.maven.plugins.flatbuffers;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Mojo(name = "compile-flatbuffers", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class FlatbuffersMojo extends AbstractMojo {

    private static final String USER_HOME = "user.home";
    private static final String FB_DIR = ".flatbuffers";
    private static final String FLATBUFFERS_REPO = "https://github.com/google/flatbuffers.git";
    private static final String DEFAULT_VERSION = "1.12.0";
    private static final String DEFAULT_DESTINATION = "target/generated-sources";
    private static final Set<String> ALLOWED_GENERATORS = new HashSet<String>() {{
        add("mutable");
        add("generated");
        add("nullable");
        add("all");
    }};

    /**
     * The version of flatbuffers to be used. Optional: default is 1.12.0.
     */
    @Parameter(property = "version", defaultValue = DEFAULT_VERSION)
    String version;

    /**
     * The url to Google's flatbuffers repository. Optional: default is https://github.com/google/flatbuffers.git.
     */
    @Parameter(property = "flatbuffersUrl", defaultValue = FLATBUFFERS_REPO)
    String flatbuffersUrl;

    /**
     * A list of source schemas to be compiled by flatc. Required - at least one value must be provided.
     */
    @Parameter(property = "sources")
    List<String> sources;

    /**
     * The destination directory for compiled files. Optional, default is target/generated-sources.
     */
    @Parameter(property = "destination", defaultValue = DEFAULT_DESTINATION)
    String destination;

    /**
     * A list of directories that will be search for schemas to include during compilation. Optional.
     */
    @Parameter(property = "includes")
    List<String> includes;

    /**
     * A list of generator options, from the allowed list: mutable, generated, nullable and all. Optional.
     */
    @Parameter(property = "generators")
    Set<String> generators;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        try {
            String home = getUserHomeDirectory();
            File fbHome = ensureFlatbuffersDirectory(home);
            Git repo = ensureFlatbuffersRepository(fbHome);

            getLog().info("SOURCES = " + sources);
            getLog().info("GENERATORS = " + generators);

            getLog().info("Required version of flatc is " + version);

            if (!flatcCompilerMatchesVersion(version, fbHome)) {
                completelyClean(fbHome);
                checkoutTag(repo, version);
                runShellCommand("cmake .", fbHome, s -> getLog().info(s));
                runShellCommand("make", fbHome, s -> getLog().info(s));
            }

            performFlatcCompilation();
        } catch (FBRuntimeException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private String getUserHomeDirectory() {
        String home = System.getProperty(USER_HOME);
        if (home == null || home.length() == 0) {
            throw new FBRuntimeException("No user home directory could be found.");
        }
        getLog().debug("User home directory = " + home);

        return home;
    }

    private File ensureFlatbuffersDirectory(String home) {
        try {
            Path path = Paths.get(home, FB_DIR);
            if (!Files.exists(path)) {
                Files.createDirectory(path);
                getLog().info("Created " + FB_DIR + " directory.");
            } else {
                getLog().info("Directory " + FB_DIR + " already exists.");
            }

            return new File(path.toString());
        } catch (IOException e) {
            throw new FBRuntimeException(e.getMessage());
        }
    }

    private Git ensureFlatbuffersRepository(File dir) {
        try {
            getLog().info("Opening flatbuffers git repository in " + dir.toString() + ".");
            return Git.open(dir);
        } catch(IOException e) {
            return cloneFlatbuffersRepository(dir);
        }
    }

    private Git cloneFlatbuffersRepository(File dir) {
        try {
            getLog().info("Cloning flatbuffers repository from " + flatbuffersUrl + ".");
            return Git.cloneRepository()
                    .setURI(flatbuffersUrl)
                    .setDirectory(dir)
                    .call();
        } catch (GitAPIException e) {
            throw new FBRuntimeException("Could not clone repository " + flatbuffersUrl + ".", e);
        }
    }

    private void checkoutTag(Git repo, String tag) {
        try {
            getLog().info("Checking out tag v" + tag + ".");
            repo.checkout().setName("v" + tag).call();
        } catch (GitAPIException e) {
            throw new FBRuntimeException("Could not checkout tag v" + tag + ".", e);
        }
    }

    private boolean flatcCompilerMatchesVersion(String version, File dir) {
        try {
            String[] captor = new String[1];
            runShellCommand("./flatc --version", dir, s -> captor[0] = s);

            if (captor[0] == null) {
                getLog().info("No flatc version found - will need to compile...");
                return false;
            } else if (captor[0].contains(version)) {
                getLog().info("flatc version " + version + " is correct - no need to compile.");
                return true;
            } else {
                getLog().info("flatc version is not " + version + " - will need to compile...");
                return false;
            }

        } catch (FBRuntimeException e) {
            getLog().info("No flatc version found - will need to compile...");
            return false;
        }
    }

    boolean isWindows = System.getProperty("os.name")
            .toLowerCase().startsWith("windows");

    private void runShellCommand(String command, File dir, Consumer<String> consumer) {
        int exitCode;
        try {
            Process process = Runtime.getRuntime().exec(command,null, dir);

            StreamConsumer streamConsumer = new StreamConsumer(process.getInputStream(), consumer);

            Executors.newSingleThreadExecutor().submit(streamConsumer);

            exitCode = process.waitFor();
        } catch(Exception e) {
            throw new FBRuntimeException("Process '" + command + "' threw exception: " + e.getMessage(), e);
        }

        if (exitCode != 0) {
            throw new FBRuntimeException("Process " + command + " exited with non-zero status");
        }
    }

    private void completelyClean(File dir) {
        try {
            // JGit can't do complete clean!! Need to use command line...
            getLog().info("Completely clean the git repository prior to rebuild.");
            runShellCommand("git clean -d -f -x", dir, s -> getLog().info(s));
        } catch (Exception e) {
            throw new FBRuntimeException("Could not clean repository.", e);
        }
    }

    private void validateGenerators() {
        Set<String> generators = new HashSet<>();

        if (this.generators != null) {
            for (String requestedGenerator : this.generators) {
                if (ALLOWED_GENERATORS.contains(requestedGenerator)) {
                    generators.add(requestedGenerator);
                } else {
                    throw new FBRuntimeException("Generator " + requestedGenerator + " is not valid.");
                }
            }
        }
    }

    private void validateIncludes() {
        if (includes == null) {
            includes = new ArrayList<>();
            return;
        }

        for (String include : includes) {
            if (!(new File(include).exists())) {
                throw new FBRuntimeException("include directory " + include + " does not exist.");
            }
        }
    }

    private void validateSources() {
        if (sources == null || sources.size() == 0) {
            throw new FBRuntimeException("At least once source must be provided to generate from.");
        }

        if (sources != null) {
            for (String requestedSource : sources) {
                if (!(new File(requestedSource).exists())) {
                    throw new FBRuntimeException("source file " + requestedSource + " does not exist.");
                }
            }
        }
    }

    private void performFlatcCompilation() {
        validateGenerators();
        validateIncludes();
        validateSources();

        StringBuilder flatc = new StringBuilder(getUserHomeDirectory());
        flatc.append("/");
        flatc.append(FB_DIR);
        flatc.append("/flatc --java -o ");
        flatc.append(destination);

        if (generators.size() > 0) {
            for(String generator : generators) {
                flatc.append(" --gen-");
                flatc.append(generator);
            }
        }

        if (includes.size() > 0) {
            for (String include : includes) {
                flatc.append(" -I ");
                flatc.append((include));
            }
        }

        flatc.append(" ");
        flatc.append(String.join(" ", sources));

        getLog().info("generate java sources using: " + flatc.toString());
        runShellCommand(flatc.toString(), new File("."), s -> getLog().info(s));
        getLog().info("Class generation completed successfully!");
    }
}