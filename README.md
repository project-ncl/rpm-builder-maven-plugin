
# RPM Builder Maven Plugin

This plugin assists in building RPMs while using [PME](https://github.com/release-engineering/pom-manipulation-ext).

This requires the `rpmbuild` binary to be installed in the host system. It should be run in a directory containing a spec file which will be automatically located.

**Note**: when using this plugin the packaging should be set to `spec`. It will attach the spec file (that may have been modified by the patching mechanism) to the build as the primary artifact.

**Note**: the prior build to be wrapped should have its version embedded in the properties under the property `wrappedBuild`. This is mandatory if using changelog generation.

Then the plugin sets up the correct directories and runs `rpmbuild -ba` generating the source and binary RPMs into the target directory. Finally the plugin can deploy the zips to a separate repository controlled by `rpmDeploymentRepository` which supports the same format as `altDeploymentRepository`.
