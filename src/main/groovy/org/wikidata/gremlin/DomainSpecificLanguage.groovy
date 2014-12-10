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
      delegate.out(prop).has('type', 'claim').outE(prop).has('edgeType', 'claim')
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
	Pipe.metaClass.preferred = { prop ->
		delegate.ifThenElse{it.out(prop).has('rank', true).hasNext()}{it.out(prop).has('rank', true)}{it.out(prop)}
	}
	
    Gremlin.addStep('toCountry')
    Pipe.metaClass.toCountry = {
		delegate.as('loopstep').copySplit(
			_().filter{it.isA('Q6256')},
		 	_().claimVertices('P17'),
			_().claimVertices('P131')
		).exhaustMerge.dedup.refresh().loop('loopstep'){it.loops < 10 && !it.object.isA('Q6256')}
	}
	
	Gremlin.addStep('namesList')
	Pipe.metaClass.namesList = {
		delegate.transform({[id: it.wikibaseId, name: it.labelEn]})
	}
/*
      // If this place _is_ a country the return it
      delegate.as('loopstep').ifThenElse{}{
        it
      }{
        // If this place has a country then return that
        it.ifThenElse{it.out('P17').hasNext()}{
          it.claimVertices('P17').refresh()
        }{
            // Otherwise follow "is in the administrative territorial entity"
            p = it.claimVertices('P131') //.refresh()
            println p.toString()
            p.loop('loopstep'){it && it.loops < 10}
        }
      }
    }*/  
  }
}