package org.wikidata.gremlin

// Apache 2 Licensed

import com.tinkerpop.blueprints.Graph
import com.tinkerpop.blueprints.Vertex
import com.tinkerpop.pipes.Pipe

class Loader {
  final Graph g
  boolean skip_props

  Loader(Graph g, skip_props = true) {
    this.g = g
	this.skip_props = skip_props
  }

  /**
   * Load a wikidata item by id if it isn't in the database.
   */
  void byId(id) {
    def v = g.V('wikibaseId', id)
    if (!v.hasNext() || v.next().stub) {
      refreshWikidataItem(id)
    }
  }

  /**
   * Loads a vertex from wikibase if it is a stub.
   */
  void byVertex(v) {
    if (v.stub) {
      refreshWikidataItem(v.wikibaseId)
    }
  }

  /**
   * Refresh a wikidata item by id.
   */
  private void refreshWikidataItem(id) {
    def item = fetchEntity(id)
	loadFromItem(item)
  }
  
  public void loadFromItem(item) {
	  def id = item['id']
	  def isProperty = (id[0] == 'P')
	  if(isProperty && skip_props) {
		  return
	  }
      def v = getOrCreateVertexForItem(id)
	  if(isProperty) {
    	  checkProperty(item)
      }
      updateLabels(v, item)
      updateClaims(v, item)
      v['type'] = item['type']
	  if(item['datatype']) {
		  v['datatype'] = item['datatype']
	  }
	  
      if (g.getFeatures().supportsTransactions) {
        g.commit()
      }
  }

  private def fetchEntity(id) {
	  println "Fetching ${id} from Wikidata"
    def text = new URL("http://www.wikidata.org/wiki/Special:EntityData/${id}.json").getText('UTF-8')
	//println "Loaded id $id, got this: "+groovy.json.JsonOutput.prettyPrint(text)
    def items = new groovy.json.JsonSlurper().parseText(text)
    return items.entities[ id ]
  }

  private void checkProperty(item)
  {
	  if(item['datatype'] && item['datatype'] == 'wikibase-item') {
		  initEdge(item['id'])
	  } else {
		  initProperty(item['id'])
	  }
  }

  private def getOrCreateVertexForItem(id) {
    def v = g.V('wikibaseId', id)
    if ( v ) {
      println "Updating $id."
      v = v.next()
      if (v.stub) {
        v.stub = false
      }
      return v
    }
    println "Creating $id."
	
    return g.addVertex([wikibaseId: id])
  }

  private void updateLabels(v, item) {
    if (!item.labels) {
      // TODO clear all labels
      return
    }
    for (label in item.labels) {
		def l = 'label' + label.key.capitalize()
      	try {
			v[l] = label.value.value
		} catch(java.lang.IllegalArgumentException e) {
			initProperty(l, label.value.value.class)
			v[l] = label.value.value
		}
    }
	if(!item.descriptions) {
		return
	}
    for (description in item.descriptions) {
		def l = 'desc' + description.key.capitalize()
      	try {
			v[l] = description.value.value
		} catch(java.lang.IllegalArgumentException e) {
			initProperty(l, description.value.value.class)
			v[l] = description.value.value
		}
    }
    // TODO clear labels that are set but not sent
  }

  private void updateClaims(v, item) {
    if (!item.claims) {
      // TODO clear all claims
      return
    }
    for (claimsOnProperty in item.claims) {
      def property = claimsOnProperty.key
      for (claim in claimsOnProperty.value) {
        claim = claim.mainsnak
        if (claim == null) {
          println "${item.id}'s ${property} contains a claim without a mainSnak.  Skipping."
          continue
        }
        boolean isEdge
        if (claim.datatype) {
          isEdge = claim.datatype == 'wikibase-item'
        } else {
          isEdge = inferIsEdgeFromProperty(property)
        }
        if (isEdge) {
          updateEdgeClaim(v, claim)
        } else {
          updatePropertyClaim(v, claim)
        }
      }
    }
    // TODO cleanup extra properties and outgoing edges
  }

  private boolean inferIsEdgeFromProperty(property) {
    // We use supportsTransactions as a standin for supporting getManagementSystem.....
    if (g.getFeatures().supportsTransactions) {
      def relationType = g.getManagementSystem().getRelationType(property)
      if (relationType) {
        return relationType.isEdgeLabel()
      }
    }
	def prop = g.V('wikibaseId', property)
    // Property doesn't yet exist as either an edge label or property or we're on the
    // gremlin console so lets ask wikidata. This isn't to inefficient because we'll
    // create it soon (hopefully).
	if(!prop) {
		println "Asking wikidata about $property."
		refreshWikidataItem(property)
		prop = g.V('wikibaseId', property).next()
	} else {
		prop = prop.next()
	}
//    def prop = fetchEntity(property)
    return prop['datatype'] == 'wikibase-item'
  }

