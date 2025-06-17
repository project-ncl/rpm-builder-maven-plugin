import org.commonjava.maven.ext.core.groovy.BaseScript
import org.commonjava.maven.ext.core.groovy.InvocationStage
import org.commonjava.maven.ext.core.groovy.PMEBaseScript
import org.commonjava.maven.ext.core.groovy.InvocationPoint

@InvocationPoint(invocationPoint = InvocationStage.LAST)
@PMEBaseScript BaseScript pme

def serial = (pme.getGAV().getVersionString() =~ "(.*redhat-0+)(.*)")[0][2]
def wrappedBuild = pme.getProject().getModel().getProperties().getProperty("wrappedBuild")
if (wrappedBuild == null) {
    pme.getLogger().error("Unable to find 'wrappedBuild' property in pom. Is it named correctly?")
    throw new RuntimeException("Problem locating wrappedBuild property")
}
def original_version = wrappedBuild.replaceAll(".redhat.*", "")
def rh_version = wrappedBuild.replaceAll(original_version, "")
def rpm_version = wrappedBuild.replaceAll(original_version, "").replaceAll("-", "_")

println "I am building version ${pme.getGAV()} with ${wrappedBuild} at ${new Date()}"
println "Serial ${serial} OrigVersion ${original_version} RHVersion ${rh_version} and rpmVersion ${rpm_version}"

// For local testing can change to local file e.g. file:///tmp/rpm-builder-maven-plugin/generateChangelog.py
File remoteChangelogGenerator = pme.getFileIO().resolveURL ("https://github.com/project-ncl/rpm-builder-maven-plugin/raw/refs/heads/main/generateChangelog.py")
String command = "python " + remoteChangelogGenerator.getAbsoluteFile() + " " + original_version + " " + rh_version + " " + serial
def python = command.execute()
python.consumeProcessOutput(System.out, System.err)
