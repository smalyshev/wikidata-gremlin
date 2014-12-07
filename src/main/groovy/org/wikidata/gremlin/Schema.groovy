package org.wikidata.gremlin

import com.tinkerpop.blueprints.Graph
import com.tinkerpop.blueprints.Vertex

// Apache 2 Licensed

class Schema {
  final Graph g

  Schema(Graph g) {
    this.g = g
  }

  def setupSchema() {
    // We use supportsTransactions as a standin for supporting getManagementSystem.....
    if (!g.getFeatures().supportsTransactions) {
      return
    }
    def mgmt = g.getManagementSystem()
    def changed = false
    if (!mgmt.containsGraphIndex('by_wikibaseId')) {
      println "Adding key and index for wikibaseId"
      changed = true
      def wikibaseId = mgmt.makePropertyKey('wikibaseId').dataType(String.class).make()
      mgmt.buildIndex('by_wikibaseId',Vertex.class).addKey(wikibaseId).unique().buildCompositeIndex()
    }
    // There are two nodes with the special_value_node parameter: 'unknown', 'no_value'.  They are used to
    // for vertex type properties that are unknown or known to have no value.
    if (!mgmt.containsGraphIndex('by_specialValueNode')) {
      println "Adding key and index for specialValueNode"
      changed = true
      def specialValueNode = mgmt.makePropertyKey('specialValueNode').dataType(String.class).make()
      mgmt.buildIndex('by_specialValueNode',Vertex.class).addKey(specialValueNode).unique().buildCompositeIndex()
		mgmt.makePropertyKey('stub').dataType(Boolean.class).make()
		mgmt.makePropertyKey('type').dataType(String.class).make()
		mgmt.makePropertyKey('datatype').dataType(String.class).make()
		mgmt.makePropertyKey('rank').dataType(Boolean.class).make()
    }
/*    if (!mgmt.containsGraphIndex('by_type_and_label')) {
      println "Adding key and index for by_type_and_label"
      changed = true
      def label = mgmt.makePropertyKey('labelEn').dataType(String.class).make()
      def type = mgmt.makePropertyKey('type').dataType(String.class).make()
      mgmt.buildIndex('by_type_and_label',Vertex.class).addKey(type).addKey(label).buildCompositeIndex()
    }
*/    
      mgmt.commit()
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
