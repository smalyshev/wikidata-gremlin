package org.wikidata.gremlin

import com.tinkerpop.gremlin.groovy.Gremlin
import com.tinkerpop.blueprints.Graph
import com.tinkerpop.blueprints.Vertex
import com.tinkerpop.pipes.AbstractPipe;
import com.tinkerpop.pipes.Pipe;
import com.tinkerpop.pipes.transform.TransformPipe;
import org.apache.commons.lang.RandomStringUtils
import groovy.util.logging.Slf4j

// Apache 2 Licensed
@Slf4j
class DomainSpecificLanguage {
  void setup() {
    log.info "Setting up domain specific language"
    Pipe.metaClass.randomMark = {
        RandomStringUtils.random(10, true, true)
    }
    // TODO the loader_ name seems silly
    Graph.metaClass.loader_ = null
    Gremlin.addStep('loader')
    Graph.metaClass.loader = { ->
      if (delegate.loader_ == null) {
        delegate.loader_ = new Loader(delegate)
      }
      return delegate.loader_
    }
    Gremlin.addStep('wd')
    Graph.metaClass.wd = { id ->
      delegate.loader().byId(id)
      return delegate.V('wikibaseId', id)
    }
    // This exists in Tinkerpop 3 but not 2.
    Vertex.metaClass.graph = { ->
      // This probably only works in titan
      return delegate.tx().getGraph()
    }
    Gremlin.addStep('refresh')
    Pipe.metaClass.refresh = {
      delegate.sideEffect{it.graph().loader().byVertex(it)}
    }
    Gremlin.addStep('reload')
    Pipe.metaClass.reload = {
      delegate.sideEffect{it.graph().loader().byVertex(it, true)}
    }
    Gremlin.addStep('isA')
    Pipe.metaClass.isA = { id ->
      delegate.has('P31link', CONTAINS, id).hasNext()
    }
    Vertex.metaClass.isA = { id ->
        id in delegate.P31link
    }
//    Gremlin.addStep('isOneOf')
//    Pipe.metaClass.isOneOf = { ids ->
//      delegate.has('P31link', T.in, ids).hasNext()
//    }
//    Vertex.metaClass.isOneOf = { ids ->
//      ids.intersect(delegate.P31link) != []
//    }
    // Get claim edges for property - used for values
    // this produces list of outgoing claim edges
    Gremlin.addStep('claimValues')
    Pipe.metaClass.claimValues = { prop ->
      delegate.outE(prop)
    }
    // Get claim vertices for property - used for links
    // this produces list of claimed vertices
    Gremlin.addStep('claimVertices')
    Pipe.metaClass.claimVertices = { prop ->
      delegate.out(prop)
    }
    // Produces list of entities that are instances of this class
    // E.g. g.listOf('Q5') are humans
    Gremlin.addStep('listOf')
    Pipe.metaClass.listOf = {
        delegate.V('P31link', it)
    }
    // Produces list of instances of the pipeline
    // E.g. g.wd('Q5').instances() are humans
    Gremlin.addStep('instances')
    Pipe.metaClass.instances = {
        delegate.in('P31')
    }
    // if the list has elements ranked "preferred", take them, otherwise take all
    // this produces list of claims
    Gremlin.addStep('preferred')
    Pipe.metaClass.preferred = {
        delegate.ifThenElse{it.has('rank', true).hasNext()}{it.has('rank', true)}{it}
    }
    // produce 'current' value among the set of properties
    // Returns list of claim nodes that are current
    // Uses P582 (end date) to check for current
    Gremlin.addStep('current')
    Pipe.metaClass.current = {
        def mark = RandomStringUtils.random(10, true, true)
        delegate.as(mark).has('P582q', T.lt, new Date()).as(mark+"old").optional(mark).except(mark+"old")
    }
    // produce 'latest' value among the set of properties
    // Returns claim nodes with the latest data
    // Uses P585 (point in time)
    Gremlin.addStep('latest')
    Pipe.metaClass.latest = {
        def mark =
        delegate.copySplit(
            _().filter{!it['P585q']}, // does not have PiT
            _().order{it.b.P585q <=> it.a.P585q}[0]
        ).exhaustMerge()
    }
    // Resolve unknown territorial entity to country item(s)
    // g.wd('Q1013639').toCountry() returns vertex for Q33/Finland
    Gremlin.addStep('toCountry')
    Pipe.metaClass.toCountry = {
        delegate.treeFind('Q6256', 'P17', 'P131')
    }
    // Assemble all vertices on a tree in in/out direction
    Gremlin.defineStep('treeOut', [Pipe, Vertex], { String... props -> new TreePipe(TreePipe.Direction.OUT, *props) })
    Gremlin.defineStep('treeIn', [Pipe, Vertex], { String... props -> new TreePipe(TreePipe.Direction.IN, *props) })

    // Find a vertex of type "id" in a tree, using outs as links
    Gremlin.addStep('treeFind')
    Pipe.metaClass.treeFind = { String id, String... outs ->
      def mark = delegate.randomMark()
      delegate.ifThenElse{it.isA(id)}{it}{
        it.as(mark).out(*outs).loop(mark){it.loops < 20 && !it.object.isA(id) && it.object.out(*outs).hasNext() }
          .filter{it.isA(id)}.dedup()
      }
    }
    // Dump the data to a JSON file
    // this returns null so not chainable
    Gremlin.addStep('dump')
    Pipe.metaClass.dump = { String filename, String... props ->
        def q = new QueryEngine()
        q.dump(filename, delegate, *props)
        null
    }

    // Produce list of id-name pairs, mostly for human inspection
    Gremlin.addStep('namesList')
    Pipe.metaClass.namesList = {
        delegate.transform({[id: it.wikibaseId, name: it.labelEn]})
    }
  }
}
