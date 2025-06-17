package org.jboss.pnc;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.slf4j.event.Level;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.OutputStreamLogDispatcher;
import ch.vorburger.exec.OutputStreamType;
import groovy.lang.GroovyShell;
import groovy.lang.Script;

/**
 * Run rpmbuild and package the results.
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE)
public class RPMBuilder extends AbstractMojo {

    @Component
    private MavenProject project;

    @Parameter(defaultValue = "${project.basedir}", property = "workingDirectory", required = true, readonly = true)
    private File workingDirectory;

    @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true, readonly = true)
    private File outputDirectory;

    /**
     * Custom groovy script to run against the spec file.
     */
    @Parameter(property = "groovyPatch")
    private String groovyPatch;

    /**
     * Whether to skip the plugin
     */
    @Parameter(defaultValue = "false", property = "skip")
    private boolean skip;

    /**
     * Custom extra macros to pass through. For example:
     * <macros>
     * <dist>.el8eap</dist>
     * <scl>eap8</scl>
     * </macros>
     */
    @Parameter(property = "macros")
    private Map<String, String> macros = new HashMap<>();

    public void execute()
            throws MojoExecutionException {

        if (skip) {
            getLog().info("Skipping RPM plugin");
            return;
        }
        File buildDir = new File(outputDirectory, "build");
        File specDir = new File(outputDirectory, "spec");
        //noinspection ResultOfMethodCallIgnored
        buildDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        specDir.mkdirs();

        try (Stream<Path> walk = Files.walk(workingDirectory.toPath(), 1)) {

            List<Path> specFiles = walk.filter(f -> f.getFileName().toString().endsWith(".spec")).toList();
            if (specFiles.size() != 1) {
                throw new MojoExecutionException(
                        "Incorrect number of spec files found (" + specFiles.size() + ") " + specFiles);
            }
            Path specFile = specFiles.get(0);
            Path targetSpecFile = specDir.toPath().resolve(specFile.toFile().getName());
            Files.copy(specFile, targetSpecFile, StandardCopyOption.REPLACE_EXISTING);

            if (isNotEmpty(groovyPatch)) {
                getLog().info("Using groovy script: " + groovyPatch);
                final GroovyShell shell = new GroovyShell();
                final Script script = shell.parse(groovyPatch);
                script.run();
            }

            ManagedProcessBuilder pb = new ManagedProcessBuilder("rpmbuild")
                    .addArgument("--define=_topdir " + workingDirectory.getAbsolutePath(), false)
                    .addArgument("--define=_sourcedir " + workingDirectory.getAbsolutePath(), false)
                    .addArgument("--define=_rpmdir " + outputDirectory.getAbsolutePath(), false)
                    .addArgument("--define=_srcrpmdir " + outputDirectory.getAbsolutePath(), false)
                    .addArgument("--define=_specdir " + specDir.getAbsolutePath(), false)
                    .addArgument("--define=_builddir " + buildDir.getAbsolutePath(), false);

            macros.forEach((key, value) -> pb.addArgument("--define=" + key + " " + value, false));

            pb.addArgument("-ba")
                    .addArgument(targetSpecFile.toAbsolutePath().toString())
                    .setOutputStreamLogDispatcher(new OutputStreamLogDispatcher() {
                        @Override
                        public Level dispatch(OutputStreamType o, String i) {
                            return Level.INFO;
                        }
                    })
                    .setWorkingDirectory(workingDirectory);

            ManagedProcess p = pb.build().start();
            int result = p.waitForExit();
            if (result != 0) {
                throw new MojoExecutionException("Process exited with code " + result);
            }

            final Collection<Path> paths = new ArrayList<>();
            Files.walkFileTree(outputDirectory.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (file.getFileName().toString().toLowerCase().endsWith(".rpm")) {
                        paths.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            File output = new File(outputDirectory, project.getArtifactId() + "-" + project.getVersion() + "-bin.zip");
            try (final OutputStream out = Files.newOutputStream(output.toPath());
                    final ZipArchiveOutputStream archive = new ArchiveStreamFactory()
                            .createArchiveOutputStream(ArchiveStreamFactory.ZIP, out)) {
                archive.setMethod(ZipEntry.DEFLATED);
                archive.setLevel(Deflater.BEST_COMPRESSION);

                for (Path path : paths) {
                    String entryName = FilenameUtils
                            .normalize(outputDirectory.toPath().relativize(path).toString(), true);
                    archive.putArchiveEntry(new ZipArchiveEntry(path.toFile(), entryName));
                    IOUtils.copy(Files.newInputStream(path), archive);
                    archive.closeArchiveEntry();
                }
            }
            project.getArtifact().setFile(output);

        } catch (IOException | ArchiveException e) {
            throw new MojoExecutionException(e);
        }

    }
}
