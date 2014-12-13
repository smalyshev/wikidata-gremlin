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
  
  public String getAllValuesName(itemName)
  {
	  return itemName+"_all"
  }

  private void checkProperty(item)
  {	  
	  // According to new data model, all claims are edges
	  initEdge(item['id'])
	  // For value properties, we also need the value prop
	  if(item['datatype'] && item['datatype'] != 'wikibase-item') {
		  initProperty(getValueName(item['id']), getDataType(item['datatype']))
		  // for sub-values
		  if(item['datatype'] != 'string') {
			  initProperty(getAllValuesName(getValueName(item['id'])))
			  initProperty(getAllValuesName(getQualifierName(item['id'])))
		  }
	  }

	  initProperty(getQualifierName(item['id']), getDataType(item['datatype']))
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
			initProperty(l, String.class)
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
		for(cl in v.outE('claim')) {
			if(!(cl.wikibaseId in claimsById)) {
				println "Dropping old claim {$cl.wikibaseId}"
				v.outE(cl.property).has('wikibaseId', cl.wikibaseId).remove()
				cl.remove()
			} else {
				claimsById[cl.wikibaseId]['exists'] = true
			}
		}
	}
	// the check is here since even if item has no current claims
	// we may want to delete old ones
    if (!item.claims) {
      return
    }
	for(claim in claimsById) {
		if(claim.value['exists']) {
			// already added
			continue
		}
		for(eclaim in expand(claim.value)) {
			updateClaim(v, eclaim)
		}
	}
  }

  private void addQualifiers(item, qualifiers) {
	  for(q in qualifiers) {
		  if(!q.value) {
			  continue
		  }
		  def qname = getQualifierName(q.key)
		  for(qitem in q.value) {
			  def value = extractPropertyValueFromClaim(qitem)
			  if(value) {
				  item.setProperty(qname, value)
				  if(qitem.datatype != 'wikibase-item' && qitem.datatype != 'string') {
					  addAllValues(qitem.datavalue.value, qname, item)
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
	  def v = [:]
	  for(it in datavalue) {
		  if(!it.value) {
			  continue
		  }
		  //item[getValueName(property)+"_"+it.key] = it.value
		  v[it.key] = it.value
	  }
	  item.setProperty(getAllValuesName(property), v)
  }
  
  private def updateClaim(v, claim) 
  {
	def data = claim.mainsnak
	def value = extractPropertyValueFromClaim(data)
	def outgoing = getTargetFromSnak(claim.mainsnak, value)
	def isValue = (data.datatype != 'wikibase-item')

  	if(!outgoing) {
  		return null
  	}
	  
	def claimE = v.addEdge(data.property, outgoing)
	// Here is the strange thing: v.out() is slow but v.out('claim') is fast even though they return the same
	// So we provide edge for all claims so we could fetch them e.g. for deleting old ones
	def claimC = v.addEdge('claim', outgoing)
	claimE.setProperty('edgeType', 'claim')
	claimE.setProperty('rank', claim.rank == "preferred")
	claimE.setProperty('wikibaseId', claim.id)
	claimC.setProperty('wikibaseId', claim.id)
	claimC.setProperty('property', data.property)
	
	if(isValue && value) {
		// TODO: choose which one is better here
		claimE.setProperty(getValueName(data.property), value)
		if(data.datatype != 'string') {
			addAllValues(data.datavalue.value, getValueName(data.property), claimE)
		}
	}
    if(claim.qualifiers) {
	  // println "Adding qualifiers to $edge"
	  addQualifiers(claimE, claim.qualifiers)
    }
	return claimE
  }

  private def extractPropertyValueFromClaim(claim, allowLink = false) {
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
			case 'wikibase-item':    return allowLink?"Q"+value['numeric-id']:null
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

  private def initProperty(name, dataType=Object.class) 
  {
	  def s = new Schema(g)
	  def mgmt = g.getManagementSystem()
	  s.addProperty(mgmt, name, dataType)
      /* TODO: we should use a mixed index here to support range queries but those need Elasticsearch */
      //mgmt.buildIndex(indexName, Vertex.class).addKey(propertyKey).buildCompositeIndex()
      mgmt.commit()
  }

  private def initEdge(name) 
  {
	  def s = new Schema(g)
	  def mgmt = g.getManagementSystem()
	  def wikibaseId = s.addProperty(mgmt, 'wikibaseId', String.class)
	  def rank = s.addProperty(mgmt, 'rank', Boolean.class)
	  def label = s.addEdgeLabel(mgmt, name, rank, wikibaseId)
	  
	  s.addVIndex(mgmt, label, "by_"+name, wikibaseId, rank)
      /* TODO: we should use a mixed index here to support range queries but those need Elasticsearch */
      //mgmt.buildIndex(indexName, Vertex.class).addKey(propertyKey).buildCompositeIndex()
      mgmt.commit()
  }
  
  public def expand(claim) 
  {
  	if(!claim.qualifiers) {
		return [claim]
	}
  	def result = [claim.qualifiers]
	def newresult
	def newr
	def order = claim['qualifiers-order'].clone()
	// P580 and P582 require special handling as a pair
	//println "Processing ${claim.id} for ${claim.mainsnak.property}"
	//println "Result is now ${result.size()}"
	if(claim.qualifiers['P580']?.size() > 1 || claim.qualifiers['P582']?.size() > 1) {
		//println "Processing qualifiers P580/2"
		order.removeAll(['P580', 'P582'])
		newresult = []
		for(i in 0..<claim.qualifiers['P580'].size()) {
			for(r in result) {
				newr = r.clone()
				newr['P580'] = [claim.qualifiers['P580'][i]]
				newr['P582'] = [claim.qualifiers['P582'][i]]
				newresult << newr
			}
		}
		result = newresult
		//println "Result is now ${result.size()}"
	}
  	for(qual in order) {
		//println "Processing qualifier $qual"
  		newresult = []
  		for(qitem in claim.qualifiers[qual]) {
  			for(r in result) {
  				newr = r.clone()
  				newr[qual] = [qitem]
  				newresult << newr
  			}
  		}
  		result = newresult
		//println "Result is now ${result.size()}"
  	}
  	if(result.size() == 1) {
		return [claim]
	}
	result.collect{
		def cit = claim.clone()
		cit.qualifiers = it
		cit
	}
  }
}
