props = new org.apache.commons.configuration.PropertiesConfiguration()
props.load("config.properties")
// Change this for multiple Titan runs on the same machine
props.setProperty("storage.machine-id-appendix", 0)
g = TitanFactory.open(props)
groovy.grape.Grape.grab(group:'org.wikidata', module:'gremlin', version:'0.0.1-SNAPSHOT')
w = org.wikidata.gremlin.ConsoleInit.init(this)
propLoader = new org.wikidata.gremlin.DataLoader(g, false)
dataLoader = new org.wikidata.gremlin.DataLoader(g, true)
