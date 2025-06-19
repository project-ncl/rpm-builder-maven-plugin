import java.util.zip.ZipFile

def buildLog = new File(basedir, 'build.log')
def zipFile = new File(basedir, "target/simple-it-1.0-SNAPSHOT-bin.zip")
def pomFile = new File( basedir, 'pom.xml' )

assert zipFile.exists()
assert pomFile.text.contains("<packaging>spec</packaging>")

List<String> entries = new ArrayList()
new ZipFile(zipFile).entries().each {
    entries.add(it.name)
}

assert entries.size() == 2
assert entries.stream().sorted().toArray().toString().contains("apache-sshd-1.0-1.el9.src.rpm, noarch/apache-sshd-1.0-1.el9.noarch.rpm]")
assert ! buildLog.text.contains("Using groovy script")
