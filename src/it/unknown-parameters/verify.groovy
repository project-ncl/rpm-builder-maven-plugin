import groovy.xml.XmlSlurper

System.out.println("Starting verify script")

def buildLog = new File(basedir, "build.log")
assert buildLog.text.contains("Found unknown parameter(s) in configuration [installrpms]")
