package org.wikidata.gremlin

import com.tinkerpop.blueprints.Graph
import com.tinkerpop.blueprints.Vertex
import com.tinkerpop.blueprints.Edge
import com.tinkerpop.blueprints.Direction
import com.thinkaurelius.titan.core.Order

// Apache 2 Licensed

class Schema {
  final Graph g
  public final static USE_ELASTIC = true;

  Schema(Graph g) {
    this.g = g
  }

  def setupSchema() {
    // We use supportsTransactions as a standin for supporting getManagementSystem.....
    if (!g.getFeatures().supportsTransactions) {
      return
    }
    def mgmt = g.getManagementSystem()
	def wikibaseId = addProperty(mgmt, 'wikibaseId', String.class)
	def vid = addProperty(mgmt, 'vid', String.class)
	def type = addProperty(mgmt, 'type', String.class)
	addProperty(mgmt, 'datatype', String.class)
	addProperty(mgmt, 'stub', Boolean.class)
	addProperty(mgmt, 'lastrevid', Integer.class)
    // There are two nodes with the special_value_node parameter: 'unknown', 'no_value'.  They are used to
    // for vertex type properties that are unknown or known to have no value.
	def specialValueNode = addProperty(mgmt, 'specialValueNode', String.class)
	// For edges
	def prop = addProperty(mgmt, 'property', String.class)
	def etype = addProperty(mgmt, 'edgeType', String.class)
	def rank = addProperty(mgmt, 'rank', Boolean.class)
	def hash = addProperty(mgmt, "contentHash", String.class)
	def claims = addEdgeLabel(mgmt, 'claim', prop, wikibaseId, hash)

	addIndex(mgmt, 'by_wikibaseId', Vertex.class, [wikibaseId], true)
	addIndex(mgmt, 'by_wikibaseIdE', Edge.class, [wikibaseId]) // may not be needed
	addIndex(mgmt, 'by_vid', Vertex.class, [vid], true)
	addIndex(mgmt, 'by_specialValueNode', Vertex.class, [specialValueNode], true)
	addIndex(mgmt, 'by_type', Vertex.class, [type])
	addIndex(mgmt, 'by_etype', Edge.class, [etype])
	addIndex(mgmt, 'by_prop', Edge.class, [prop])
	//addIndex(mgmt, 'by_hash', Vertex.class, [hash]) //? may not be needed
	addIndex(mgmt, 'by_Ehash', Edge.class, [hash])

	// ??? Maybe not needed anymore
	addVIndex(mgmt, claims, 'by_claims', rank, prop, hash)

	// Mixed indexes
	// Index for P123link values - using P123link_ for now, no direct support for SET properties
	addMixedIndex(mgmt, "by_links", Vertex.class, [wikibaseId]);
	// Index for P123value and P123q values
	addMixedIndex(mgmt, "by_values", Edge.class, [])

    mgmt.commit()
  }

  def addProperty(mgmt, name, type) {
	  def key = mgmt.getPropertyKey(name)
	  if(key) {
		  return key
	  }
	  println "Creating property $name"
	  mgmt.makePropertyKey(name).dataType(type).make()
  }

  def addEdgeLabel(mgmt, name, Object[] signature)
  {
	  def label = mgmt.getEdgeLabel(name);
	  if(label) {
		  return label
	  }
	  println "Creating label $name"
	  mgmt.makeEdgeLabel(name).signature(*signature).make()
  }

  def addIndex(mgmt, name, type, keys, unique = false)
  {
	  def idx = mgmt.getGraphIndex(name);
	  if(idx) {
		  return idx
	  }
	  println "Creating index $name"
	  idx = mgmt.buildIndex(name, type)
	  for(k in keys) {
		  idx = idx.addKey(k)
	  }
	  if(unique) {
		 idx = idx.unique()
	  }
	  idx.buildCompositeIndex()
  }

  def addMixedIndex(mgmt, name, type, keys)
  {
	  if(!USE_ELASTIC) {
		  return;
	  }
	  def idx = mgmt.getGraphIndex(name);
	  if(idx) {
		  return idx
	  }
	  println "Creating mixed index $name"
	  idx = mgmt.buildIndex(name, type)
	  for(k in keys) {
		  idx = idx.addKey(k, com.thinkaurelius.titan.core.schema.Parameter.of('mapped-name',name))
	  }
	  idx.buildMixedIndex("search")
  }

    def addVIndex(mgmt, label, name, Object[] keys)
  {
	  def idx = mgmt.getRelationIndex(label, name);
	  if(idx) {
		  return idx
	  }
	  println "Creating vertex index $name"
	  mgmt.buildEdgeIndex(label, name, Direction.BOTH, Order.DESC, *keys)
  }

  def setupConstantData() {
    def changed = false
    if (!g.V('specialValueNode', 'unknown')) {
      println "Adding specialValueNode: 'unknown'"
      changed = true
      g.addVertex([specialValueNode: 'unknown', stub: false])
    }
    if (!g.V('specialValueNode', 'novalue')) {
      println "Adding specialValueNode: 'novalue'"
      changed = true
      g.addVertex([specialValueNode: 'novalue', stub: false])
    }
    if (changed && g.getFeatures().supportsTransactions) {
      g.commit()
    }
  }
}
