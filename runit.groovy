//g = TitanFactory.build().set("storage.backend","cassandra").set("storage.hostname","127.0.0.1").set("storage.buffer-size", ).set("storage.batch-loading", true).iset("ids.block-size", "10000").open()
index = (System.env.get("INSTANCE") as int) - 1
props = new org.apache.commons.configuration.PropertiesConfiguration()
props.load("config.properties")
props.setProperty("storage.machine-id-appendix", index)
g = TitanFactory.open(props)
groovy.grape.Grape.grab(group:'org.wikidata', module:'gremlin', version:'0.0.1-SNAPSHOT')
//groovy.grape.Grape.grab(group:'org.gperfutils', module:'gbench', version:'0.4.2-groovy-1.8')
w = org.wikidata.gremlin.ConsoleInit.init(this)
propLoader = new org.wikidata.gremlin.DataLoader(g, false)
dataLoader = new org.wikidata.gremlin.DataLoader(g, true)
q = new org.wikidata.gremlin.QueryEngine(g)
import org.wikidata.gremlin.*;
