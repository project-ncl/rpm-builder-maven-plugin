import java.nio.file.Files
import java.nio.file.Path

def deploydir = new File(localRepositoryPath, "/../local-deploy")
deploydir.deleteDir()
def noarchdir = new File(basedir, "target/dependency/noarch")
noarchdir.mkdirs()

Files.copy(Path.of(basedir.toString(), "foobar-test-1.0.0-1.fc42.noarch.rpm"), Path.of(noarchdir.toString(), "foobar-test-1.0.0-1.fc42.noarch.rpm"))
System.out.println ("Finished copying the RPM")
