
def deploydir = new File(localRepositoryPath.toString() + "/../local-deploy")
deploydir.deleteDir()
def rpmdir = new File(localRepositoryPath.toString() + "/../rpm-deploy")
rpmdir.deleteDir()
