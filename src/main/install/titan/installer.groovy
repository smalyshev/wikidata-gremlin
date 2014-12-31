#!/usr/bin/env groovy

INIT_SCRIPT_NAME = 'wikidata_gremlin_rexster_init.groovy'
JAR_NAME = "wikidata-gremlin-0.0.1-SNAPSHOT.jar"

def cli = new CliBuilder()
cli.h(longOpt:'help', 'print this message')
cli.d(longOpt:'destination', args:1, argName:'destination', 'installation destination')
def options = cli.parse(args)
if (!options) {
	return 1
}
if (options.h) {
	cli.usage()
	return 1
}
def destination = new File(options.d ? options.d : '.')

def scriptDir = new File(new File(getClass().protectionDomain.codeSource.location.path).parent)
def installDir = new File(scriptDir.parent)
def jar
if (installDir.name == "install") {
	def root = new File(new File(new File(installDir.parent).parent).parent)
	jar = new File(root, "target/$JAR_NAME")
}
if (!jar || !jar.exists()) {
	println "Couldn't find install jar"
	return 1
}
def initScript = new File(scriptDir, INIT_SCRIPT_NAME)
if (!initScript.exists()) {
	println "Couldn't find init script!"
	return 1
}

def destinationDir(destination, name) {
	def dir = new File(destination, name)
	if (!dir.exists() || !dir.isDirectory()) {
		println "name doesn't exist:  $dir"
		System.exit(1)
	}
	return dir
}
def rexhomeDir = destinationDir(destination, 'rexhome')
def libDir = destinationDir(destination, 'lib')
def confDir = destinationDir(destination, 'conf')


def copy(File source, File destination) {
	if (destination.exists()) {
		return
	}
	println "Copying $source to $destination"
	java.nio.file.Files.copy(source.toPath(), destination.toPath())
}

def installInitConfig(confDir, configFileName) {
	println "Installing wikidata-gremlin init configuration to $configFileName"
	def configFile = new File(confDir, configFileName)
	if (!configFile.exists() || !configFile.isFile()) {
		println "Invalid file: $configFile"
		System.exit(1)
	}
	def config = new XmlSlurper().parse(configFile)
	config['script-engines']['script-engine'].each{engine ->
		engine.appendNode{'init-scripts'(INIT_SCRIPT_NAME)}
	}
	def out = new FileWriter(configFile)
	groovy.xml.XmlUtil.serialize(config, out)
	out.close()
}

println "Installing from $scriptDir to $destination"
copy(jar, new File(libDir, JAR_NAME))
copy(initScript, new File(rexhomeDir, INIT_SCRIPT_NAME))
installInitConfig(confDir, 'rexster-cassandra.xml')
installInitConfig(confDir, 'rexster-cassandra-es.xml')




