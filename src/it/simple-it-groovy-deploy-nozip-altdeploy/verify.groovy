import groovy.xml.XmlSlurper

System.out.println("Starting verify script")
def buildLog = new File(basedir, "build.log")
def specFile = new File(basedir, "target/spec/apache-sshd.spec")
def origSpecFile = new File(basedir, "apache-sshd.spec")
def zipFile = new File(basedir, "target/simple-it-groovy-deploy-1.0.0.redhat-00002.zip")
assert !zipFile.exists()
assert buildLog.text.contains("Using groovy script")
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

def rpmdir = new File(localRepositoryPath.toString() + "/../local-deploy", "${pom.groupId.text().replace('.', '/')}/${pom.artifactId.text()}/${pom.version.text()}" )
List<String> entries = new ArrayList()
rpmdir.eachFile() { entries.add(it.getName()) }

assert entries.size() == 12
ArrayList<String> rpms = new ArrayList()
entries.stream().filter(s -> s.endsWith("rpm")).forEach {
    it -> rpms.add(it.toString())
}
assert rpms.size() == 2
assert rpms.contains("apache-sshd-1.0-2.src.rpm")

assert buildLog.text.contains("rpm-builder-maven-plugin/target/its/simple-it-groovy-deploy-nozip-altdeploy/target/dependency/noarch/foobar-test-1.0.0-1.fc42.noarch.rpm using rpm2cpio/cpio")
assert new File("/tmp/foobar").exists()
