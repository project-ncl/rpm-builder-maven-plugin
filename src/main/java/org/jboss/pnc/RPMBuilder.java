package org.jboss.pnc;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectHelper;
import org.commonjava.maven.ext.core.impl.Version;
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
public class RPMBuilder extends BaseMojo {

    @Component
    private MavenProjectHelper projectHelper;

    @Parameter(defaultValue = "${project.basedir}", property = "workingDirectory", required = true, readonly = true)
    private File workingDirectory;

    /**
     * Custom groovy script to run against the spec file.
     */
    @Parameter(property = "groovyPatch")
    private String groovyPatch;

    /**
     * Whether to attach the RPMs in a zip
     */
    @Parameter(defaultValue = "false", property = "attachZip")
    private boolean attachZip;

    @Parameter(property = "changeLog")
    private Changelog changeLog;

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

        File buildDir = new File(outputDirectory, "build");
        File specDir = new File(outputDirectory, "spec");
        //noinspection ResultOfMethodCallIgnored
        buildDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        specDir.mkdirs();

        String serial = Integer.toString(Version.getIntegerBuildNumber(project.getVersion()));
        String wrappedBuild = project.getProperties().getProperty("wrappedBuild");
        String meadRel = ".1";
        String meadVersion = null;
        String meadAlpha = null;
        if (wrappedBuild == null) {
            getLog().error(
                    "Unable to find wrappedBuild property in project properties. Define this property to denote the version of the build to be wrapped inside the RPM");
            if (changeLog != null && changeLog.generate) {
                throw new MojoExecutionException("Unable to find wrappedBuild property");
            }
        } else {
            meadVersion = Version.getMMM(wrappedBuild);
            meadAlpha = Version.getQualifierWithDelim(wrappedBuild).replace("-", "_");
        }
        getLog().info(
                "With project " + project.getName() + " found project.version " + project.getVersion()
                        + " and properties wrappedBuild=" + wrappedBuild
                        + " meadalpha=" + meadAlpha + " meadrel=" + meadRel
                        + " meadversion=" + meadVersion + " serial=" + serial);

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
                script.setProperty("wrappedBuild", wrappedBuild);
                // Its possible there might be no delimiter so set the macro to 'empty'
                script.setProperty("meadalpha", isEmpty(meadAlpha) ? "%{nil}" : meadAlpha);
                script.setProperty("meadrel", meadRel);
                script.setProperty("meadversion", meadVersion);
                script.setProperty("serial", serial);
                script.run();
            }
            if (changeLog != null && changeLog.generate) {
                LocalDateTime dt = LocalDateTime.now();
                String title = "* " + dt.format(DateTimeFormatter.ofPattern("E MMM dd yyyy")) + " "
                        + changeLog.email + " - "
                        + meadVersion + "-"
                        + serial
                        + meadAlpha
                        + meadRel;

                getLog().info("Generating changelog with title '" + title + "' and message " + changeLog.message);

                List<String> specLines = Files.readAllLines(targetSpecFile);
                List<String> newSpecLines = new ArrayList<>();
                specLines.forEach(line -> {
                    if (line.startsWith("%changelog")) {
                        newSpecLines.add(line);
                        newSpecLines.add(title);
                        newSpecLines.add(changeLog.message);
                        newSpecLines.add("");
                    } else {
                        newSpecLines.add(line);
                    }
                });
                Files.write(targetSpecFile, newSpecLines, Charset.defaultCharset());
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

            // TODO: Remove?
            if (attachZip) {
                List<File> rpms = findRPMs(outputDirectory.toPath());
                File output = new File(outputDirectory, project.getArtifactId() + "-" + project.getVersion() + ".zip");
                try (final OutputStream out = Files.newOutputStream(output.toPath());
                        final ZipArchiveOutputStream archive = new ArchiveStreamFactory()
                                .createArchiveOutputStream(ArchiveStreamFactory.ZIP, out)) {
                    archive.setMethod(ZipEntry.DEFLATED);
                    archive.setLevel(Deflater.BEST_COMPRESSION);

                    for (File path : rpms) {
                        String entryName = FilenameUtils
                                .normalize(outputDirectory.toPath().relativize(path.toPath()).toString(), true);
                        archive.putArchiveEntry(new ZipArchiveEntry(path, entryName));
                        IOUtils.copy(Files.newInputStream(path.toPath()), archive);
                        archive.closeArchiveEntry();
                    }
                }
                getLog().info("Attaching " + output.getName() + " to project containing " + rpms.size() + " rpms.");
                // Attach the assembled zip file as secondary artifact.
                projectHelper.attachArtifact(project, "zip", output);
            }
            // Attach the modified spec file as the primary output.
            project.getArtifact().setFile(targetSpecFile.toFile());
        } catch (IOException | ArchiveException e) {
            throw new MojoExecutionException(e);
        }

    }

    public static class Changelog {
        public boolean generate;
        public String email = "project-ncl@redhat.com";
        public String message = "- New Release";

        public Changelog() {
        }

        @Override
        public String toString() {
            return "Changelog{" +
                    "generate=" + generate +
                    ", email='" + email + '\'' +
                    ", message='" + message + '\'' +
                    '}';
        }
    }
}
