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
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Mojo(name = "compile-flatbuffers", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class FlatbuffersMojo extends AbstractMojo {

    private static final String USER_HOME = "user.home";
    private static final String FB_DIR = ".flatbuffers";
    private static final String FLATBUFFERS_REPO = "https://github.com/google/flatbuffers.git";
    private static final String DEFAULT_TAG = "1.12.0";

    @Parameter(property = "version")
    String version;

    @Parameter(property = "flatbuffersUrl")
    String flatbuffersUrl;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        try {
            String home = getUserHomeDirectory();
            File fbHome = ensureFlatbuffersDirectory(home);
            Git repo = ensureFlatbuffersRepository(fbHome);

            String version = version();
            getLog().info("Required version of flatc is " + version);

            if (!flatcCompilerMatchesVersion(version, fbHome)) {
                completelyClean(fbHome);
                checkoutTag(repo, version);
                runShellCommand("cmake .", fbHome, s -> getLog().info(s));
                runShellCommand("make", fbHome, s -> getLog().info(s));
            }
        } catch (FBRuntimeException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private String flatbuffersUrl() {
        return flatbuffersUrl == null ? FLATBUFFERS_REPO : flatbuffersUrl;
    }

    private String version() {
        return version == null ? DEFAULT_TAG : version;
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
            String url = flatbuffersUrl();
            getLog().info("Cloning flatbuffers repository from " + url + ".");
            return Git.cloneRepository()
                    .setURI(flatbuffersUrl())
                    .setDirectory(dir)
                    .call();
        } catch (GitAPIException e) {
            throw new FBRuntimeException("Could not clone repository " + FLATBUFFERS_REPO + ".", e);
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
}