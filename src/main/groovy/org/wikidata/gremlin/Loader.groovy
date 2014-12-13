package org.wikidata.gremlin

// Apache 2 Licensed

import com.tinkerpop.blueprints.Graph
import com.tinkerpop.blueprints.Vertex
import com.tinkerpop.blueprints.Direction
import com.thinkaurelius.titan.core.Order
import java.text.SimpleDateFormat
import com.thinkaurelius.titan.core.attribute.Geoshape

class Loader {
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
  void byVertex(v, refresh = false) {
    if (v.stub || refresh) {
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
      def v = getOrCreateVertex(id)
	  def isNew = false
	  if(v['stub']) {
		  v['stub'] = false
		  println "Creating $id"
		  isNew = true
	  } else {
		  println "Updating $id"
	  }
	  currentVertex = v
	  if(isProperty) {
    	  checkProperty(item)
      }
      updateLabels(v, item, isNew)
      updateClaims(v, item, isNew)
      v['type'] = item['type']
	  if(item['datatype']) {
		  v['datatype'] = item['datatype']
	  }

// Not committing here to allow DataLoader to group updates	  
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
		  case 'quantity':         return String.class
		  case 'time':             return Date.class
		  default:
		  	return Object.class
	  }
  }
  
  public String getValueName(itemName)
  {
	  return itemName+"value"
  }

  public String getQualifierName(itemName)
  {
	  return itemName+"q"
  }

  private void checkProperty(item)
  {	  
	  // According to new data model, all claims are edges
	  initEdge(item['id'])
	  // For value properties, we also need the value prop
	  if(item['datatype'] && item['datatype'] != 'wikibase-item') {
		  initProperty(getValueName(item['id']), getDataType(item['datatype']))
	  }
  }

  private def getOrCreateVertex(id) 
  {
    def v = g.V('wikibaseId', id)
    if ( v ) {
      return v.next()
    }
	
    return g.addVertex([wikibaseId: id, stub: true])
  }

