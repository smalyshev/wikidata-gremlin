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
      delegate.sideEffect{it.stub=true; loader.byVertex(it)}
    }
    Gremlin.addStep('isA')
    Pipe.metaClass.isA = { id ->
      delegate.out('P31').has('wikibaseId', id).hasNext()
    }
	// Produces list of entities that are instances of this class
	// E.g. g.listOf('Q5') are humans
	Gremlin.addStep('listOf')
	Pipe.metaClass.listOf = { 
		delegate.V('wikibaseId', it).in('P31')
	}
	// if the list has elements ranked "preferred", take them, otherwise take all
	Gremlin.addStep('preferred')
	Pipe.metaClass.preferred = { prop ->
		delegate.ifThenElse{it.outE(prop).has('rank', true).hasNext()}{it.outE(prop).has('rank', true)}{it.outE(prop)}
	}
	
    Gremlin.addStep('toCountry')
    Pipe.metaClass.toCountry = {
      // If this place _is_ a country the return it
      delegate.as('next').ifThenElse{it.isA('Q6256')}{
        it
      }{
        // If this place has a country then return that
        it.ifThenElse{it.out('P17').hasNext()}{
          it.out('P17').refresh()
        }{
          // Otherwise follow "is in the administrative territorial entity"
          it.out('P131').refresh().loop('next'){it && it.loops < 10}
        }
      }
    }
  }
}