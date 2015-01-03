package org.wikidata.gremlin

import com.tinkerpop.blueprints.Graph
import com.tinkerpop.pipes.Pipe
import java.io.ByteArrayInputStream;
//import org.antlr.v4.runtime.CommonTokenStream;
//import org.antlr.v4.runtime.ParserRuleContext;
//import org.antlr.v4.runtime.tree.ParseTree;
//import org.antlr.v4.runtime.tree.Tree;
//import org.antlr.v4.runtime.ANTLRInputStream;
import groovy.json.*


class QueryEngine {
	private File f
	private final Graph g

	QueryEngine(Graph g = null) {
		this.g = g
	}

	public void setFile(String filename)
	{
		this.f = new File(filename)
		f.createNewFile()
	}

	public void dumpLine(data, properties)
	{
		def out = [:]
		for(p in properties) {
			out[p] = data[p]
		}
		f << new JsonBuilder(out) << ",\n"
	}

	public void dump(String filename, Pipe data, String[] properties)
	{
		if(!properties) {
			properties = ['wikibaseId']
		}
		setFile(filename)
		f << "[\n"
		data.sideEffect{this.dumpLine(it, properties)}.iterate()
		f << "]\n"
	}

	public def compare(String wdq, Pipe data)
	{
		println "Loading WDQ..."
		def wdqres = new URL("https://wdq.wmflabs.org/api?q=$wdq").getText('UTF-8');
		def wdqitems = new groovy.json.JsonSlurper().parseText(wdqres)
		if(!wdqitems.status.items) {
			println "WDQ query failed, sorry"
			return false
		}
		println "Got ${wdqitems.status.items} items from WDQ, let's check them out"
		def checklist = [:]
		for(itm in wdqitems.items) {
			def name = 'Q'+(itm as String)
			try {
				g.wd(name) // ping name in case it's new
			} catch(Exception e) {
				println "Failed to load $name, probably stale, ignoring $e"
			}
			checklist[name] = true
		}
		def ourres = []
		println "Running Gremilin..."
		data.aggregate(ourres).iterate()
		if(ourres.size() == wdqitems.status.items) {
			println "Sizes match, good job"
			return true
		}
		println "Mismatch happened: WDQ is ${wdqitems.status.items}, ours is ${ourres.size()}, checking items"
		def badapples = []
		for(oitm in ourres) {
			if(!(oitm.wikibaseId in checklist)) {
				println "${oitm.wikibaseId} is in our query but not in WDQ"
				badapples << oitm.wikibaseId
			}
			checklist[oitm.wikibaseId] = false
		}
		badapples << false
		def missing = checklist.findAll{it.value}
		if(missing) {
			println "Missing in our output: ${missing.size()}"
		}
		badapples.addAll(missing.keySet())
		return badapples
	}

}

/*
class Parser {
	public static void parse(String s) {
		def input = new ANTLRInputStream(s)
		def lexer = new WDQLexer(input)
		def tokens = new CommonTokenStream(lexer)
		def parser = new WDQParser(tokens)
		ParseTree t = parser.expression();
		System.out.println(t.toStringTree(parser));
	}
}*/
