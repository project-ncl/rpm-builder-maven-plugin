# Usage

The plugin has a number of configuration parameters that are described on the [goals](plugin-info.html) page. It requires the `rpmbuild` binary to be installed in the host system. It should be run in a directory containing a spec file which will be automatically located.

**Note:** when using this plugin the packaging should be set to spec. It will attach the spec file (that may have been modified by the patching mechanism) to the build as the primary artifact. It will run `rpmbuild -ba` on the spec file.

**Note:** the prior build to be wrapped should have its version embedded in the properties under the property `wrappedBuild`. This is mandatory if using changelog generation.

## Patching via Groovy

The plugin can run a groovy script (`groovyScript`) to perform patches to a RPM spec file before building e.g. if PME has been run on the containing pom then this could be used to update template fields within the spec file. For the groovy scripts, the following variables are injected:

* `meadalpha` the final portion of the `wrappedBuild` property converted to RPM NVR compatible format e.g. `.SP13_redhat_00001` from the example below.
* `meadrel` with the value `.1`
* `meadversion` the numeric value of the `wrappedBuild` property e.g. `1.4.18` from the example below.
* `serial` - the final part of the increment of the project version e.g. `2` from the example below.
* `wrappedBuild` the value of the `wrappedBuild` property

## Miscellaneous

The rpms can be packaged into a zip and attached to the build. This is disabled by default and may be enabled via `attachZip`
A `macros` configuration map may be used to pass additional macro defines to the `rpmbuild` command.
A `changeLog` configuration object may be used to trigger change log generation. By default, email is set to `project-ncl@redhat.com` and message is set to `- New Release`.


## Deployment

The plugin can deploy the RPMs to a repository. This is controlled by `rpmDeploymentRepository` which supports the same format as `altDeploymentRepository`. The plugin deployment may be skipped with `rpm.deploy.skip`. If `rpmDeploymentRepository` is not set it will fall back to the value of the user property `altDeploymentRepository`.


## Examples

```xml
    <artifactId>my-build</artifactId>
    <version>1.0.0.redhat-00002</version>
    <packaging>spec</packaging>

    <properties>
      <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
      <wrappedBuild>1.4.18.SP13-redhat-00003</wrappedBuild>
    </properties>

    <!-- ... -->

    <plugin>
        <groupId>org.jboss.pnc</groupId>
        <artifactId>rpm-builder-maven-plugin</artifactId>
        <version>1.0</version>
        <extensions>true</extensions>
        <configuration>
          <macros>
              <dist>.el9eap8</dist>
          </macros>
          <changeLog>
              <generate>true</generate>
              <email>me@dummy.com</email>
          </changeLog>
          <groovyPatch>
            def serial = ("${project.version}" =~ "(.*redhat-0+)(.*)")[0][2]
            def original_version = "${wrappedBuild}".replaceAll(".redhat.*", "")
            def rh_version = "${wrappedBuild}".replaceAll(original_version, "")
            def rpm_version = "${wrappedBuild}".replaceAll(original_version, "").replaceAll("-", "_")

            println "I am building version ${project.version} of ${project.name} with ${wrappedBuild} at ${new Date\(\)}"
            println "Serial ${serial} OrigVersion ${original_version} RHVersion ${rh_version}"

            // Note the spec file is always copied to target/spec first so modify it there.
            def spec = new File("${project.build.directory}/spec/apache-sshd.spec")
            def contents = spec.getText('UTF-8')

            // Hack to ensure subdirectory creation is correctly named.
            contents = contents.replaceAll("%setup -q -n wrappedBuild-", "%setup -q -n apache-sshd-")

            contents = """
%global meadversion ${original_version}
%global namedversion ${wrappedBuild}
%global meadalpha ${rpm_version}
%global meadrel .1
%global serial $serial
%define maven_version ${wrappedBuild}
%if %with mead
Source100: sshd-${wrappedBuild}-project-sources.zip
%endif

""" + contents
            spec.write(contents)
          </groovyPatch>
        </configuration>
      </plugin>

```

Alternate example using the injected variables in the groovy script:

```xml
    <plugin>
        <groupId>org.jboss.pnc</groupId>
        <artifactId>rpm-builder-maven-plugin</artifactId>
        <version>1.0</version>
        <extensions>true</extensions>
        <configuration>
          <macros>
              <dist>.el9eap8</dist>
          </macros>
          <changeLog>
              <generate>true</generate>
              <email>me@dummy.com</email>
          </changeLog>
          <groovyPatch>
            // Note the spec file is always copied to target/spec first so modify it there.
            def spec = new File("${project.build.directory}/spec/apache-sshd.spec")
            def contents = spec.getText('UTF-8')

            contents = """
%global meadversion ${meadversion}
%global meadalpha ${meadalpha}
%global meadrel ${meadrel}
%global serial ${serial}
%define maven_version ${wrappedBuild}

%if %with mead
Source100: sshd-${wrappedBuild}-project-sources.zip
%endif
""" + contents
                spec.write(contents)
          </groovyPatch>
        </configuration>
      </plugin>

```