  private void updateEdgeClaim(v, claim) {
    // This claim is an edge so select the outoing edge:
    def outgoing
    switch (claim.snaktype) {
    case 'value':
      def outgoingId = 'Q' + claim.datavalue.value[ 'numeric-id' ]
      outgoing = g.V('wikibaseId', outgoingId)
      if (outgoing) {
        outgoing = outgoing.next()
        break
      }
      //println "Creating ${outgoingId} as a stub."
      outgoing = g.addVertex([wikibaseId: outgoingId, stub: true])
      break
    case 'somevalue':
      outgoing = g.V('specialValueNode', 'unknown').next()
      break
    case 'novalue':
      outgoing = g.V('specialValueNode', 'novalue').next()
      break
    default:
      println "Unknown snaktype on ${v.wikibaseId}:  ${claim.snaktype}.  Skipping."
      return
    }
	if(!g.getEdgeLabel(claim.property)) {
		g.makeEdgeLabel(claim.property).make()
		g.commit()
	}
    // Don't add it if it doesn't exist
    if (!v.out(claim.property).retain([outgoing])) {
	  // println "Adding edge ${claim.property} to $outgoing"
      v.addEdge(claim.property, outgoing)
	  //byId(claim.property)
    }
  }

  private def updatePropertyClaim(v, claim) {
    // This claim is a property
    initProperty(claim.property)

    // Pick a value for the property
    def value
    switch (claim.snaktype) {
    case 'value':
      // TODO this is a simplification
      value = extractPropertyValueFromClaim(v, claim)
      if (value == null) {
        return
      }
      break
    case 'somevalue':
      // TODO find better sentinel values 
      value = 'somevalue'
      break
    case 'novalue':
      // TODO find better sentinel values 
      value = 'novalue'
      break
    default:
      println "Unknown snaktype on ${v.wikibaseId}:  ${claim.snaktype}.  Skipping."
      return
    }
    v[ claim.property ] = value
  }

  private def extractPropertyValueFromClaim(v, claim) {
    def value = claim.datavalue.value
    switch (claim.datatype) {
    case 'commonsMedia':     return value
    case 'globe-coordinate': return extractPropertyValueFromGlobeCoordinate(value)
    case 'monolingualtext':  return "${value.language}:${value.text}".toString()
    case 'quantity':         return value.amount
    case 'string':           return value
    case 'time':             return extractPropertyValueFromTime(v, value)
    case 'url':              return value
    default:
      println "Unkown datatype on ${v.wikibaseId}:  ${claim.datatype}.  Skipping."
      return null
    }
  }

  private def extractPropertyValueFromGlobeCoordinate(coord) {
    // Has latitude, longitude, alt, precision, globe
    return "${coord.latitude} N ${coord.longitude} W".toString() // TODO this is silly
  }

  // Format looks like   +0001783 - 12 - 23 T 00 : 00 : 00 Z without the spaces
  private timeFormat = /(sd{4,50})-(dd)-(dd)T(dd):(dd):(dd)Z/.replaceAll('s', /[+-]/).replaceAll('d', /[0-9]/)
  private def extractPropertyValueFromTime(v, time) {
    def matches = time =~ timeFormat
    if (!matches) {
      println "Error parsing date on ${v.wikibaseId}:  ${time}.  Skipping."
      return null
    }
    // TODO return the time value.....
  }

  private def initProperty(name, dataType=Object.class) {
    // We use supportsTransactions as a standin for supporting getManagementSystem.....
    if (!g.getFeatures().supportsTransactions) {
      return
    }
    def propertyKey = g.getManagementSystem().getPropertyKey(name);
    if (propertyKey != null) {
      return
    }
    println "Creating property $name."
	//byId(name)
    def mgmt = g.getManagementSystem()
    //def dataType = Object.class // TODO figure out the right type
    propertyKey = mgmt.makePropertyKey(name).dataType(dataType).make()
    def indexName = "by_${name}"
    // TODO we should use a mixed index here to support range queries but those need Elasticsearch
    //mgmt.buildIndex(indexName, Vertex.class).addKey(propertyKey).buildCompositeIndex()
    // This does not commit the graph transaction - just the management one
    mgmt.commit()
  }

  private def initEdge(name) {
    // We use supportsTransactions as a standin for supporting getManagementSystem.....
    if (!g.getFeatures().supportsTransactions) {
      return
    }
    def mgmt = g.getManagementSystem()
    def edgeKey = mgmt.getEdgeLabel(name);
    if (edgeKey != null) {
      return
    }
    println "Creating edge $name."
	mgmt.makeEdgeLabel(name).make()
    // This does not commit the graph transaction - just the management one
    mgmt.commit()
  }
}
