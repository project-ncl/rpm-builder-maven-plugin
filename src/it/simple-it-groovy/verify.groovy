import java.util.zip.ZipFile

def buildLog = new File(basedir, 'build.log')
def zipFile = new File(basedir, "target/simple-it-groovy-1.0.0.redhat-00002-bin.zip")
def specfile = new File(basedir, 'target/spec/apache-sshd.spec')

assert zipFile.exists()

List<String> entries = new ArrayList()
new ZipFile(zipFile).entries().each {
    entries.add(it.name)
}

assert entries.size() == 2
assert entries.stream().sorted().toArray().toString().contains("[apache-sshd-1.4.18.SP13-2.src.rpm, noarch/apache-sshd-1.4.18.SP13-2.noarch.rpm]")
assert buildLog.text.contains("Using groovy script")
assert specfile.text.contains("""unknown@dummy.com - 1.4.18-2.SP13_redhat_00003.1\n- New Release""")
