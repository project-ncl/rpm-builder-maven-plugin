import java.util.zip.ZipFile
import groovy.xml.XmlSlurper

def buildLog = new File(basedir, "build.log")
def zipFile = new File(basedir, "target/simple-it-groovy-deploy-1.0.0.redhat-00002-bin.zip")
def specFile = new File(basedir, "target/spec/apache-sshd.spec")
def origSpecFile = new File(basedir, "apache-sshd.spec")

assert zipFile.exists()

List<String> entries = new ArrayList()
new ZipFile(zipFile).entries().each {
    entries.add(it.name)
}

assert entries.size() == 2
assert entries.stream().sorted().toArray().toString().contains("[apache-sshd-1.0-2.src.rpm, noarch/apache-sshd-1.0-2.noarch.rpm]")
assert buildLog.text.contains("Using groovy script")

def pomFile = new File( basedir, 'pom.xml' )
def pom = new XmlSlurper().parse( pomFile )
def deploydir = new File(localRepositoryPath.toString() + "/..//local-deploy", "${pom.groupId.text().replace('.', '/')}/${pom.artifactId.text()}/${pom.version.text()}" )

def deploypom = new File( deploydir, "${pom.artifactId.text()}-${pom.version.text()}.pom" )
def deployspec = new File( deploydir, "${pom.artifactId.text()}-${pom.version.text()}.spec" )
def deployzip = new File( deploydir, "${pom.artifactId.text()}-${pom.version.text()}.zip" )
assert deploypom.exists()
assert deployspec.exists()
assert deployspec.text == specFile.text
assert deployspec.text != origSpecFile.text
assert deployzip.size() == zipFile.size()
