/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jboss.pnc.rpmbuilderplugin;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.internal.impl.Maven2RepositoryLayoutFactory;
import org.eclipse.aether.internal.impl.checksum.DefaultChecksumAlgorithmFactorySelector;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.checksum.ChecksumAlgorithmFactory;
import org.eclipse.aether.spi.connector.checksum.ChecksumAlgorithmFactorySelector;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.eclipse.aether.spi.connector.layout.RepositoryLayoutFactory;
import org.eclipse.aether.transfer.NoRepositoryLayoutException;
import org.eclipse.aether.util.ConfigUtils;

/**
 * This is a direct copy of org.eclipse.aether.internal.impl.Maven2RepositoryLayoutFactory with small modifications to
 * make it suitable for deploying RPMs to a specified repository under their original name. It uses a content type of
 * {@code "default"}.
 */
public final class IndyGenericRepositoryLayoutFactory implements RepositoryLayoutFactory {

    private static final String DEFAULT_CHECKSUMS_ALGORITHMS = "SHA-1,MD5";

    private static final String DEFAULT_OMIT_CHECKSUMS_FOR_EXTENSIONS = ".asc,.sigstore";

    private float priority;

    private final ChecksumAlgorithmFactorySelector checksumAlgorithmFactorySelector;

    public float getPriority() {
        return priority;
    }

    /**
     * Service locator ctor.
     */
    @Deprecated
    public IndyGenericRepositoryLayoutFactory() {
        this(new DefaultChecksumAlgorithmFactorySelector());
    }

    @Inject
    public IndyGenericRepositoryLayoutFactory(ChecksumAlgorithmFactorySelector checksumAlgorithmFactorySelector) {
        this.checksumAlgorithmFactorySelector = requireNonNull(checksumAlgorithmFactorySelector);
    }

    public RepositoryLayout newInstance(RepositorySystemSession session, RemoteRepository repository)
            throws NoRepositoryLayoutException {
        requireNonNull(session, "session cannot be null");
        requireNonNull(repository, "repository cannot be null");
        if (!"default".equals(repository.getContentType())) {
            throw new NoRepositoryLayoutException(repository);
        }

        List<ChecksumAlgorithmFactory> checksumsAlgorithms = checksumAlgorithmFactorySelector.selectList(
                ConfigUtils.parseCommaSeparatedUniqueNames(
                        ConfigUtils.getString(
                                session,
                                DEFAULT_CHECKSUMS_ALGORITHMS,
                                Maven2RepositoryLayoutFactory.CONFIG_PROP_CHECKSUMS_ALGORITHMS + "."
                                        + repository.getId(),
                                Maven2RepositoryLayoutFactory.CONFIG_PROP_CHECKSUMS_ALGORITHMS)));

        // ensure uniqueness of (potentially user set) extension list
        Set<String> omitChecksumsForExtensions = Arrays.stream(
                ConfigUtils.getString(
                        session,
                        DEFAULT_OMIT_CHECKSUMS_FOR_EXTENSIONS,
                        Maven2RepositoryLayoutFactory.CONFIG_PROP_OMIT_CHECKSUMS_FOR_EXTENSIONS)
                        .split(","))
                .filter(s -> s != null && !s.trim().isEmpty())
                .collect(Collectors.toSet());

        // validation: enforce that all strings in this set are having leading dot
        if (omitChecksumsForExtensions.stream().anyMatch(s -> !s.startsWith("."))) {
            throw new IllegalArgumentException(
                    String.format(
                            "The configuration %s contains illegal values: %s (all entries must start with '.' (dot))",
                            Maven2RepositoryLayoutFactory.CONFIG_PROP_OMIT_CHECKSUMS_FOR_EXTENSIONS,
                            omitChecksumsForExtensions));
        }

        return new IndyGenericRepositoryLayout(
                checksumAlgorithmFactorySelector,
                checksumsAlgorithms,
                omitChecksumsForExtensions);
    }

    private static class IndyGenericRepositoryLayout implements RepositoryLayout {
        private final ChecksumAlgorithmFactorySelector checksumAlgorithmFactorySelector;

        private final List<ChecksumAlgorithmFactory> configuredChecksumAlgorithms;

        private final Set<String> extensionsWithoutChecksums;

        private IndyGenericRepositoryLayout(
                ChecksumAlgorithmFactorySelector checksumAlgorithmFactorySelector,
                List<ChecksumAlgorithmFactory> configuredChecksumAlgorithms,
                Set<String> extensionsWithoutChecksums) {
            this.checksumAlgorithmFactorySelector = requireNonNull(checksumAlgorithmFactorySelector);
            this.configuredChecksumAlgorithms = Collections.unmodifiableList(configuredChecksumAlgorithms);
            this.extensionsWithoutChecksums = requireNonNull(extensionsWithoutChecksums);
        }

        private URI toUri(String path) {
            try {
                return new URI(null, null, path, null);
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public List<ChecksumAlgorithmFactory> getChecksumAlgorithmFactories() {
            return configuredChecksumAlgorithms;
        }

        @Override
        public boolean hasChecksums(Artifact artifact) {
            String artifactExtension = artifact.getExtension(); // ie. pom.asc
            for (String extensionWithoutChecksums : extensionsWithoutChecksums) {
                if (artifactExtension.endsWith(extensionWithoutChecksums)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public URI getLocation(Artifact artifact, boolean upload) {
            StringBuilder path = new StringBuilder(128);

            path.append(artifact.getGroupId().replace('.', '/')).append('/');

            path.append(artifact.getArtifactId()).append('/');

            path.append(artifact.getBaseVersion()).append('/');

            // Modifications here for RPM deployment
            path.append(artifact.getFile().getName());

            return toUri(path.toString());
        }

        @Override
        public URI getLocation(Metadata metadata, boolean upload) {
            StringBuilder path = new StringBuilder(128);

            if (metadata.getGroupId().length() > 0) {
                path.append(metadata.getGroupId().replace('.', '/')).append('/');

                if (metadata.getArtifactId().length() > 0) {
                    path.append(metadata.getArtifactId()).append('/');

                    if (metadata.getVersion().length() > 0) {
                        path.append(metadata.getVersion()).append('/');
                    }
                }
            }

            path.append(metadata.getType());

            return toUri(path.toString());
        }

        @Override
        public List<ChecksumLocation> getChecksumLocations(Artifact artifact, boolean upload, URI location) {
            if (!hasChecksums(artifact) || isChecksum(artifact.getExtension())) {
                return Collections.emptyList();
            }
            return getChecksumLocations(location);
        }

        @Override
        public List<ChecksumLocation> getChecksumLocations(Metadata metadata, boolean upload, URI location) {
            return getChecksumLocations(location);
        }

        private List<ChecksumLocation> getChecksumLocations(URI location) {
            List<ChecksumLocation> checksumLocations = new ArrayList<>(configuredChecksumAlgorithms.size());
            for (ChecksumAlgorithmFactory checksumAlgorithmFactory : configuredChecksumAlgorithms) {
                checksumLocations.add(ChecksumLocation.forLocation(location, checksumAlgorithmFactory));
            }
            return checksumLocations;
        }

        private boolean isChecksum(String extension) {
            return checksumAlgorithmFactorySelector.isChecksumExtension(extension);
        }
    }
}
