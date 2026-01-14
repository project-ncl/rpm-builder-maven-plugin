import groovy.xml.XmlSlurper

def buildLog = new File(basedir, "build.log")
def specFile = new File(basedir, "target/spec/apache-sshd.spec")
def origSpecFile = new File(basedir, "apache-sshd.spec")
def zipFile = new File(basedir, "target/simple-it-groovy-deploy-1.0.0.redhat-00002.zip")
assert !zipFile.exists()
assert buildLog.text.contains("Using groovy script")
assert buildLog.text.contains("Unable to find wrappedBuild property in project properties. Define this property to denote the version of the build to be wrapped inside the RPM")
def pomFile = new File( basedir, 'pom.xml' )
def pom = new XmlSlurper().parse( pomFile )
def deploydir = new File(localRepositoryPath.toString() + "/../local-deploy", "${pom.groupId.text().replace('.', '/')}/${pom.artifactId.text()}/${pom.version.text()}" )

def deploypom = new File( deploydir, "${pom.artifactId.text()}-${pom.version.text()}.pom" )
def deployspec = new File( deploydir, "${pom.artifactId.text()}-${pom.version.text()}.spec" )
def deployzip = new File( deploydir, "${pom.artifactId.text()}-${pom.version.text()}.zip" )
assert !deployzip.exists()
assert deploypom.exists()
assert deployspec.exists()
assert deployspec.text == specFile.text
assert deployspec.text != origSpecFile.text

def rpmdir = new File(localRepositoryPath.toString() + "/..//rpm-deploy", "${pom.groupId.text().replace('.', '/')}/${pom.artifactId.text()}/${pom.version.text()}" )
List<String> entries = new ArrayList()
rpmdir.eachFile() { entries.add(it.getName()) }

assert entries.size() == 6
ArrayList<String> rpms = new ArrayList()
entries.stream().filter(s -> s.endsWith("rpm")).forEach {
    it -> rpms.add(it.toString())
}
assert rpms.size() == 2
assert rpms.contains("apache-sshd-1.0-2.src.rpm")
