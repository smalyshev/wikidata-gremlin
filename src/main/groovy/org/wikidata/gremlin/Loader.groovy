package org.wikidata.gremlin

// Apache 2 Licensed

import com.tinkerpop.blueprints.Graph
import com.tinkerpop.blueprints.Vertex
import com.tinkerpop.blueprints.Edge
import com.tinkerpop.blueprints.Direction
import com.thinkaurelius.titan.core.Order

import java.text.SimpleDateFormat

import com.thinkaurelius.titan.core.attribute.Geoshape
import com.thinkaurelius.titan.core.Cardinality

import org.apache.commons.lang.SerializationUtils

import java.security.MessageDigest

import org.joda.time.format.*;
import org.joda.time.*;
import com.tinkerpop.blueprints.util.wrappers.batch.VertexIDType;
import groovy.util.logging.Slf4j

/**
 * Loading data from external format (e.g. JSON) into the database
 */
@Slf4j
class Loader {
  final Graph g
  private boolean batch = false
  private TitanBatchGraph bgraph = null
  /**
   * Should we skip loading properties?
   */
  private boolean skip_props
  private def currentVertex
  private def specNodes = [:]
  private long claims = 0

  Loader(Graph g, skip_props = true) {
    this.g = g
	this.skip_props = skip_props
  }

