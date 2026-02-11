package org.jboss.pnc.rpmbuilderplugin;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

import javax.inject.Inject;

import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jboss.pnc.mavenmanipulator.core.impl.Version;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import io.smallrye.common.process.ProcessBuilder;

/**
 * Run rpmbuild and package the results.
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE)
public class RPMBuilder extends BaseMojo {

    @Parameter(defaultValue = "${mojoExecution}")
    protected MojoExecution mojoExecution;

    @Inject
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
    @Parameter(defaultValue = "true", property = "attachZip")
    private boolean attachZip = true;

    /**
     * Whether to generate a changeLog. For example:
     *
     * <pre>
     * {@code
     * <changeLog>
     *    <generate>true</generate>
     *    <email>my-email@arandomcompany.com</email>
     *    <message> - MyMessageSuffix</message
     * </changeLog>
     * }</pre>
     */
    @Parameter(property = "changeLog")
    private Changelog changeLog;

    /**
     * Whether to unpack(install) any noarch RPMs found in the <code>${project.build.directory}/dependency</code>
     * directory
     */
    @Parameter(defaultValue = "false", property = "installRPMs")
    private boolean installRPMs = false;

    /**
     * Custom extra macros to pass through. For example:
     *
     * <pre>
     * {@code
     * <macros>
     *    <dist>.el8eap</dist>
     *    <scl>eap8</scl>
     * </macros>
     * }</pre>
     */
    @Parameter(property = "macros")
    private Map<String, String> macros = new HashMap<>();

    public void execute()
            throws MojoExecutionException {

        checkForUnknownParameters();

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

        if (installRPMs) {
            File rpmDirectory = new File(outputDirectory, "dependency/noarch");

            if (!rpmDirectory.exists()) {
                throw new MojoExecutionException("Configured to install RPMs but no RPMs found in " + rpmDirectory);
            }
            try (Stream<Path> walk = Files.walk(rpmDirectory.toPath(), 1)) {
                List<Path> rpms = walk.filter(f -> f.getFileName().toString().endsWith(".noarch.rpm")).toList();
                if (rpms.isEmpty()) {
                    throw new MojoExecutionException("Configured to install RPMs but no RPMs found in " + rpmDirectory);
                }
                for (Path rpm : rpms) {
                    getLog().info("Extracting rpm " + rpm + " using rpm2cpio/cpio");
                    AtomicReference<Integer> exitCode = new AtomicReference<>(0);
                    List<String> args = new ArrayList<>();
                    args.add("-idmuv");
                    args.add("--quiet");
                    args.add("-D");
                    args.add("/");

                    ProcessBuilder.newBuilder("rpm2cpio")
                            .directory(rpmDirectory.toPath())
                            .arguments(rpm.getFileName().toString())
                            .exitCodeChecker(ec -> {
                                exitCode.set(ec);
                                return true;
                            })
                            .output()
                            .pipeTo(Path.of("/usr/bin/cpio"))
                            .arguments(args)
                            .output()
                            .consumeLinesWith(8192, getLog()::info)
                            .error()
                            .redirect()
                            .run();

                    if (exitCode.get() != 0) {
                        getLog().error("Error building RPM");
                        throw new MojoExecutionException("Process exited with code " + exitCode.get());
                    }
                }
            } catch (IOException e) {
                throw new MojoExecutionException(e);
            }
        }

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

            List<String> args = new ArrayList<>();
            args.add("--define=_topdir " + workingDirectory.getAbsolutePath());
            args.add("--define=_sourcedir " + workingDirectory.getAbsolutePath());
            args.add("--define=_rpmdir " + outputDirectory.getAbsolutePath());
            args.add("--define=_srcrpmdir " + outputDirectory.getAbsolutePath());
            args.add("--define=_specdir " + specDir.getAbsolutePath());
            args.add("--define=_builddir " + buildDir.getAbsolutePath());
            macros.forEach((key, value) -> args.add("--define=" + key + " " + value));
            args.add("-ba");
            args.add(targetSpecFile.toAbsolutePath().toString());

            // Change delimiter for shell copying/debugging.
            getLog().info(
                    "About to execute:\trpmbuild "
                            + args.stream().map(a -> {
                                if (a.contains("=")) {
                                    return a.replaceAll("=", "='") + "'";
                                } else {
                                    return a;
                                }
                            }).collect(Collectors.joining(" ")));

            AtomicReference<Integer> exitCode = new AtomicReference<>(0);
            ProcessBuilder.newBuilder("rpmbuild")
                    .directory(workingDirectory.toPath())
                    .arguments(args)
                    .exitCodeChecker(ec -> {
                        exitCode.set(ec);
                        return true;
                    })
                    .output()
                    .consumeLinesWith(8192, getLog()::info)
                    .error()
                    .redirect()
                    .run();

            if (exitCode.get() != 0) {
                getLog().error("Error building RPM");
                throw new MojoExecutionException("Process exited with code " + exitCode.get());
            }

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
        } catch (IOException e) {
            throw new MojoExecutionException(e);
        }
    }

    /**
     * Mostly sourced from <code>org.apache.maven.lifecycle.internal.DefaultMojoExecutionConfigurator</code>.
     *
     * @throws MojoExecutionException if there are unknown parameters in the XML configuration.
     */
    private void checkForUnknownParameters() throws MojoExecutionException {
        var plugin = project.getBuildPlugins()
                .stream()
                .filter(p -> p.equals(mojoExecution.getPlugin()))
                .findFirst();
        if (plugin.isEmpty()) {
            plugin = project.getPluginManagement()
                    .getPlugins()
                    .stream()
                    .filter(p -> p.equals(mojoExecution.getPlugin()))
                    .findFirst();
        }
        if (plugin.isPresent()) {
            Xpp3Dom pomConfiguration;
            PluginExecution pluginExecution = null;
            for (PluginExecution execution : plugin.get().getExecutions()) {
                if (execution.getId().equals(mojoExecution.getExecutionId())) {
                    pluginExecution = execution;
                }
            }
            if (pluginExecution != null) {
                pomConfiguration = (Xpp3Dom) pluginExecution.getConfiguration();
            } else {
                pomConfiguration = (Xpp3Dom) plugin.get().getConfiguration();
            }
            Set<String> parametersNamesAll = Optional
                    .ofNullable(mojoExecution.getMojoDescriptor().getPluginDescriptor())
                    .map(PluginDescriptor::getMojos)
                    .orElseGet(Collections::emptyList)
                    .stream()
                    .filter(m -> m.getParameters() != null)
                    .flatMap(m -> m.getParameters().stream())
                    .flatMap(parameter -> Stream.of(parameter.getName()))
                    .collect(Collectors.toSet());
            Set<String> unknownParameters = Arrays.stream(pomConfiguration.getChildren())
                    .map(Xpp3Dom::getName)
                    .filter(name -> !parametersNamesAll.contains(name))
                    .collect(Collectors.toSet());

            if (!unknownParameters.isEmpty()) {
                getLog().error("Found unknown parameter(s) in configuration " + unknownParameters);
                throw new MojoExecutionException("Unknown parameters " + unknownParameters);
            }
        }
    }

    /**
     * A wrapper class to encapsulate changelog generation information.
     */
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
