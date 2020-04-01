package com.sequsoft.maven.plugins.flatbuffers;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.stream.LogOutputStream;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
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
     * The maven project, so that this plugin can add a generated sources directory.
     */
    @Parameter( readonly = true, defaultValue = "${project}" )
    private MavenProject project;

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
            File fbHome = ensureFlatbuffersDirectory();

            getLog().info("Required version of flatc is " + version);

            if (!flatcCompilerMatchesVersion(version, fbHome)) {
                Git repo = ensureFlatbuffersRepository(fbHome);
                completelyClean(fbHome);
                checkoutTag(repo, version);
                runShellCommand("cmake .", fbHome, s -> getLog().info(s));
                runShellCommand("make", fbHome, s -> getLog().info(s));
            }

            performFlatcCompilation();
            addGeneratedSources();
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

    private File ensureFlatbuffersDirectory() {
        try {
            Path path = Paths.get(getUserHomeDirectory(), FB_DIR);
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
            deleteFlatbuffersDirectoryIfExists(dir);
            return cloneFlatbuffersRepository(dir);
        }
    }

    private void deleteFlatbuffersDirectoryIfExists(File dir) {
        getLog().info("Deleting the flatbuffers directory to ensure clean state");
        try {
            if (dir.exists()) {
                Files.walk(dir.toPath())
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (IOException e) {
            throw new FBRuntimeException("Failed to delete flatbuffers directory");
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

    private String flatcExecutablePath() {
        return Paths.get(getUserHomeDirectory(), FB_DIR, "flatc").toString();
    }

    private boolean flatcCompilerMatchesVersion(String version, File dir) {
        try {
            String[] captor = new String[1];
            String cmd = flatcExecutablePath() + " --version";
            runShellCommand(cmd, dir, s -> captor[0] = s);

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

    private void runShellCommand(String command, File dir, Consumer<String> consumer) {
        ProcessExecutor executor = new ProcessExecutor()
                .command(command.split(" "))
                .directory(dir)
                .redirectOutput(new LogOutputStream() {
                    @Override
                    protected void processLine(String s) {
                        consumer.accept(s);
                    }
                });

        try {
            ProcessResult result = executor.execute();
            int exitCode = result.getExitValue();
            if (exitCode != 0) {
                getLog().info("Process exit code was: " + exitCode);
                throw new FBRuntimeException("Process " + command + " exited with non-zero status");
            }
        } catch (IOException | InterruptedException | TimeoutException e) {
            getLog().info("Process threw exception: " + e.getMessage());
            throw new FBRuntimeException("Process '" + command + "' threw exception: " + e.getMessage(), e);
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

        StringBuilder cmd = new StringBuilder(flatcExecutablePath());
        cmd.append(" --java -o ");
        cmd.append(destination);

        if (generators.size() > 0) {
            for(String generator : generators) {
                cmd.append(" --gen-");
                cmd.append(generator);
            }
        }

        if (includes.size() > 0) {
            for (String include : includes) {
                cmd.append(" -I ");
                cmd.append((include));
            }
        }

        cmd.append(" ");
        cmd.append(String.join(" ", sources));

        getLog().info("generate java sources using: " + cmd.toString());
        runShellCommand(cmd.toString(), new File("."), s -> getLog().info(s));
        getLog().info("Class generation completed successfully!");
    }

    private void addGeneratedSources() {
        getLog().info("Generated source directory added to maven project: " + destination + ".");
        project.addCompileSourceRoot(destination);
    }
}