// Apache 2 Licensed

package org.wikidata.gremlin

import groovy.lang.Closure;
import groovy.util.logging.Slf4j;

import com.tinkerpop.blueprints.impls.tg.TinkerGraph

@Slf4j
class ConsoleInit extends RexsterInit {
	final protected def script

	private ConsoleInit(script) {
		super()
		this.script = script
		script.random = new Random()
	}

	static def init(script) {
		return new ConsoleInit(script).setup()
	}

	protected ConsoleInit setup()
	{
		log.info "Starting console setup..."
		graph()
		schema()
		super.setup()
		this
	}

	/**
	 * Set the g global with the graph contents.
	 */
	private def graph() {
		// First lets just see if we're already in a graph scope like on the web console.
		// Try initializing like we're in rexster command line console.
		try {
			if (script.g) {
				log.info "Initialized for Rexster Web Console"
			}
		} catch (MissingPropertyException e) {
			// OK .g isn't defined.  Go to next option
			log.info "script.g not found, proceeding..."
		}
		try {
			script.g = script.rexster.getGraph('wikidata')
			log.info "Initialized for Rexster Console"
		} catch (MissingPropertyException e) {
			// OK .rexster isn't defined.  Go to next option
			log.info "script.rexster not found, proceeding..."
		}
		if(!script.g) {
			try {
				log.info "Initializing for Gremlin console"
				script.g = new TinkerGraph('wikidata', TinkerGraph.FileType.GRAPHSON)
			} catch (Exception e) {
				log.error "Something is wrong loading or creating the graph.  Here is the exception, good luck:"
				throw e
			}
		}
		return script.g
	}

	/**
	 * Setup the graph serve wikidata items.
	 */
	private void schema() {
		try {
			def schema = new Schema(script.g)
			schema.setupSchema()
			schema.setupConstantData()
		} catch (Exception e) {
			log.error "Something went wrong updating the schema.  Here is the exception, good luck:"
			e.printStackTrace()
			System.exit(1)
		}
	}

	static void loadData(script, max, procs, num, file, ignore_props=true) {
		def loader = new Loader(script.g, ignore_props)
		new DataLoader(loader).setReaders(procs).setNum(num).gzipFile(file).read(max)
	}

	void test () {
		script.g.V('vid', 0).remove()
		script.g.V('vid', 1).remove()
		def v0 = script.g.addVertex([vid: 0, type: 'start'])
		def random = new Random()
		for(i in 1..10000000) {
			def v = script.g.addVertex([vid: i, type: 'claim'])
			v.addEdge('is-a', v0)
			def n = random.nextInt(i)
			def vr = script.g.V('vid', n).next()
			//println "$n: $vr"
			v.addEdge('test', vr)
			if (i%10000 == 0) {
				println "Done $i"
				script.g.commit()
			}
		}
	}

}
