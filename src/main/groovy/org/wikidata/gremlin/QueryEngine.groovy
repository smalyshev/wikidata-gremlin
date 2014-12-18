package org.wikidata.gremlin

import com.tinkerpop.blueprints.Graph
import com.tinkerpop.pipes.Pipe
import groovy.json.*

class QueryEngine {
	private File f

	QueryEngine() {
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
}