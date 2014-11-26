package org.wikidata.gremlin

import java.io.Reader
import com.tinkerpop.blueprints.Graph

class DataLoader {
	final Loader loader
	private Reader stream
	private File rejects
	private String fileName
	private int numReaders = 1
	private int myNum = 0
	private boolean gzipped
	
	DataLoader(Graph g, boolean ignore_props = false) {
		this.loader = new org.wikidata.gremlin.Loader(g, ignore_props)
	}
	
	public DataLoader setReaders(int r)
	{
		numReaders = r
		return this
	}

	public DataLoader setNum(int r)
	{
		myNum = r
		return this
	}
	
	public DataLoader file(String f) 
	{
		fileName = f
		gzipped = false
		return this
	}
	
	public DataLoader gzipFile(String f) 
	{
		fileName = f
		gzipped = true
		return this
	}
	
	protected void initStream() {
		def input 
		if(gzipped) {
			input = new java.util.zip.GZIPInputStream(new FileInputStream(fileName))
		} else {
			input = new FileInputStream(fileName)
		}
		stream = new LineNumberReader(new InputStreamReader(input, "UTF-8"))
	}
	
	protected void initRejects() {
		rejects = new File("rejects.${fileName}.${numReaders}.${myNum}.json")
	}
	
	public void load(max) {
		initStream()
		initRejects()
		String line = stream.readLine()
		if(line[0] == '[') {
			line = stream.readLine()
		}
		if(myNum > 0) {
			for(i in 0..myNum-1) {
				line = stream.readLine()
			}
		}
		for(i in 0..max-1) {
   		 	if(!line || line[0] == ']') {
   				return
   		 	}
			if(line[-1] == ',') {
   				line = line[0..-2]
   		 	}
			
			try {
				def item = new groovy.json.JsonSlurper().parseText(line)
	   		 	if(!item) {
	   			 return
	   		 	}
	   		 	loader.loadFromItem(item)
			} catch(e) {
				println "Importing line ${stream.getLineNumber()} failed: $e"
				rejects << line
			}
			(0..numReaders-1).each() { line = stream.readLine() }
		}
	}
}