  private void updateLabels(v, item, isNew) 
  {
	// clean labels that do not exist in item
	/* TODO: for now, we just wipe the properties clean and reinstate them.
	In the future, we might want to have more intelligent strategies for updates */
	if(!isNew) {
		for(p in v.getProperties()) {
			def l = p.getPropertyKey()?.getName();
			if(l && l.length() > 5 && l[0..4] == 'label' && !(l in item.labels)) {
				v.removeProperty(l)
			}
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
/*	TODO: do we want descriptions?
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
*/    
  }

  private void updateClaims(v, item, isNew) {
	def claimsById = [:]
    for (claimsOnProperty in item.claims) {
		if(!claimsOnProperty.value.size()) {
			// empty claim, ignore
			continue
		}
      	for (claim in claimsOnProperty.value) {
	        if (claim.mainsnak == null) {
	          println "${item.id}'s ${property} contains a claim without a mainSnak.  Skipping."
	          continue
	        }
			if (claim.rank == "deprecated") {
				// ignore deprecated claims for now
				continue
			}
			claimsById[claim.id] = claim
		}
	}
	if(!isNew) {
		for(cl in v.out('claim')) {
			if(!(cl.wikibaseId in claimsById)) {
				println "Dropping old claim {$cl.wikibaseId}"
				cl.remove()
			}
		}
	}
    if (!item.claims) {
      return
    }
	for(claim in claimsById) {
		updateClaim(v, claim.value)
	}
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

  private void addQualifiers(item, qualifiers) {
	  for(q in qualifiers) {
		  if(!q.value) {
			  continue
		  }
		  def qname = q.key
		  for(qitem in q.value) {
			  def value = extractPropertyValueFromClaim(qitem)
			  def outV = getTargetFromSnak(qitem, value)
			  def edge = item.addEdge(qname, outV)
			  edge.edgeType = 'qualifier'
			  if(value) {
				  edge[getValueName(qname)] = value
				  if(qitem.snaktype == 'value') {
					  addAllValues(qitem.datavalue.value, qname, edge)
				  }
			  }
		  }
	  }
  }
  
  private def getTargetFromSnak(data, value)
  {
      def outgoing
      switch (data.snaktype) {
      case 'value':
	  	
  		if(data.datatype == 'wikibase-item') {
  			outgoing = getOrCreateVertex('Q' + data.datavalue.value[ 'numeric-id' ])
  		} else {
			if(value == null) {
				// if we are supposed to have a specific value, but could not find it
				// we produce "somevalue" link instead
				outgoing = g.V('specialValueNode', 'unknown').next()
			} else {
				outgoing = getOrCreateVertex(data.property)
			}
  		}
        break
      case 'somevalue':
        outgoing = g.V('specialValueNode', 'unknown').next()
        break
      case 'novalue':
        outgoing = g.V('specialValueNode', 'novalue').next()
        break
      default:
        println "Unknown snaktype on ${v.wikibaseId}:  ${claim.snaktype}.  Skipping."
        return null
      }
	  return outgoing
  }
  
  private void addAllValues(datavalue, property, item)
  {
	  if(!(datavalue instanceof HashMap)) {
		  return
	  }
	  for(it in datavalue) {
		  if(!it.value) {
			  continue
		  }
		  item[getValueName(property)+"_"+it.key] = it.value
	  }
  }
  
  private def updateClaim(v, claim) 
  {
  	def claimV = getOrCreateVertex(claim.id)
  	if(claimV.stub) {
  		// new one
  		claimV.type = 'claim'
  		claimV.stub = false
  	} else {
		//println "Skipping old claim ${claim.id}"
  		// we already have this claim
  		return
  	}
	//println "Adding claim ${claim.id}: $claim"
    // This claim is an edge so select the outoing edge:
	def data = claim.mainsnak
	def isValue = (data.datatype != 'wikibase-item')
	def value = extractPropertyValueFromClaim(data)
    def outgoing = getTargetFromSnak(claim.mainsnak, value)
	if(!outgoing) {
		return null
	}

	claimV['rank'] = (claim.rank == "preferred")
	
	def edgeIn = v.addEdge(data.property, claimV)
	// Here is the strange thing: v.out() is slow but v.out('claim') is fast even though they return the same
	// So we provide edge for all claims so we could fetch them e.g. for deleting old ones
	v.addEdge('claim', claimV)
	def edgeOut = claimV.addEdge(data.property, outgoing)
	edgeOut.edgeType = 'claim'
	if(isValue && value) {
		// TODO: choose which one is better here
		claimV[getValueName(data.property)] = value
		edgeOut[getValueName(data.property)] = value
		if(data.snaktype == 'value' && data.datatype != 'wikibase-item' && data.datatype != 'string') {
			addAllValues(data.datavalue.value, data.property, claimV)
			addAllValues(data.datavalue.value, data.property, edgeOut)
		}
	}
    if(claim.qualifiers) {
	  // println "Adding qualifiers to $edge"
	  addQualifiers(claimV, claim.qualifiers)
    }
	return claimV
  }

  private def extractPropertyValueFromClaim(claim) {
	  if (claim.snaktype != 'value') {
		  return null
	  }
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
			case 'wikibase-item':    return null
		    default:
		      println "Unknown datatype on ${getCurrentVertex()?.wikibaseId}: ${claim.datatype}.  Skipping."
		      return null
	    }
	} catch(Exception e) {
		println "Value parsing failed on ${getCurrentVertex()?.wikibaseId}: ${claim.datatype} with: $e"
		return null
	}
  }

  private def extractPropertyValueFromGlobeCoordinate(coord) {
    // Has latitude, longitude, alt, precision, globe
    return Geoshape.point(coord.latitude as double, coord.longitude as double)
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
		return null
	}
	//def df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
    //return df.parse(time) 
	Date.parse("yyyy-MM-dd HH:mm:ss", "$y-${matches[0][2]}-${matches[0][3]} ${matches[0][4]}:${matches[0][5]}:${matches[0][6]}") 
  }

  private def initProperty(name, dataType=Object.class) {
	def s = new Schema(g)
    def mgmt = g.getManagementSystem()
	s.addProperty(mgmt, name, dataType)
    /* TODO: we should use a mixed index here to support range queries but those need Elasticsearch
    //mgmt.buildIndex(indexName, Vertex.class).addKey(propertyKey).buildCompositeIndex()
    // This does not commit the graph transaction - just the management one */
    mgmt.commit()
  }

  private def initEdge(name) {
  	def s = new Schema(g)
    def mgmt = g.getManagementSystem()
    def wikibaseId = s.addProperty(mgmt, 'wikibaseId', String.class)
    def rank = s.addProperty(mgmt, 'rank', Boolean.class)
    def edgeType = s.addProperty(mgmt, 'edgeType', String.class)
    def label = s.addEdgeLabel(mgmt, name, rank, wikibaseId, edgeType)

    s.addVIndex(mgmt, label, "by_"+name, wikibaseId, rank, edgeType)
    // This does not commit the graph transaction - just the management one
    mgmt.commit()
  }
}
