package org.wikidata.gremlin

import com.tinkerpop.gremlin.groovy.Gremlin
import com.tinkerpop.blueprints.Graph
import com.tinkerpop.pipes.Pipe

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
      delegate.out('P31').has('type', 'claim').out('P31').has('wikibaseId', id).hasNext()
    }
    Gremlin.addStep('isOneOf')
    Pipe.metaClass.isOneOf = { ids ->
      delegate.out('P31').has('type', 'claim').out('P31').has('wikibaseId', T.in, ids).hasNext()
    }
	// Get claim edges for property - used for values
	// this produces list of outgoing claim edges
    Gremlin.addStep('claimValues')
    Pipe.metaClass.claimValues = { prop ->
      delegate.out(prop).has('type', 'claim')
    }
	// Get claim vertices for property - used for links
	// this produces list of claimed vertices
    Gremlin.addStep('claimVertices')
    Pipe.metaClass.claimVertices = { prop ->
      delegate.out(prop).has('type', 'claim').outE(prop).has('edgeType', 'claim').inV
    }
	// Produces list of entities that are instances of this class
	// E.g. g.listOf('Q5') are humans
	Gremlin.addStep('listOf')
	Pipe.metaClass.listOf = { 
		delegate.V('wikibaseId', it).in('P31').has('type', 'claim').in('P31')
	}
	// Produces list of instances of the pipeline
	// E.g. g.wd('Q5').instances() are humans
	Gremlin.addStep('instances')
	Pipe.metaClass.instances = { 
		delegate.in('P31').has('type', 'claim').in('P31')
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
		delegate.as('c_props').outE('P582').has('edgeType', 'qualifier').has('P582value', T.lt, new Date())
			.back('c_props').as('c_old').optional('c_props').except('c_old')
	}
	// produce 'latest' value among the set of properties
	// Returns claim nodes with the latest data
	// Uses P585 (point in time)
	Gremlin.addStep('latest')
	Pipe.metaClass.latest = { 
		delegate.copySplit(
			_().filter{!it.outE('P585').has('edgeType', 'qualifier').hasNext()}, // does not have PiT
			_().as('l_props').outE('P585').has('edgeType', 'qualifier').order{it.b.P585value <=> it.a.P585value}[0].back('l_props')
		).exhaustMerge()
	}
	// Resolve unknown territorial entity to country item(s)
	// g.wd('Q1013639').toCountry() returns vertex for Q33/Finland
    Gremlin.addStep('toCountry')
    Pipe.metaClass.toCountry = {
		delegate.as('loopstep').copySplit(
			_().filter{it.isA('Q6256')},
		 	_().claimVertices('P17'),
			_().claimVertices('P131')
		).exhaustMerge.dedup.refresh().loop('loopstep'){it.loops < 10 && !it.object.isA('Q6256')}
	}
	// Produce list of id-name pairs, mostly for human inspection
	Gremlin.addStep('namesList')
	Pipe.metaClass.namesList = {
		delegate.transform({[id: it.wikibaseId, name: it.labelEn]})
	}
  }
}