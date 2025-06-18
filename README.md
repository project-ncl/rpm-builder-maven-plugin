
# RPM Builder Maven Plugin

This plugin assists in building RPMs while using [PME](https://github.com/release-engineering/pom-manipulation-ext).

This requires the `rpmbuild` binary to be installed in the host system. It should be run in a directory containing a spec file which will be automatically located.

### Optional Parameters
* A `groovyPatch` configuration parameter may be used to patch the spec file e.g. if PME has been run on the containing pom then this could be used to update template fields within the spec file. 
* A `macros` configuration map may be used to pass additional macro defines to the `rpmbuild` command.

Then the plugin sets up the correct directories and runs `rpmbuild -ba` generating the source and binary RPMs into the target directory. Finally it will package those RPMs into a zip which will be attached to the build.

A complete example:

```xml
    <artifactId>my-build</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>rpm</packaging>

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
          <groovyPatch>
                def serial = ("${project.version}" =~ "(.*redhat-0+)(.*)")[0][2]
                def original_version = "${sshd}".replaceAll(".redhat.*", "")
                def rh_version = "${sshd}".replaceAll(original_version, "")
                def rpm_version = "${sshd}".replaceAll(original_version, "").replaceAll("-", "_")

                println "I am building version ${project.version} of ${project.name} with ${sshd} at ${new Date()}"
                println "Serial ${serial} OrigVersion ${original_version} RHVersion ${rh_version}"

                // Note the spec file is always copied to target/spec first so modify it there.
                def spec = new File("${project.build.directory}/spec/apache-sshd.spec")
                def contents = spec.getText('UTF-8')

                // Hack to ensure subdirectory creation is correctly named.
                contents = contents.replaceAll("%setup -q -n sshd-", "%setup -q -n apache-sshd-")

                contents = """
%global meadversion ${original_version}
%global namedversion ${sshd}
%global meadalpha ${rpm_version}
%global meadrel .1
%global serial $serial
%define maven_version ${sshd}
%if %with mead
Source100: sshd-${sshd}-project-sources.zip
%endif

""" + contents
                spec.write(contents)
          </groovyPatch>
        </configuration>
      </plugin>

```
