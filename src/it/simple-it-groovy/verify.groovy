import java.util.zip.ZipFile

def buildLog = new File(basedir, 'build.log')
def zipFile = new File(basedir, "target/simple-it-groovy-1.0.0.redhat-00002-bin.zip")

assert zipFile.exists()

List<String> entries = new ArrayList()
new ZipFile(zipFile).entries().each {
    entries.add(it.name)
}

assert entries.size() == 2
assert entries.stream().sorted().toArray().toString().contains("[apache-sshd-1.0-2.src.rpm, noarch/apache-sshd-1.0-2.noarch.rpm]")
assert buildLog.text.contains("Using groovy script")
