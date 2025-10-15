package org.jboss.pnc;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.internal.impl.DefaultRepositoryLayoutProvider;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.checksum.ChecksumAlgorithmFactorySelector;
import org.eclipse.aether.spi.connector.layout.RepositoryLayoutProvider;

/**
 * Deploy the built rpms to a specified repository.
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
public class DeployMojo extends BaseMojo {

    private static final Pattern ALT_LEGACY_REPO_SYNTAX_PATTERN = Pattern.compile("(.+?)::(.+?)::(.+)");

    private static final Pattern ALT_REPO_SYNTAX_PATTERN = Pattern.compile("(.+?)::(.+)");

    @Inject
    protected MavenSession session;

    @Inject
    private RepositorySystem repositorySystem;

    @Inject
    private ChecksumAlgorithmFactorySelector checksumAlgorithmFactorySelector;

    /**
     * The DefaultDeployer uses DefaultRepositoryConnectionProvider which uses BasicRepositoryConnectorFactory
     * which uses DefaultRepositoryLayoutProvider which uses RepositoryLayoutFactory which is a
     * Maven2RepositoryLayoutFactory. By overriding the layout provider, we can change how the files are deployed.
     */
    @Inject
    private RepositoryLayoutProvider repositoryLayoutProvider;

    /**
     * Target URI deployment repository. This should support the same format as altDeploymentRepository.
     */
    @Parameter(property = "rpmDeploymentRepository")
    private String rpmDeploymentRepository;

    /**
     * Whether to skip the plugin
     */
    @Parameter(defaultValue = "false", property = "rpm.deploy.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping RPM deployment");
            return;
        } else if (isEmpty(rpmDeploymentRepository)) {
            throw new MojoExecutionException(
                    "No repositoryId or rpmDeploymentRepository specified (or skip via '-Drpm.deploy.skip')");
        }

        try {
            List<File> rpms = findRPMs(outputDirectory.toPath());
            if (repositoryLayoutProvider instanceof DefaultRepositoryLayoutProvider) {
                ((DefaultRepositoryLayoutProvider) repositoryLayoutProvider).setRepositoryLayoutFactories(
                        Collections.singletonList(
                                new IndyGenericRepositoryLayoutFactory(checksumAlgorithmFactorySelector)));
            } else {
                throw new MojoExecutionException(
                        "Unknown repository layout provider " + repositoryLayoutProvider.getClass().getName());
            }

            // This matcher block is a direct copy of https://github.com/apache/maven-deploy-plugin/blob/maven-deploy-plugin-3.1.4/src/main/java/org/apache/maven/plugins/deploy/DeployMojo.java#L370 as unfortunately that code has not been
            // exposed for reuse.
            Matcher matcher = ALT_LEGACY_REPO_SYNTAX_PATTERN.matcher(rpmDeploymentRepository);
            String id;
            String url;

            if (matcher.matches()) {
                id = matcher.group(1).trim();
                String layout = matcher.group(2).trim();
                url = matcher.group(3).trim();

                if ("default".equals(layout)) {
                    getLog().warn(
                            "Using legacy syntax for alternative repository. " + "Use \"" + id + "::" + url
                                    + "\" instead.");
                } else {
                    throw new MojoExecutionException(
                            "Invalid legacy syntax and layout for rpm repository: \""
                                    + rpmDeploymentRepository + "\". Use \"" + id + "::" + url
                                    + "\" instead, and only default layout is supported.");
                }
            } else {
                matcher = ALT_REPO_SYNTAX_PATTERN.matcher(rpmDeploymentRepository);

                if (!matcher.matches()) {
                    throw new MojoExecutionException(
                            "Invalid syntax for rpm repository: \"" + rpmDeploymentRepository
                                    + "\". Use \"id::url\".");
                } else {
                    id = matcher.group(1).trim();
                    url = matcher.group(2).trim();
                }
            }

            RemoteRepository remoteRepository = new RemoteRepository.Builder(
                    id,
                    "default",
                    url).build();
            remoteRepository = repositorySystem
                    .newDeploymentRepository(session.getRepositorySession(), remoteRepository);

            getLog().info("Got RPM deployment repository: " + remoteRepository);

            DeployRequest request = new DeployRequest();
            request.setRepository(remoteRepository);

            getLog().info("Deploying " + rpms + " to " + url);

            rpms.forEach(
                    path -> request.addArtifact(
                            new DefaultArtifact(
                                    project.getGroupId(),
                                    project.getArtifactId(),
                                    null,
                                    "rpm",
                                    project.getVersion(),
                                    null,
                                    path)));

            repositorySystem.deploy(session.getRepositorySession(), request);

        } catch (IOException | DeploymentException e) {
            throw new MojoExecutionException(e);
        }
    }
}
