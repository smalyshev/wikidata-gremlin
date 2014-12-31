w = org.wikidata.gremlin.RexsterInit.init(this)
// Utility function for Rexster console
groovy.lang.Script.metaClass.gg = {
        rexster.getGraph('wikidata')
}

