package org.wikidata.gremlin

import com.tinkerpop.gremlin.groovy.Gremlin
import com.tinkerpop.blueprints.Graph
import com.tinkerpop.pipes.Pipe
import org.apache.commons.lang.RandomStringUtils
// Apache 2 Licensed

class DomainSpecificLanguage {
  final Loader loader

  DomainSpecificLanguage(Loader loader) {
    this.loader = loader
  }

  void setup() {
    Gremlin.addStep('wd')
    Graph.metaClass.wd = { id ->
      loader.byId(id)
      return delegate.V('wikibaseId', id)
    }
    Gremlin.addStep('refresh')
    Pipe.metaClass.refresh = {
      delegate.sideEffect{loader.byVertex(it)}
    }
    Gremlin.addStep('reload')
    Pipe.metaClass.reload = {
      delegate.sideEffect{loader.byVertex(it, true)}
    }
    Gremlin.addStep('isA')
    Pipe.metaClass.isA = { id ->
      delegate.out('P31').has('wikibaseId', id).hasNext()
    }
    Gremlin.addStep('isOneOf')
    Pipe.metaClass.isOneOf = { ids ->
      delegate.out('P31').has('wikibaseId', T.in, ids).hasNext()
    }
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
		delegate.V('wikibaseId', it).in('P31')
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
		def mark = RandomStringUtils.random(10, true, true)
		delegate.copySplit(
			_().filter{!it['P585q']}, // does not have PiT
			_().order{it.b.P585q <=> it.a.P585q}[0]
		).exhaustMerge()
	}
	// Resolve unknown territorial entity to country item(s)
	// g.wd('Q1013639').toCountry() returns vertex for Q33/Finland
    Gremlin.addStep('toCountry')
    Pipe.metaClass.toCountry = {
		def mark = RandomStringUtils.random(10, true, true)
		delegate.ifThenElse{it.isA('Q6256')}{it}{
			it.as(mark).out('P17', 'P131').refresh().loop(mark){it.loops < 10 && !it.object.isA('Q6256')}.dedup
		}
	}
	// Produce list of id-name pairs, mostly for human inspection
	Gremlin.addStep('namesList')
	Pipe.metaClass.namesList = {
		delegate.transform({[id: it.wikibaseId, name: it.labelEn]})
	}
  }
}