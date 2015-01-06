package org.wikidata.gremlin

import java.io.Reader
import com.tinkerpop.blueprints.Graph
import groovy.json.*
import com.thinkaurelius.titan.core.TitanException

class DataLoader {
	private int LINES_PER_COMMIT = 1000
	final Loader loader
	final Graph g
	private Reader stream
	private File rejects = null
	private File processed = null
	private String fileName
	private int numReaders = 1
	private int myNum = 0
	private boolean gzipped
	private int skipLines = 0
	private boolean failOnError = false
	private int processedNum = 0
	private boolean batch = false

	DataLoader(Graph g, boolean ignore_props = false) {
		this.g = g
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

	public DataLoader setLines(int l)
	{
		LINES_PER_COMMIT = l
		return this
	}

	public DataLoader failOnError(boolean f=true)
	{
		failOnError = f
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

	protected void initFiles() {
		if(processed) {
			return
		}
		String basename = (fileName =~ /[^\/]+$/)[0]
		rejects = new File("rejects.${basename}.${numReaders}.${myNum}.json")
		processed = new File("processed.${basename}.${numReaders}.${myNum}")
	}

	public DataLoader recover()
	{
		initFiles()
		if(!processed.size()) {
			// nothing to recover
			return this
		}
		processedNum = processed.text as int;
		println "Recovery: advancing line number by $processedNum"
		return this
	}

	public DataLoader batch(val = true)
	{
		batch = val
		this.loader.setBatch(val)
		this
	}

	public void load(max) {
		initStream()
		initFiles()
		loader.resetClaims()
		def json = new JsonSlurper() //.setType(JsonParserType.INDEX_OVERLAY )
		String line = stream.readLine()
		if(line[0] == '[') {
			line = stream.readLine()
		}
		if(processedNum) {
			// we need to advance the num by 1 since last line processed is 1000, not 999
			processedNum++
		}
		if(myNum > 0 || processedNum > 0) {
			for(i in 0..<(myNum+processedNum)) {
				line = stream.readLine()
			}
		}
		def realLines = 0
		def failedLines = 0
		for(i in processedNum..<max) {
   		 	if(!line || line[0] == ']') {
   				break
   		 	}
			if(line[-1] == ',') {
   				line = line[0..-2]
   		 	}

			try {
				def item = json.parseText(line)
	   		 	if(!item) {
				  println "Bad line $i, skipping\n"
 	   			  break
	   		 	}
	   		 	loader.loadFromItem(item)
			} catch(TitanException e) {
				// it's bad and for once it's not our fault. Let's stop for now
				println "Titan exception: $e. Bailing out..."
				e.printStackTrace()
				break;
			} catch(e) {
				println "Importing line ${stream.getLineNumber()} failed: $e"
				rejects << line
				rejects << "\n"
				failedLines++;
				if(failOnError) { throw e; }
			}
			realLines++;
			(0..numReaders-1).each() { line = stream.readLine() }
			if(i != 0 && i % LINES_PER_COMMIT == 0) {
				if(!batch) {
					g.commit()
				}
				println "Committed on row $i"
				def fw = new FileWriter(processed)
				fw.write(i as String)
				fw.close()
			}
		}
		g.commit()
		println "Processed $realLines lines, failed $failedLines, processed ${loader.getClaims()} claims"
	}

	public void processClaims(max, Closure c) {
		initStream()
		initRejects()
		def json = new JsonSlurper() //.setType(JsonParserType.INDEX_OVERLAY )
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
   				break
   		 	}
			if(line[-1] == ',') {
   				line = line[0..-2]
   		 	}

			def item = json.parseText(line)
   		 	if(!item) {
	   			 break
   		 	}
			c(item, g, i)
/*	   		 	for (claimsOnProperty in item.claims) {
					if(!claimsOnProperty.value.size()) {
						// empty claim, ignore
						continue
					}
			      	for (claim in claimsOnProperty.value) {
				        if (claim.mainsnak == null) {
							continue;
						}
						c(claim)
					}
				}
*/
			(0..numReaders-1).each() { line = stream.readLine() }
			if(i != 0 && i % LINES_PER_COMMIT == 0) {
				println "Processed row $i"
				def fw = new FileWriter(processed)
				fw.write(i as String)
				fw.close()
			}
		}
	}
}
