package org.wikidata.gremlin

import groovy.lang.Closure;

class RexsterInit
{
	static RexsterInit init(script)
	{
		return new RexsterInit().setup()
	}
	
	protected RexsterInit setup()
	{
		getDSL().setup()
		this
	}

	protected DomainSpecificLanguage getDSL()
	{
		return new DomainSpecificLanguage()
	}

}
