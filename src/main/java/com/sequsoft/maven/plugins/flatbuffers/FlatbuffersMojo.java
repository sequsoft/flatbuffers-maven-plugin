package com.sequsoft.maven.plugins.flatbuffers;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

@Mojo(name = "compile-flatbuffers", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class FlatbuffersMojo extends AbstractMojo {

    private static final String USER_HOME = "user.home";
    private static final String FB_DIR = ".flatbuffers";
    private static final String FLATBUFFERS_REPO = "https://github.com/google/flatbuffers.git";
    private static final String DEFAULT_TAG = "v1.12.0";

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    MavenProject project;

    @Parameter(property = "branch")
    String tag;

    @Parameter(property = "flatbuffersUrl")
    String flatbuffersUrl;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        try {
            String home = getUserHomeDirectory();
            String fbHome = ensureFlatbuffersDirectory(home);
            Git repo = ensureFlatbuffersRepository(new File(fbHome));
            checkoutTag(repo, tag());
            runShellCommand("cmake .", new File(fbHome));
            runShellCommand("make", new File(fbHome));
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

    private String ensureFlatbuffersDirectory(String home) {
        try {
            Path path = Paths.get(home, FB_DIR);
            if (!Files.exists(path)) {
                Files.createDirectory(path);
                getLog().info("Created " + FB_DIR + " directory.");
            } else {
                getLog().info("Directory " + FB_DIR + " already exists.");
            }

            return path.toString();
        } catch (IOException e) {
            throw new FBRuntimeException(e.getMessage());
        }
    }

    private Git ensureFlatbuffersRepository(File dir) {
        try {
            getLog().info("Opening flatbuffers git repository in " + dir.toString() + ".");
            return Git.open(dir);
        } catch(IOException e) {
            return closeFlatbuffersRepository(dir);
        }
    }

    private String flatbuffersUrl() {
        return flatbuffersUrl == null ? FLATBUFFERS_REPO : flatbuffersUrl;
    }

    private String tag() {
        return tag == null ? DEFAULT_TAG : tag;
    }

    private Git closeFlatbuffersRepository(File dir) {
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
            repo.checkout().setName(tag).call();
        } catch (GitAPIException e) {
            throw new FBRuntimeException("Could not checkout tag " + tag + ".", e);
        }
    }

    boolean isWindows = System.getProperty("os.name")
            .toLowerCase().startsWith("windows");

    private void runShellCommand(String command, File dir) {
        int exitCode = 0;
        try {
            Process process = Runtime.getRuntime().exec(command,null, dir);

            StreamConsumer streamConsumer = new StreamConsumer(process.getInputStream(), s -> getLog().info(s));

            Executors.newSingleThreadExecutor().submit(streamConsumer);

            exitCode = process.waitFor();
        } catch(Exception e) {
            throw new FBRuntimeException("Process " + command + " threw exception: " + e.getMessage(), e);
        }

        if (exitCode != 0) {
            getLog().info("Exit code: " + exitCode);
            throw new FBRuntimeException("Process " + command + " exited with non-zero status");
        }

        /*if (isWindows) {
            process = Runtime.getRuntime()
                    .exec(String.format("cmd.exe /c dir %s", homeDirectory));
        } else {
            process = Runtime.getRuntime()
                    .exec(String.format("sh -c ls %s", homeDirectory));
        }
        StreamGobbler streamGobbler =
                new StreamGobbler(process.getInputStream(), System.out::println);
        Executors.newSingleThreadExecutor().submit(streamGobbler);
        int exitCode = process.waitFor();
        assert exitCode == 0;*/
    }
}