  /**
   * Set if this loader is a batch loader
   */
  public void setBatch(boolean val = true) {
	  batch = val
	  if(batch) {
		  bgraph = new TitanBatchGraph(g, VertexIDType.STRING, 100000)
		  bgraph.setVertexIdKey("wikibaseId")
		  bgraph.setLoadingFromScratch(false)
		  skip_props = true
	  } else {
	  	  bgraph = null
	  }
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

  /**
   * Load into the DB from hashmap stricture
   * @param item
   */
  public void loadFromItem(item) {
	  def id = item['id']
	  def isProperty = (id[0] == 'P')
	  if(isProperty && skip_props) {
		  return
	  }
      def v = getOrCreateVertex(id)
	  def isNew = false
	  if(v['stub']) {
		  log.info "Creating $id"
		  isNew = true
	  } else {
	  	if(item['lastrevid'] && v['lastrevid'] && v['lastrevid'] > item['lastrevid']) {
			  log.info "Ignoring update for $id - it's rev ${item['lastrevid']} and we already have ${v['lastrevid']}"
			  return
		  }
		  log.info "Updating $id"
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
	  if(item['lastrevid']) {
		  v['lastrevid'] = item['lastrevid']
	  }
	  if(isNew) {
		  v['stub'] = false
	  }
  	  if(isProperty) {
			g.commit()
		}
// Not committing here to allow DataLoader to group updates
  }

  /**
   * Fetch data from the Wikidata
   * @param String id
   * @return HashMap
   */
  private def fetchEntity(id) {
	  log.info "Fetching ${id} from Wikidata"
	  def text = new URL("http://www.wikidata.org/wiki/Special:EntityData/${id}.json").getText('UTF-8')
	  log.debug "Loaded id $id, got this: "+groovy.json.JsonOutput.prettyPrint(text)
	  def items = new groovy.json.JsonSlurper().parseText(text)
	  return items.entities[ id ]
  }

  /**
   * Get the data class for the wikibase type
   * @param wikitype
   * @return
   */
  private Class getDataType(wikitype)
  {
	  // TODO: figure out how to make types for with novalue/somevalue
	  switch(wikitype) {
		  case 'string':
		  case 'monolingualtext':
		  case 'url':
		  case 'wikibase-item':
		  case 'wikibase-property':
		  case 'commonsMedia':     return String.class
		  case 'globe-coordinate': return Geoshape.class
		  // For now, using Double, if we discover the precision is not enough, we'll
		  // have to look for better solution
		  case 'quantity':         return Double.class
		  case 'time':             return Long.class
		  default:
		  	return Object.class
	  }
  }

  /**
   * Get Property value name from Property name
   * @param itemName
   * @return String
   */
  public String getValueName(String itemName)
  {
	  return itemName+"value"
  }

  /**
   * Get Property qualifier name from Property name
   * @param itemName
   * @return String
   */
  public String getQualifierName(itemName)
  {
	  return itemName+"q"
  }

  /**
   * Get Property full value storage name from Property name
   * @param itemName
   * @return String
   */
  public String getAllValuesName(itemName)
  {
	  return itemName+"_all"
  }

  /**
   * Get Claim link name from Property name
   * @param itemName
   * @return String
   */
  public String getLinkName(itemName)
  {
	  return itemName+"link"
  }

  /**
   * Check if property exists and create it if not
   * Creates endge labels, properties, etc.
   */
  private void checkProperty(item)
  {
	  // According to the data model, all claims are edges
	  initEdge(item['id'])
	  // For value properties, we also need the value prop
	  initProperty(getValueName(item['id']), getDataType(item['datatype']), item['id'])
	  initProperty(getQualifierName(item['id']), getDataType(item['datatype']), item['id'])
	  if(item['datatype'] && item['datatype'] != 'wikibase-item'
		  && item['datatype'] != 'wikibase-property' && item['datatype'] != 'string') {
		  initProperty(getAllValuesName(getValueName(item['id'])))
		  initProperty(getAllValuesName(getQualifierName(item['id'])))
	  }

  }

  /**
   * Add vertex to batch graph
   */
  private def batchGetVertex(id) {
		def v = bgraph.getVertex(id)
		if(!v) {
			v = bgraph.addVertex(id)
			v.setProperty("stub", true)
		}
		return v
  }

  /**
   * Get vertex by Wikibase ID or create stub if it does not exist
   * @param id
   * @return
   */
  private def getOrCreateVertex(id)
  {
	if(batch) {
		return batchGetVertex(id)
	}
    def v = g.V('wikibaseId', id)
    if ( v ) {
      return v.next()
    }

    return g.addVertex([wikibaseId: id, stub: true])
  }

  /**
   * Create hash of an object
   * @param item Item to hash (must be hashmap-like)
   * @param fields fields to use, empty means all
   * @return hash string
   */
  public String getHash(item, String... fields)
  {
	  def m = MessageDigest.getInstance("SHA1")

	  for(el in item) {
		  if(fields && !(el.key in fields)) {
			  continue
		  }
		  m.update(SerializationUtils.serialize(el.value))
	  }
	  return new BigInteger(1, m.digest()).toString(16).padLeft( 40, '0')
  }

  /**
   * Refresh labels for the Item vertex
   * @param v Vertex
   * @param item Item data
   * @param isNew Is this a brand new data item?
   */
  private void updateLabels(v, item, isNew)
  {
	if(batch && !isNew) {
		log.info "Cannot update in batch mode, skipping..."
		return
	}
	def hash = getHash(item, 'labels')
	if(!isNew && v.contentHash == hash) {
		// hash did not change, we're done here
		return
	} else {
		v.contentHash = hash
	}
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
		if(!label?.value?.value) {
			continue;
		}
		def l = 'label' + label.key.capitalize()
      	try {
			v[l] = label.value.value
		} catch(java.lang.IllegalArgumentException e) {
			initProperty(l)
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

  /**
   * Update all claims on a vertex
   * @param v Vertex
   * @param item Item data
   * @param isNew Is this a brand new data item?
   */
  private void updateClaims(v, item, isNew) {
	def claimsById = [:]
	def linkCache = [:]
    for (claimsOnProperty in item.claims) {
		if(!claimsOnProperty.value.size()) {
			// empty claim, ignore
			continue
		}
      	for (claim in claimsOnProperty.value) {
	        if (claim.mainsnak == null) {
	          log.debug "${item.id}'s ${property} contains a claim without a mainSnak.  Skipping."
	          continue
	        }
			if (claim.rank == "deprecated") {
				// ignore deprecated claims for now
				continue
			}
			claim.contentHash = getHash(claim)
			claimsById[claim.contentHash] = claim
		}
	}
	if(!isNew) {
		if(batch) {
			log.info "Cannot update in batch mode, skipping..."
			return
		}
		for(cl in v.outE.has('edgeType', 'claim')) {
			if(!(cl.contentHash in claimsById)) {
				def prop = cl.getProperty('property')
				def target = cl.inV.next()
				log.debug "Dropping old claim ${cl.wikibaseId}"
				v.outE.has('contentHash', cl.contentHash).remove()
				// update also the links
				if(target.wikibaseId && target.wikibaseId[0] == 'Q'
					&& !v.out(prop).has('wikibaseId', target.wikibaseId).hasNext()) {
					def lname = getLinkName(prop)
					// if this link targeted v(target) and there are no links to it anymore
					// drop it from links
					for(vp in v.getProperties(lname)) {
						if(vp.getValue() == target.wikibaseId) {
							vp.remove();
							break;
						}
					}
					v.setProperty(getLinkName(prop)+"_", v[lname].join(' '))
				}
			} else {
				claimsById[cl.contentHash].exists = true
			}
		}
	}
	// the check is here since even if item has no current claims
	// we may want to delete old ones
    if (!item.claims) {
      return
    }
	for(claim in claimsById) {
		if(claim.value.exists) {
			// already added
			continue
		}
		for(eclaim in expand(claim.value)) {
			updateClaim(v, eclaim, claim.key, linkCache)
		}
	}
  }

  /**
   * Process qualifiers for the claim
   * @param item
   * @param qualifiers
   */
   private void addQualifiers(item, qualifiers) {
	  for(q in qualifiers) {
		  if(!q.value) {
			  continue
		  }
		  def qname = getQualifierName(q.key)
		  for(qitem in q.value) {
			  if(!qitem) {
				  continue;
			  }
			  def value = extractPropertyValueFromClaim(qitem)
			  if(value) {
				  item.setProperty(qname, value)
				  if(qitem.datatype != 'wikibase-item' && qitem.datatype != 'string' && qitem.datatype != 'wikibase-property') {
					  addAllValues(qitem.datavalue.value, qname, item)
				  }
			  }
		  }
	  }
  }
   
   /**
    * Fetch special node from the graph
    * @param String label
    * @return Vertex
    */
   private def getSpecialNode(String label)
   {
	   if(!(label in specNodes)) {
		   if(!batch) {
	   			specNodes[label] = g.V('specialValueNode', label).next()
		   } else {
		   		specNodes[label] = batchGetVertex(label)
		   }
	   } 
	   specNodes[label] 
   }

   /**
    * Get target vertex for the claim
    * @param data Data structure for snak
    * @param value Parsed value (mainly used for null checks)
    * @return Outgoing vertex or null
    */
  private def getTargetFromSnak(data, value)
  {
      def outgoing
      switch (data.snaktype) {
      case 'value':

  		if(data.datatype == 'wikibase-item') {
  			outgoing = getOrCreateVertex('Q' + data.datavalue.value[ 'numeric-id' ])
  		} else if(data.datatype == 'wikibase-property') {
		    outgoing = getOrCreateVertex('P' + data.datavalue.value[ 'numeric-id' ])
  		} else {
			if(value == null) {
				// if we are supposed to have a specific value, but could not find it
				// we produce "somevalue" link instead
				outgoing = getSpecialNode('unknown')
			} else {
				outgoing = getOrCreateVertex(data.property)
			}
  		}
        break
      case 'somevalue':
        outgoing = getSpecialNode('unknown')
        break
      case 'novalue':
        outgoing = getSpecialNode('novalue')
        break
      default:
        log.info "Unknown snaktype on ${v.wikibaseId}:  ${claim.snaktype}.  Skipping."
        return null
      }
	  return outgoing
  }

  /**
   * Create full value property storage
   * @param datavalue Value map
   * @param property Property name
   * @param item Target item where the property is set
   */
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
		  v[it.key] = it.value
	  }
	  item.setProperty(getAllValuesName(property), v)
  }

  /**
   * Update single claim on a vertex
   * @param v Vertex
   * @param claim Claim data
   * @param content Hash Hash for claim contents
   * 				(for cloned claims from expand, this is the hash of the whole claim)
   * @return Claim edge
   */
  private def updateClaim(Vertex v, claim, contentHash, linkCache)
  {
	def data = claim.mainsnak
	def value = extractPropertyValueFromClaim(data)
	def outgoing = getTargetFromSnak(claim.mainsnak, value)
	def isValue = (data.datatype != 'wikibase-item' && data.datatype != 'wikibase-property')

  	if(!outgoing) {
  		return null
  	}
	claims++;
	// Here is the strange thing: v.outE() is slow but v.outE('claim') is fast even though they return the same
	// So we provide edge for all claims so we could fetch them e.g. for deleting old ones
	// TODO: may not be needed anymore if we can use contentHash + property
//	def claimC = v.addEdge('claim', outgoing)
//	claimC.setProperty('wikibaseId', claim.id)
//	claimC.setProperty('property', data.property)
//	claimC.setProperty('contentHash', contentHash)

	log.debug "Updating claim ${claim.id}"
	def claimE = v.addEdge(data.property, outgoing)
	claimE.setProperty('edgeType', 'claim')
	claimE.setProperty('contentHash', contentHash)
	claimE.setProperty('rank', claim.rank == "preferred")
	claimE.setProperty('wikibaseId', claim.id)
	claimE.setProperty('property', data.property)

    if(claim.qualifiers) {
	  addQualifiers(claimE, claim.qualifiers)
    }

	if(isValue && value) {
		claimE.setProperty(getValueName(data.property), value)
		if(data.datatype != 'string' ) {
			addAllValues(data.datavalue.value, getValueName(data.property), claimE)
		}
	} else {
		// if it's a link, we can put it in value property for faster lookup
		// Since vertex and its edges are stored together but going to other side
		// requires loading another vertex
		if(!isValue && outgoing.wikibaseId) {
			claimE.setProperty(getValueName(data.property), outgoing.wikibaseId)
		}
	}

	// The code is arranged so that all operations on claimE finish before anything else is done
	// This is because of this comment in https://github.com/tinkerpop/blueprints/blob/master/blueprints-core/src/main/java/com/tinkerpop/blueprints/util/wrappers/batch/BatchGraph.java
	// * An important limitation of BatchGraph is that edge properties can only be set immediately after the edge has been added.
    // * If other vertices or edges have been created in the meantime, setting, getting or removing properties will throw
    // * exceptions. This is done to avoid caching of edges which would require a great amount of memory.
	// This is necessary only for BatchGraph
	if(!isValue && outgoing.wikibaseId) {
		def lname = getLinkName(data.property)
		if(!linkCache[lname]) {
			linkCache[lname] = [] as Set
		}
  	  // Create reverse index for edges to allow faster reverse lookups for queries like "get all humans"
  	  // See discussion: https://groups.google.com/forum/#!topic/aureliusgraphs/-3QQIWaT2H8
	  if(!(outgoing.wikibaseId in linkCache[lname])) {
		  // We need the check above since despite being called SET, the property
		  // would not accept the same value twice
	    v.addProperty(lname, outgoing.wikibaseId)
		linkCache[lname] << outgoing.wikibaseId
  	    // This is the same in a form of a string, for ES to index since ES does not index SET properties
	    v.setProperty(lname+"_", linkCache[lname].join(' '))
	  }
	}

	return claimE
  }

  /**
   * Extract value from claim data
   * @param claim
   * @return
   */
  private def extractPropertyValueFromClaim(claim, allowLink = false)
  {
	  if (claim.snaktype != 'value') {
		  return null
	  }
    def value = claim.datavalue.value
	try {
	    switch (claim.datatype) {
		    case 'commonsMedia':     return value
		    case 'globe-coordinate': return extractPropertyValueFromGlobeCoordinate(value)
		    case 'monolingualtext':  return "${value.language}:${value.text}".toString()
		    case 'quantity':         return value.amount as double
		    case 'string':           return value
		    case 'time':             return extractPropertyValueFromTime(value.time)
		    case 'url':              return value
			case 'wikibase-property':    return allowLink?"P"+value['numeric-id']:null
			case 'wikibase-item':    return allowLink?"Q"+value['numeric-id']:null
		    default:
		      log.info "Unknown datatype on ${getCurrentVertex()?.wikibaseId}: ${claim.datatype}.  Skipping."
		      return null
	    }
	} catch(Exception e) {
		log.info "Value parsing failed on ${getCurrentVertex()?.wikibaseId}: ${claim.datatype} with: $e"
		return null
	}
  }

  /**
   * Extract coordinate value
   * @param coord Coordinate data as map
   * @return
   */
  private def extractPropertyValueFromGlobeCoordinate(coord) {
    // Has latitude, longitude, alt, precision, globe
    return Geoshape.point(coord.latitude as double, coord.longitude as double)
  }

  private def getCurrentVertex() {
	  return currentVertex
  }

  // Format looks like   +0001783 - 12 - 23 T 00 : 00 : 00 Z without the spaces
  private timeFormat = /(sd{4,50})-(dd)-(dd)T(dd):(dd):(dd)Z/.replaceAll('s', /[+-]/).replaceAll('d', /[0-9]/)
  private static def df = ISODateTimeFormat.dateTimeNoMillis().withZone(DateTimeZone.UTC)
  /**
   * Get date representation from string
   * @param time Time string
   * @return
   */
  private long extractPropertyValueFromTime(String time) {
    def matches = time =~ timeFormat
    if (!matches) {
      log.warn "Error parsing date on ${getCurrentVertex()?.wikibaseId}:  ${time}.  Skipping."
      return null
    }
	long y = matches[0][1] as long
	if(y >= DateUtils.LARGEST_YEAR || y <= 0) {
		// If the year is too big, just do it in whole years
		// We store dates beyond 1AD in just years, as we have no calendar to apply there
		return DateUtils.fromYear(y)
	}
	// We also need to prevent dates like 2014-00-00 from breaking it
	// We'll convert them to 2014-01-01
	// No checks for dates like 31 September though - we can't auto-fix that
	def m = matches[0][2] as int
	if(m <1 || m > 12) {
		// replace it with January
		m = 1
	}
	def d = matches[0][3] as int
	if(d == 0) {
		d = 1
	}
	// return time in seconds
	DateUtils.fromDate(df.parseDateTime("${y}-${m}-${d}T${matches[0][4]}:${matches[0][5]}:${matches[0][6]}Z"))
	// We assume wikibase dates are in UTC with Z timezone, since we match the regexp against Z
	// If not, we'll need to fix it here
  }

  /**
   * Initialize Titan property
   * @param name
   * @return
   */
  private void initProperty(name, dataType=Object.class, label = null)
  {
	  def s = new Schema(g)
	  def mgmt = g.getManagementSystem()
	  def prop = s.addProperty(mgmt, name, dataType)
	  if(dataType != Object.class) {
		  // add indexes for Value properties
		  s.addIndex(mgmt, "by_"+name, Edge.class, [prop])
		  if(label && dataType != Geoshape.class && dataType != Double.class) {
			  def edge = mgmt.getEdgeLabel(label)
			  s.addVIndex(mgmt, edge, "by_"+name, prop)
		  }
		  def index = mgmt.getGraphIndex('by_values')
		  if(index) {
			  try {
				  mgmt.addIndexKey(index, prop, com.thinkaurelius.titan.core.schema.Parameter.of('mapped-name',name))
			  } catch(IllegalArgumentException e) {
		        // already added
		      }
		  }
	  }
      mgmt.commit()
  }

  /**
   * Initialize Titan edge
   * @param name
   * @return
   */
  private void initEdge(name)
  {
	  def s = new Schema(g)
	  def mgmt = g.getManagementSystem()
	  def wikibaseId = s.addProperty(mgmt, 'wikibaseId', String.class)
	  def rank = s.addProperty(mgmt, 'rank', Boolean.class)
	  def hash = s.addProperty(mgmt, "contentHash", String.class)
	  def etype = s.addProperty(mgmt, 'edgeType', String.class)
	  def label = s.addEdgeLabel(mgmt, name, rank, wikibaseId, hash, etype)

	  s.addVIndex(mgmt, label, "by_rank"+name, rank)
	  s.addVIndex(mgmt, label, "by_hash"+name, hash, wikibaseId)
	  s.addVIndex(mgmt, label, "by_type"+name, etype)

	  // Create reverse index for edges to allow faster reverse lookups for queries like "get all humans"
	  // See discussion: https://groups.google.com/forum/#!topic/aureliusgraphs/-3QQIWaT2H8
	  def linkName = getLinkName(name)
	  def prop = mgmt.getPropertyKey(linkName)
	  if(!prop) {
		prop = mgmt.makePropertyKey(linkName).dataType(String.class).cardinality(Cardinality.SET).make()
	  }
	  // This one stringified for Elastic
	  def prop2 = s.addProperty(mgmt, linkName+"_", String.class)
	  s.addIndex(mgmt, "by_"+linkName, Vertex.class, [prop])
	  // Add Elastic field to Elastic index
	  def mindex = mgmt.getGraphIndex('by_links')
	  if(mindex) {
		  try {
			  mgmt.addIndexKey(mindex, prop2, com.thinkaurelius.titan.core.schema.Parameter.of('mapped-name',linkName+"_"))
		  } catch(IllegalArgumentException e) {
		  // already added
		  }
	  }

      mgmt.commit()
  }

  /**
   * Convert one claim to one or more claims where each qualifier is encountered only once
   * P580 and P582 (start date/end date) are grouped together.
   * @param claim
   * @return
   */
  public def expand(claim)
  {
  	if(!claim.qualifiers) {
		return [claim]
	}
  	def result = [claim.qualifiers]
	def newresult
	def newr
	def order = claim['qualifiers-order'].clone()
	log.trace "Processing ${claim.id} for ${claim.mainsnak.property}"
	// P580 and P582 require special handling as a pair
	if(claim.qualifiers['P580'] && claim.qualifiers['P582'] && (claim.qualifiers['P580']?.size() > 1 || claim.qualifiers['P582']?.size() > 1)) {
		log.trace "Processing qualifiers P580/2"
		order.removeAll(['P580', 'P582'])
		newresult = []
		for(i in 0..<claim.qualifiers['P580'].size()) {
			for(r in result) {
				newr = r.clone()
				newr['P580'] = [claim.qualifiers['P580'][i]]
				if(claim.qualifiers['P582'] && claim.qualifiers['P582'][i]) {
					newr['P582'] = [claim.qualifiers['P582'][i]]
				}
				newresult << newr
			}
		}
		result = newresult
		log.trace "Result is now ${result.size()}"
	}
  	for(qual in order) {
		log.trace "Processing qualifier $qual"
  		newresult = []
  		for(qitem in claim.qualifiers[qual]) {
  			for(r in result) {
  				newr = r.clone()
  				newr[qual] = [qitem]
  				newresult << newr
  			}
  		}
  		result = newresult
		log.trace "Result is now ${result.size()}"
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
  
  public long getClaims() {
	  return claims;
  }
  
  public void resetClaims() {
	  claims = 0
  }
}
