// Apache 2 Licensed

package org.wikidata.gremlin

import com.tinkerpop.blueprints.impls.tg.TinkerGraph

class ConsoleInit {
  final def script

  private ConsoleInit(script) {
    this.script = script
  }

  static ConsoleInit init(script) {
    ConsoleInit init = new ConsoleInit(script)
    init.graph()
    init.schema()
    init.dsl()
	return init
  }

  /**
   * Set the g global with the graph contents.
   */
  private def graph() {
    // First lets just see if we're already in a graph scope like on the web console.
    // Try initializing like we're in rexster command line console.
    try {
	  if (script.g) {
		println "Initialized for Rexster Web Console"
		return
	  }
    } catch (MissingPropertyException e) {
	// OK .g isn't defined.  Go to next option
    }
	try {
      script.g = script.rexster.getGraph('wikidata')
      println "Initialized for Rexster Console"
    } catch (MissingPropertyException e) {
		// OK .rexster isn't defined.  Go to next option
		throw e
    }
    return script.g
//    try {
//      println "Initializing for Gremlin console"
//      script.g = new TinkerGraph('wikidata', TinkerGraph.FileType.GRAPHSON)
//    } catch (Exception e) {
//      println "Something is wrong loading or creating the graph.  Here is the exception, good luck:"
//      throw e
//    }
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
      println "Something went wrong updating the schema.  Here is the exception, good luck:"
      e.printStackTrace()
      System.exit(1)
    }
  }

  private void dsl() {
    def loader = new Loader(script.g, false)
    def dsl = new DomainSpecificLanguage(loader)
    dsl.setup()
  }

  static void loadData(script, max, procs, num, file, ignore_props=true) {
      def loader = new Loader(script.g, ignore_props)
	  new DataLoader(loader).setReaders(procs).setNum(num).gzipFile(file).read(max)
  }

  void benchmark(Closure c) {
	  def t = System.currentTimeMillis()
	  c()
	  res = (System.currentTimeMillis() - t)
	  println res
	  res
  }

  void measure(int ntimes, Closure c) {
	  def i = 0
	  def res = []
	  5.times {
		  def t = System.currentTimeMillis()
		  ntimes.times c
		  res[i] = (System.currentTimeMillis() - t)
		  i++
		  println i
	  }
	  def avg = res.sum() / res.size()
	  println res
	  println "Average: $avg"
	  println "Time: ${avg/ntimes} ms"
	  [avg: avg, times: res, time: avg/ntimes]
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
