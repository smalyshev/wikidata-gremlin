package org.wikidata.gremlin

// Apache 2 Licensed

import com.tinkerpop.blueprints.Graph
import com.tinkerpop.blueprints.Vertex
import java.text.SimpleDateFormat
import com.thinkaurelius.titan.core.attribute.Geoshape

class Loader {
  final String QUALIFIER_PROPERTY = "_qualifiers"
  final String PAST_SUFFIX = "_all"
	
  final Graph g
  boolean skip_props
  private def currentVertex

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
	if (g.getFeatures().supportsTransactions) {
		// We do commit here since it's one-off update
		g.commit()
	}
  }
  
  public void loadFromItem(item) {
	  def id = item['id']
	  def isProperty = (id[0] == 'P')
	  if(isProperty && skip_props) {
		  return
	  }
      def v = getOrCreateVertexForItem(id)
	  currentVertex = v
	  if(isProperty) {
    	  checkProperty(item)
      }
      updateLabels(v, item)
      updateClaims(v, item)
      v['type'] = item['type']
	  if(item['datatype']) {
		  v['datatype'] = item['datatype']
	  }

// Not committing here to allow DataLoader to group updates	  
//      if (g.getFeatures().supportsTransactions) {
//        g.commit()
//      }
  }

  private def fetchEntity(id) {
	  println "Fetching ${id} from Wikidata"
	  def text = new URL("http://www.wikidata.org/wiki/Special:EntityData/${id}.json").getText('UTF-8')
	  //println "Loaded id $id, got this: "+groovy.json.JsonOutput.prettyPrint(text)
	  def items = new groovy.json.JsonSlurper().parseText(text)
	  return items.entities[ id ]
  }

  private Class getDataType(wikitype)
  {
	  // TODO: figure out how to make types for with novalue/somevalue
	  switch(wikitype) {
		  case 'string':
		  case 'monolingualtext':
		  case 'url':
		  case 'commonsMedia':     return String.class
		  case 'globe-coordinate': return Geoshape.class
		  // TODO: which class we have to use here? Maybe BigInteger?
		  case 'quantity':         return long.class
		  case 'time':             return Date.class
		  default:
		  	return Object.class
	  }
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

  private void updateLabels(v, item) 
  {
	// clean labels that do not exist in item
	for(p in v.getProperties()) {
		def l = p.getPropertyKey()?.getName();
		if(l && l.length() > 5 && l[0..4] == 'label' && !(l in item.labels)) {
			v.removeProperty(l)
		}
	}
    if (!item.labels) {
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
/*	if(!item.descriptions) {
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
*/    
  }

  private void updateClaims(v, item) {
  	for(p in v.getProperties()) {
  		def l = p.getPropertyKey()?.getName();
  		if(l && l.length() > 1 && l[0] == 'P') {
  			v.removeProperty(l)
  		}
  	}
  	for(e in v.outE()) {
  		def l = e.getEdgeLabel().getName()
  		if(l && l.length() > 1 && l[0] == 'P') {
  			e.remove()
  		}
  	}
	
    if (!item.claims) {
      return
    }
    for (claimsOnProperty in item.claims) {
		if(!claimsOnProperty.value.size()) {
			continue
		}
		def property = claimsOnProperty.key
		def firstClaim = claimsOnProperty.value[0]
        boolean isEdge
		// Here we are assuming claims are homogenuos at least to the measure of 
		// not mixing edges and properties under the same name, since our import data model
		// does not support such thing
        if (firstClaim.datatype) {
          isEdge = firstClaim.datatype == 'wikibase-item'
        } else {
          isEdge = inferIsEdgeFromProperty(property)
        }
		def updater = isEdge?this.&updateEdgeClaim:this.&updatePropertyClaim
		def currentClaim = null
      	for (claim in claimsOnProperty.value) {
	        if (claim.mainsnak == null) {
	          println "${item.id}'s ${property} contains a claim without a mainSnak.  Skipping."
	          continue
	        }
			// TODO: handle references
			// TODO: we should distinguish between current data and past data by qualifiers
			if(claim.qualifiers && claim.qualifiers['P585']) {
				// point in time qualifier - we should only record the last one
				def claimTime = extractPropertyValueFromTime(claim.qualifiers['P585'][0]?.datavalue?.value?.time)
				if(claimTime && (claimTime instanceof Date)) {
					claim._time = claimTime
					if(!currentClaim || currentClaim._time < claimTime) {
						currentClaim = claim
					}
					// record this as past claim
				} else {
					println "${item.id}'s ${property} has quailifier P585 (point-in-time) but claim.qualifiers['P585'][0] does not parse as time.  Skipping."
				}
				addPastClaim(v, claim, isEdge, claimTime)
				continue
			}
			// End date - if it's lower than now it's a past claim
			// Note that if we don't have a value it's OK to pass it, we assume it's current
			if(claim.qualifiers && claim.qualifiers['P582'] && claim.qualifiers['P582'][0]?.snaktype == 'value') {
				def claimTime = extractPropertyValueFromTime(claim.qualifiers['P582'][0]?.datavalue?.value?.time)
				addPastClaim(v, claim, isEdge, claimTime)
				if(!claimTime || !(claimTime instanceof Date)) {
					println "${item.id}'s ${property} has quailifier P582 but claim.qualifiers['P582'][0] does not parse as time.  Skipping."
				}
				if(claimTime < (new Date())) {
					continue
				} 
			}
			updater(v, claim.mainsnak, claim.qualifiers)
		}
		if(currentClaim) {
			updater(v, currentClaim.mainsnak, currentClaim.qualifiers)
		}
    }
    // TODO cleanup extra outgoing edges
  }

  private boolean inferIsEdgeFromProperty(property) {
	  def relationType = g.getRelationType(property)
      if (relationType) {
        return relationType.isEdgeLabel()
      }
	def prop = g.V('wikibaseId', property)
	// This should never happen if we do import properly, e.g.
	// 1. Extract properties
	// 2. Import properties
	// 3. Import non-property data
	// But if we mess up, we have a fallback here
	if(!prop) {
		println "Asking wikidata about $property."
		refreshWikidataItem(property)
		prop = g.V('wikibaseId', property).next()
	} else {
		prop = prop.next()
	}
    return prop['datatype'] == 'wikibase-item'
  }

  private void removeClaim(v, claim, isEdge)
  {
	if(!isEdge) {
		v.removeProperty(claim.property)
	} else {
        def outgoingId = 'Q' + claim.datavalue.value[ 'numeric-id' ]
		def edge = v.outE(claim.property).as('edge').inV.has('wikibaseId', outgoingId).back('edge')
		if (edge.hasNext()) {
			edge = edge.next()
			edge.remove()
		}
	}
  }

  private void addPastClaim(v, claim, isEdge, time) {
	  // TODO: how to add past claims?
	  // For now, remove it as current claim
	  removeClaim(v, claim.mainsnak, isEdge)
	  if(0 && isEdge) {
		  def claimCopy = claim.mainsnak.clone()
		  claimCopy.property += PAST_SUFFIX
		  println "Adding past $claimCopy.property to $v: $claimCopy"
		  updateEdgeClaim(v, claimCopy, claim.qualifiers)
	  }
  }

  private void addQualifiers(item, qualifiers) {
	  for(q in qualifiers) {
		  if(!q.value) {
			  continue
		  }
		  def qname = q.key
		  item[qname] = []
		  for(qitem in q.value) {
			  def value
			  if(qitem.snaktype != "value") {
				  value = qitem.snaktype
			  } else {
				  if(qitem.datatype == 'wikibase-item') {
					  value = "Q"+qitem.datavalue?.value['numeric-id']
				  } else {
					  value = extractPropertyValueFromClaim(qitem)
				  }
			  }
			  item[qname] << value
		  }
	  }
  }

  private void updateEdgeClaim(v, claim, qualifiers=null) {
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
/*	TODO: enable for batch loading?
	if(!g.getEdgeLabel(claim.property)) {
		g.makeEdgeLabel(claim.property).make()
		g.commit()
	}
*/    // Don't add it if it already exists
	def edge = v.outE(claim.property).as('edge').inV.retain([outgoing]).back('edge')
	if(edge.hasNext()) {
		edge = edge.next()
	} else {
		// println "Adding edge ${v.wikibaseId}--${claim.property}-->${outgoing.wikibaseId}"
		edge = v.addEdge(claim.property, outgoing)
	}
    if(qualifiers) {
	  // println "Adding qualifiers to $edge: $qualifiers"
	  edge[QUALIFIER_PROPERTY] = [:]
	  addQualifiers(edge[QUALIFIER_PROPERTY], qualifiers)
    }
  }

  private def updatePropertyClaim(v, claim, qualifiers = null) {
    // Pick a value for the property
    def value
    switch (claim.snaktype) {
    case 'value':
      // TODO this is a simplification
      value = extractPropertyValueFromClaim(claim)
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
    if(qualifiers) {
	  // println "Adding qualifiers to $edge: $qualifiers"
	  def qname = claim.property+QUALIFIER_PROPERTY
	  v[qname] = [:]
	  addQualifiers(v[qname], qualifiers)
    }
	
  }

  private def extractPropertyValueFromClaim(claim) {
    def value = claim.datavalue.value
	try {
	    switch (claim.datatype) {
		    case 'commonsMedia':     return value
		    case 'globe-coordinate': return extractPropertyValueFromGlobeCoordinate(value)
		    case 'monolingualtext':  return "${value.language}:${value.text}".toString()
		    case 'quantity':         return value.amount
		    case 'string':           return value
		    case 'time':             return extractPropertyValueFromTime(value.time)
		    case 'url':              return value
		    default:
		      println "Unkown datatype on ${getCurrentVertex()?.wikibaseId}: ${claim.datatype}.  Skipping."
		      return null
	    }
	} catch(Exception e) {
		println "Value parsing failed on ${getCurrentVertex()?.wikibaseId}: ${claim.datatype} with: $e"
		return null
	}
  }

  private def extractPropertyValueFromGlobeCoordinate(coord) {
    // Has latitude, longitude, alt, precision, globe
    return Geoshape.point(coord.latitude, coord.longitude)
  }
  
  private def getCurrentVertex() {
	  return currentVertex
  }

  // Format looks like   +0001783 - 12 - 23 T 00 : 00 : 00 Z without the spaces
  private timeFormat = /(sd{4,50})-(dd)-(dd)T(dd):(dd):(dd)Z/.replaceAll('s', /[+-]/).replaceAll('d', /[0-9]/)
  private def extractPropertyValueFromTime(time) {
    def matches = time =~ timeFormat
    if (!matches) {
      println "Error parsing date on ${getCurrentVertex()?.wikibaseId}:  ${time}.  Skipping."
      return null
    }
	int y = matches[0][1] as long
	if(y < 0) {
		// TODO: Java is not good with handling BC, need better solution
		return "somevalue"
	}
	//def df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    //return df.parse(time) 
	Date.parse("yyyy-MM-dd HH:mm:ss", "$y-${matches[0][2]}-${matches[0][3]} ${matches[0][4]}:${matches[0][5]}:${matches[0][6]}") 
  }

  private def initProperty(name, dataType=Object.class) {
    // We use supportsTransactions as a standin for supporting getManagementSystem.....
    if (!g.getFeatures().supportsTransactions) {
      return
    }
    def mgmt = g.getManagementSystem()
    def propertyKey = mgmt.getPropertyKey(name);
    if (propertyKey != null) {
		mgmt.rollback()
		return
    }
    println "Creating property $name."
    propertyKey = mgmt.makePropertyKey(name).dataType(dataType).make()
    // def indexName = "by_${name}"
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
		mgmt.rollback()
		return
    }
    println "Creating edge $name."
	mgmt.makeEdgeLabel(name).make()
    // This does not commit the graph transaction - just the management one
    mgmt.commit()
  }
}
