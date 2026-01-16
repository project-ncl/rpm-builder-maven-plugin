package org.jboss.pnc.rpmbuilderplugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

public abstract class BaseMojo extends AbstractMojo {

    @Inject
    protected MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true, readonly = true)
    protected File outputDirectory;

    protected List<File> findRPMs(Path searchDirectory) throws IOException {
        final List<File> rpms = new ArrayList<>();
        final File dependencyDirectory = new File(outputDirectory, "dependency");
        Files.walkFileTree(searchDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                // If RPMs have been downloaded via https://maven.apache.org/plugins/maven-dependency-plugin/unpack-mojo.html the
                // default directory is target/dependency so we should ignore that.
                if (!file.toString().startsWith(dependencyDirectory.toString())
                        && file.getFileName().toString().toLowerCase().endsWith(".rpm")) {
                    rpms.add(file.toFile());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return rpms;
    }
}
