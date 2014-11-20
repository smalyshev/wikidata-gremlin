// Apache 2 Licensed

package org.wikidata.gremlin

import com.tinkerpop.blueprints.impls.tg.TinkerGraph

class ConsoleInit {
  final def script

  private ConsoleInit(script) {
    this.script = script
  }

  static void init(script) {
    ConsoleInit init = new ConsoleInit(script)
    init.graph()
    init.schema()
    init.dsl()
  }

  /**
   * Set the g global with the graph contents.
   */
  private void graph() {
    // First lets just see if we're already in a graph scope like on the web console.
    if (script.g) {
      println "Initialized for Rexster Web Console"
      return
    }
    // Try initializing like we're in rexster command line console.
    try {
      script.g = rexster.getGraph('graph')
      println "Initialized for Rexster Console"
    } catch (MissingPropertyException e) {
      // OK rexster isn't defined.  Lets assume we're in the basic gremlin console.
    }
    try {
      println "Initializing for Gremlin console"
      script.g = new TinkerGraph('wikidata', TinkerGraph.FileType.GRAPHSON)
    } catch (Exception e) {
      println "Something is wrong loading or creating the graph.  Here is the exception, good luck:"
      e.printStackTrace()
      System.exit(1)
    }
  }

  /**
   * Setup the graph serve wikidata items.
   */
  private void schema() {
    try {
      def schema = new org.wikidata.gremlin.Schema(script.g)
      schema.setupSchema()
      schema.setupConstantData()
    } catch (Exception e) {
      println "Something went wrong updating the schema.  Here is the exception, good luck:"
      e.printStackTrace()
      System.exit(1)
    }
  }

  private void dsl() {
    def loader = new org.wikidata.gremlin.Loader(script.g)
    def dsl = new org.wikidata.gremlin.DomainSpecificLanguage(loader)
    dsl.setup()
  }
}
